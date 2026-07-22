;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.interop.player
  "Unsafe low-level vlcj ownership primitives.

  Application code should use [[ol.vinyl]] instead. Values in this namespace
  provide exactly-once native cleanup but do not own the high-level lifecycle
  gate that excludes public commands, queries, and metadata parsing from release."
  (:require
   [clojure.core.async :as async]
   [ol.vinyl.interop.listener :as listener]
   [ol.vinyl.lifecycle :as lifecycle])
  (:import
   [java.util.concurrent
    CompletableFuture
    ConcurrentHashMap
    ExecutionException
    TimeUnit
    TimeoutException]
   [java.util.concurrent.atomic AtomicBoolean AtomicLong]
   [uk.co.caprica.vlcj.factory MediaPlayerFactory]
   [uk.co.caprica.vlcj.player.base MediaPlayer State]))

(def ^String/1 audio-media-player-args
  (into-array String ["--quiet" "--intf=dummy"]))

(defonce ^java.util.Set ^:private failed-native-resources_
  (ConcurrentHashMap/newKeySet))

(defn ^:no-doc quarantine!
  "Keeps an uncertain native resource graph strongly reachable."
  [resources]
  (.add failed-native-resources_ resources)
  nil)

(def ^:private native-runtime
  {:add-media-listener! (fn [^MediaPlayer media-player event-listener]
                          (.addMediaEventListener (.events media-player)
                                                  event-listener))
   :add-player-listener! (fn [^MediaPlayer media-player event-listener]
                           (.addMediaPlayerEventListener (.events media-player)
                                                         event-listener))
   :drain-timeout-ms 30000
   :new-factory! #(MediaPlayerFactory. audio-media-player-args)
   :new-media-listener! listener/create-media-event-listener
   :new-player! (fn [^MediaPlayerFactory factory]
                  (.newMediaPlayer (.mediaPlayers factory)))
   :new-player-listener! listener/create-media-player-listener
   :new-stop-listener! listener/stop-completion-listener
   :player-state (fn [^MediaPlayer media-player]
                   (.state (.status media-player)))
   :release-factory! (fn [^MediaPlayerFactory factory]
                       (.release factory))
   :release-player! (fn [^MediaPlayer media-player]
                      (.release media-player))
   :remove-media-listener! (fn [^MediaPlayer media-player event-listener]
                             (.removeMediaEventListener (.events media-player)
                                                        event-listener))
   :remove-player-listener! (fn [^MediaPlayer media-player event-listener]
                              (.removeMediaPlayerEventListener
                               (.events media-player)
                               event-listener))
   :stop! (fn [^MediaPlayer media-player]
            (.stop (.controls media-player)))
   :stop-timeout-ms 3000
   :stopped? #(contains? #{State/ERROR State/STOPPED} %)
   :submit! (fn [^MediaPlayer media-player task]
              (.submit media-player ^Runnable task))})

(defn- outcome [f]
  (try
    [:ok (f)]
    (catch Throwable t
      [:error t])))

(defn- add-suppressed! [^Throwable primary errors]
  (doseq [^Throwable error errors]
    (when-not (identical? primary error)
      (.addSuppressed primary error)))
  primary)

(defn- throw-errors! [errors]
  (when-let [^Throwable primary (first errors)]
    (throw (add-suppressed! primary (rest errors)))))

(defn- run-actions! [actions]
  (loop [remaining actions
         failed #{}
         errors []]
    (if-let [{:keys [name run skip-if-failed]} (first remaining)]
      (if (some failed skip-if-failed)
        (recur (next remaining) failed errors)
        (let [[status value] (outcome run)]
          (if (= :error status)
            (recur (next remaining)
                   (conj failed name)
                   (conj errors value))
            (recur (next remaining) failed errors))))
      {:errors errors
       :failed failed})))

(defn- rollback! [primary resources actions]
  (let [{:keys [errors failed]} (run-actions! (reverse actions))]
    (cond
      (contains? failed :release-player)
      (quarantine! resources)

      (contains? failed :release-factory)
      (quarantine! (:factory resources)))
    (throw (add-suppressed! primary errors))))

(defn- acquire! [initial-context steps]
  (loop [context initial-context
         compensations []
         remaining steps]
    (if-let [{:keys [acquire compensation compensation-before?]}
             (first remaining)]
      (let [prepared-compensation (when (and compensation
                                             compensation-before?)
                                    (compensation context))
            prepared-actions (cond-> compensations
                               prepared-compensation
                               (conj prepared-compensation))
            [status value] (outcome #(acquire context))]
        (if (= :error status)
          (rollback! value context prepared-actions)
          (let [next-context value
                acquired-compensation (when (and compensation
                                                 (not compensation-before?))
                                        (compensation next-context))]
            (recur next-context
                   (cond-> prepared-actions
                     acquired-compensation
                     (conj acquired-compensation))
                   (next remaining)))))
      context)))

(defn- drain-timeout-error [runtime timeout-type cause]
  (ex-info "Timed out while draining the media-player executor."
           {:timeout-ms (:drain-timeout-ms runtime)
            :type timeout-type}
           cause))

(defn- submission-failed-error [error]
  (ex-info "Failed to submit media-player executor cleanup."
           {:type :ol.vinyl.interop.player/submission-failed}
           error))

(defn- submit-and-await!
  [runtime media-player deadline-nanos timeout-type task]
  (let [completion (CompletableFuture.)]
    (try
      ((:submit! runtime)
       media-player
       (fn []
         (try
           (task)
           (.complete completion nil)
           (catch Throwable t
             (.completeExceptionally completion t)))))
      (catch InterruptedException error
        (.interrupt (Thread/currentThread))
        (throw error))
      (catch Throwable error
        (throw (submission-failed-error error))))
    (try
      (.get completion
            (lifecycle/remaining-nanos deadline-nanos)
            TimeUnit/NANOSECONDS)
      (catch ExecutionException error
        (throw (.getCause error)))
      (catch TimeoutException error
        (throw (drain-timeout-error runtime timeout-type error)))
      (catch InterruptedException error
        (.interrupt (Thread/currentThread))
        (throw error)))))

(defn- submitted-operation-error
  [runtime media-player deadline-nanos operation]
  (let [[status value]
        (outcome
         (fn []
           (submit-and-await!
            runtime
            media-player
            deadline-nanos
            :ol.vinyl.interop.player/drain-timeout
            operation)))]
    (when (= :error status)
      value)))

(defn- unsafe-drain-error? [error]
  (or (instance? InterruptedException error)
      (contains? #{:ol.vinyl.interop.player/drain-timeout
                   :ol.vinyl.interop.player/submission-failed}
                 (:type (ex-data error)))))

(defn- unsafe-drain-errors? [errors]
  (boolean (some unsafe-drain-error? errors)))

(defn- await-stop-error
  [runtime ^CompletableFuture completion deadline-nanos]
  (let [remaining-nanos (lifecycle/remaining-nanos deadline-nanos)
        stop-timeout-nanos (.toNanos TimeUnit/MILLISECONDS
                                     (long (:stop-timeout-ms runtime)))
        wait-nanos (min remaining-nanos stop-timeout-nanos)
        deadline-limited? (<= remaining-nanos stop-timeout-nanos)]
    (try
      (.get completion wait-nanos TimeUnit/NANOSECONDS)
      nil
      (catch TimeoutException error
        (when deadline-limited?
          (drain-timeout-error
           runtime
           :ol.vinyl.interop.player/drain-timeout
           error)))
      (catch InterruptedException error
        (.interrupt (Thread/currentThread))
        error)
      (catch ExecutionException error
        (.getCause error)))))

(defn- stop-player-errors [runtime media-player deadline-nanos]
  (let [[state-status player-state]
        (outcome (fn []
                   ((:player-state runtime) media-player)))
        state-error (when (= :error state-status) player-state)]
    (if (and (= :ok state-status)
             ((:stopped? runtime) player-state))
      (cond-> [] state-error (conj state-error))
      (let [completion (CompletableFuture.)
            [listener-status stop-listener]
            (outcome
             (fn []
               ((:new-stop-listener! runtime)
                (fn []
                  (.complete completion nil)))))
            listener-error (when (= :error listener-status) stop-listener)
            errors (into [] (remove nil?) [state-error listener-error])
            add-error (when (= :ok listener-status)
                        (submitted-operation-error
                         runtime
                         media-player
                         deadline-nanos
                         (fn []
                           ((:add-player-listener! runtime)
                            media-player
                            stop-listener))))
            errors (cond-> errors add-error (conj add-error))]
        (if (unsafe-drain-errors? errors)
          errors
          (let [stop-error
                (submitted-operation-error
                 runtime
                 media-player
                 deadline-nanos
                 (fn []
                   ((:stop! runtime) media-player)))
                errors (cond-> errors stop-error (conj stop-error))]
            (if (unsafe-drain-errors? errors)
              errors
              (let [wait-error
                    (when (and (= :ok listener-status)
                               (nil? add-error))
                      (await-stop-error runtime completion deadline-nanos))
                    errors (cond-> errors wait-error (conj wait-error))]
                (if (unsafe-drain-errors? errors)
                  errors
                  (let [remove-error
                        (when (= :ok listener-status)
                          (submitted-operation-error
                           runtime
                           media-player
                           deadline-nanos
                           (fn []
                             ((:remove-player-listener! runtime)
                              media-player
                              stop-listener))))]
                    (cond-> errors
                      remove-error (conj remove-error))))))))))))

(defn- detach-listener-error
  [runtime media-player deadline-nanos remove-listener! event-listener]
  (submitted-operation-error
   runtime
   media-player
   deadline-nanos
   (fn []
     (remove-listener! media-player event-listener))))

(defn- drain-executor!
  [{:vlc/keys [media-player] :as resources} runtime deadline-nanos]
  (let [[status value]
        (outcome
         (fn []
           (submit-and-await!
            runtime
            media-player
            deadline-nanos
            :ol.vinyl.interop.player/drain-timeout
            (fn []))))]
    (when (= :error status)
      (quarantine! resources)
      (throw value))
    nil))

(defn- throw-unsafe-errors! [resources errors]
  (when (unsafe-drain-errors? errors)
    (quarantine! resources)
    (throw-errors! errors)))

(defn- release-resources!
  [{:vlc/keys [event-listener
               media-event-listener
               media-player
               media-player-factory
               own-factory?]
    :as resources}
   runtime
   deadline_
   drained?_]
  (let [deadline-nanos (.get ^AtomicLong deadline_)]
    (when-not (.get ^AtomicBoolean drained?_)
      (drain-executor! resources runtime deadline-nanos))
    (let [stop-errors (stop-player-errors runtime
                                          media-player
                                          deadline-nanos)]
      (throw-unsafe-errors! resources stop-errors)
      (let [player-listener-error
            (detach-listener-error runtime
                                   media-player
                                   deadline-nanos
                                   (:remove-player-listener! runtime)
                                   event-listener)
            player-listener-errors
            (cond-> stop-errors
              player-listener-error (conj player-listener-error))]
        (throw-unsafe-errors! resources player-listener-errors)
        (let [media-listener-error
              (detach-listener-error runtime
                                     media-player
                                     deadline-nanos
                                     (:remove-media-listener! runtime)
                                     media-event-listener)
              pre-release-errors
              (cond-> player-listener-errors
                media-listener-error (conj media-listener-error))]
          (throw-unsafe-errors! resources pre-release-errors)
          (let [[player-status player-value]
                (outcome (fn []
                           ((:release-player! runtime) media-player)))
                player-error (when (= :error player-status) player-value)
                [factory-status factory-value]
                (if (and own-factory? (nil? player-error))
                  (outcome (fn []
                             ((:release-factory! runtime)
                              media-player-factory)))
                  [:ok nil])
                factory-error (when (= :error factory-status) factory-value)
                errors (into pre-release-errors
                             (remove nil?)
                             [player-error factory-error])]
            (cond
              player-error (quarantine! resources)
              factory-error (quarantine! media-player-factory))
            (throw-errors! errors)
            nil))))))

(defn create-audio-player-with-runtime!
  "Creates an owned interop player using `runtime` as its native boundary."
  [<events factory runtime]
  (let [own-factory? (nil? factory)
        initial-context {:factory factory
                         :own-factory? own-factory?}
        factory-steps
        (when own-factory?
          [{:acquire (fn [context]
                       (assoc context :factory ((:new-factory! runtime))))
            :compensation
            (fn [{:keys [factory]}]
              {:name :release-factory
               :run #((:release-factory! runtime) factory)
               :skip-if-failed #{:release-player}})}])
        steps
        (concat
         factory-steps
         [{:acquire (fn [{:keys [factory] :as context}]
                      (assoc context :media-player
                             ((:new-player! runtime) factory)))
           :compensation
           (fn [{:keys [media-player]}]
             {:name :release-player
              :run #((:release-player! runtime) media-player)})}
          {:acquire (fn [context]
                      (assoc context
                             :event-listener
                             ((:new-player-listener! runtime)
                              (fn [event]
                                (async/put! <events event)))))}
          {:acquire (fn [context]
                      (assoc context
                             :media-event-listener
                             ((:new-media-listener! runtime)
                              (fn [event]
                                (async/put! <events event)))))}
          {:acquire (fn [{:keys [event-listener media-player] :as context}]
                      ((:add-player-listener! runtime)
                       media-player
                       event-listener)
                      context)
           :compensation
           (fn [{:keys [event-listener media-player]}]
             {:name :remove-player-listener
              :run #((:remove-player-listener! runtime)
                     media-player
                     event-listener)})
           :compensation-before? true}
          {:acquire (fn [{:keys [media-event-listener media-player] :as context}]
                      ((:add-media-listener! runtime)
                       media-player
                       media-event-listener)
                      context)
           :compensation
           (fn [{:keys [media-event-listener media-player]}]
             {:name :remove-media-listener
              :run #((:remove-media-listener! runtime)
                     media-player
                     media-event-listener)})
           :compensation-before? true}])
        context (acquire! initial-context steps)
        resources {:vlc/event-listener (:event-listener context)
                   :vlc/media-event-listener (:media-event-listener context)
                   :vlc/media-player (:media-player context)
                   :vlc/media-player-factory (:factory context)
                   :vlc/own-factory? own-factory?}
        deadline_ (AtomicLong. Long/MAX_VALUE)
        drained?_ (AtomicBoolean.)
        drain-before!_ (fn [deadline-nanos]
                         (drain-executor! resources
                                          runtime
                                          deadline-nanos)
                         (.set drained?_ true))
        release_ (delay (release-resources! resources
                                            runtime
                                            deadline_
                                            drained?_))]
    (assoc resources
           :vlc/deadline_ deadline_
           :vlc/drain-before!_ drain-before!_
           :vlc/drain-timeout-ms (:drain-timeout-ms runtime)
           :vlc/release_ release_)))

(defn create-audio-player!
  "Creates a VLC audio player with the default settings.

  The returned value owns its native player and listeners and must be passed to
  [[release!]] when no longer needed. A supplied `factory` remains caller-owned."
  ([<events]
   (create-audio-player! <events nil))
  ([<events factory]
   (create-audio-player-with-runtime! <events factory native-runtime)))

(defn- set-earlier-deadline! [^AtomicLong deadline_ deadline-nanos]
  (loop [current (.get deadline_)]
    (when (< deadline-nanos current)
      (when-not (.compareAndSet deadline_ current deadline-nanos)
        (recur (.get deadline_))))))

(defn ^:no-doc drain-before!
  "Drains prior vlcj executor work before high-level exclusive ownership."
  [{:vlc/keys [deadline_ drain-before!_]} deadline-nanos]
  (set-earlier-deadline! deadline_ deadline-nanos)
  (drain-before!_ deadline-nanos))

(defn ^:no-doc release-before!
  "Releases the interop player once using the shared monotonic deadline."
  [{:vlc/keys [deadline_ release_] :as _player} deadline-nanos]
  (set-earlier-deadline! deadline_ deadline-nanos)
  (force release_))

(defn release!
  "Releases the interop player exactly once and returns `nil` on success."
  [{:vlc/keys [drain-timeout-ms] :as interop-player}]
  (release-before! interop-player
                   (lifecycle/deadline-after-ms drain-timeout-ms)))

(defn run-on-player-thread
  "Runs `f` asynchronously on the vlcj player thread.

  Native event callbacks must publish work through this function instead of
  calling back into LibVLC from the callback thread."
  [{:vlc/keys [^MediaPlayer media-player]} f]
  (assert (instance? MediaPlayer media-player)
          "run-on-player-thread requires a valid MediaPlayer instance")
  (assert (fn? f) "run-on-player-thread requires a function to run on the player thread")
  (.submit media-player f))

(comment
  (def _fac (MediaPlayerFactory.))
  (def _mp (.newMediaPlayer (.mediaPlayers _fac)))

  (.addMediaPlayerEventListener
   (.events _mp)
   (listener/wait-for-stop-listener #(tap> :got-stop)))

  (-> _mp .status .state)
  (-> _mp .controls .stop)
  (.release _mp)
  (.release _fac)
  :rcf)