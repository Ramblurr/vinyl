;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.impl
  (:require
   [clojure.core.async :as async]
   [ol.vinyl.bus :as bus]
   [ol.vinyl.interop.api :as api]
   [ol.vinyl.interop.parsing :as parsing]
   [ol.vinyl.interop.player :as player]
   [ol.vinyl.lifecycle :as lifecycle]
   [ol.vinyl.playback :as playback]
   [ol.vinyl.util :as util]))

(def ^:private native-runtime
  {:create-native-player! player/create-audio-player!
   :drain-native-player! player/drain-before!
   :drain-timeout-ms 30000
   :join-loop! (fn [_loop-name ^Thread thread deadline-nanos]
                 (if thread
                   (let [remaining-nanos
                         (lifecycle/remaining-nanos deadline-nanos)]
                     (when (and (.isAlive thread)
                                (pos? remaining-nanos))
                       (.join thread
                              (quot remaining-nanos 1000000)
                              (int (rem remaining-nanos 1000000))))
                     (not (.isAlive thread)))
                   true))
   :playback-init! playback/init!
   :playback-release! playback/release!
   :release-native-player! player/release-before!
   :start-control-loop! bus/start-control-loop!
   :start-event-loop! bus/start-event-loop!})

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

(defn- run-actions! [actions deadline-nanos]
  (loop [remaining actions
         failed #{}
         errors []]
    (if-let [{:keys [name run skip-if-failed]} (first remaining)]
      (if (some failed skip-if-failed)
        (recur (next remaining) failed errors)
        (let [[status value] (outcome (fn [] (run deadline-nanos)))]
          (if (= :error status)
            (recur (next remaining)
                   (conj failed name)
                   (conj errors value))
            (recur (next remaining) failed errors))))
      {:errors errors
       :failed failed})))

(defn- close-intake!
  [{::keys [<close <control <events]}]
  (async/close! <close)
  (async/close! <control)
  (async/close! <events))

(defn- rollback-construction! [primary context actions]
  (close-intake! context)
  (let [deadline-nanos
        (lifecycle/deadline-after-ms
         (lifecycle/drain-timeout-ms (::lifecycle context)))
        {:keys [errors failed]}
        (run-actions! (reverse actions) deadline-nanos)]
    (when (and (::player context)
               (some failed #{:join-control-loop :join-event-loop}))
      (player/quarantine! (::player context)))
    (throw (add-suppressed! primary errors))))

(defn- acquire! [initial-context steps]
  (loop [context initial-context
         compensations []
         remaining steps]
    (if-let [{:keys [acquire compensation]} (first remaining)]
      (let [[status value] (outcome (fn [] (acquire context)))]
        (if (= :error status)
          (rollback-construction! value context compensations)
          (let [next-context value
                next-compensation (when compensation
                                    (compensation next-context))]
            (recur next-context
                   (cond-> compensations
                     next-compensation (conj next-compensation))
                   (next remaining)))))
      context)))

(defn- loop-drain-error [loop-name timeout-ms]
  (ex-info "Timed out while draining a player loop."
           {:loop loop-name
            :timeout-ms timeout-ms
            :type :ol.vinyl.impl/loop-drain-timeout}))

(defn- join-loop-error [runtime loop-name thread deadline-nanos]
  (let [[status value]
        (outcome
         (fn []
           ((:join-loop! runtime)
            loop-name
            thread
            deadline-nanos)))
        error (cond
                (= :error status) value
                value nil
                :else (loop-drain-error
                       loop-name
                       (:drain-timeout-ms runtime)))]
    (when (instance? InterruptedException error)
      (.interrupt (Thread/currentThread)))
    error))

(defn- interrupted-error? [error]
  (instance? InterruptedException error))

(defn- release-once!
  [{::keys [control-loop event-loop lifecycle player] :as instance} runtime]
  (let [deadline-nanos
        (lifecycle/deadline-after-ms (:drain-timeout-ms runtime))]
    (lifecycle/begin-release! lifecycle)
    (close-intake! instance)
    (let [control-error (join-loop-error runtime
                                         :control
                                         control-loop
                                         deadline-nanos)
          event-error (when-not (interrupted-error? control-error)
                        (join-loop-error runtime
                                         :event
                                         event-loop
                                         deadline-nanos))
          loop-errors (into []
                            (remove nil?)
                            [control-error event-error])]
      (if (seq loop-errors)
        (do
          (player/quarantine! player)
          (lifecycle/complete-release! lifecycle :release-failed)
          (throw-errors! loop-errors))
        (try
          ((:drain-native-player! runtime) player deadline-nanos)
          (lifecycle/with-exclusive
            lifecycle
            deadline-nanos
            (fn []
              (let [[playback-status playback-value]
                    (outcome (fn []
                               ((:playback-release! runtime) instance)))
                    [player-status player-value]
                    (outcome (fn []
                               ((:release-native-player! runtime)
                                player
                                deadline-nanos)))
                    errors
                    (into []
                          (remove nil?)
                          [(when (= :error playback-status) playback-value)
                           (when (= :error player-status) player-value)])]
                (throw-errors! errors))))
          (lifecycle/complete-release! lifecycle :released)
          nil
          (catch Throwable error
            (when (contains? #{:ol.vinyl.lifecycle/drain-interrupted
                               :ol.vinyl.lifecycle/drain-timeout}
                             (:type (ex-data error)))
              (player/quarantine! player))
            (lifecycle/complete-release! lifecycle :release-failed)
            (throw error)))))))

(defn create-player-with-runtime!
  "Creates an opaque player using `runtime` as its lifecycle boundary."
  [{:keys [media-player-factory] :as _opts} runtime]
  (let [<control (async/chan (async/sliding-buffer 32))
        <events (async/chan (async/sliding-buffer 32))
        <close (async/chan)
        lifecycle (lifecycle/create
                   {:drain-timeout-ms (:drain-timeout-ms runtime)})
        state_ (atom {:subscriptions {}})
        initial-context {::<control <control
                         ::<events <events
                         ::<close <close
                         ::lifecycle lifecycle
                         ::state_ state_}
        steps
        [{:acquire  (fn [context]
                      (assoc context
                             ::player
                             ((:create-native-player! runtime)
                              <events
                              media-player-factory)))
          :compensation (fn [{::keys [player]}]
                          {:name :release-native-player
                           :run (fn [deadline-nanos]
                                  ((:release-native-player! runtime)
                                   player
                                   deadline-nanos))
                           :skip-if-failed #{:join-control-loop :join-event-loop}})}
         {:acquire (fn [context]
                     ((:playback-init! runtime) context)
                     context)
          :compensation (fn [context]
                          {:name :release-playback
                           :run (fn [_deadline-nanos]
                                  ((:playback-release! runtime) context))})}
         {:acquire (fn [context]
                     (assoc context
                            ::control-loop
                            ((:start-control-loop! runtime) context)))
          :compensation (fn [{::keys [control-loop]}]
                          {:name :join-control-loop
                           :run (fn [deadline-nanos]
                                  (when-let [error (join-loop-error runtime
                                                                    :control
                                                                    control-loop
                                                                    deadline-nanos)]
                                    (throw error)))})}
         {:acquire (fn [context]
                     (assoc context
                            ::event-loop
                            ((:start-event-loop! runtime) context)))
          :compensation (fn [{::keys [event-loop]}]
                          {:name :join-event-loop
                           :run (fn [deadline-nanos]
                                  (when-let [error (join-loop-error runtime
                                                                    :event
                                                                    event-loop
                                                                    deadline-nanos)]
                                    (throw error)))})}]
        instance (acquire! initial-context steps)
        release_ (delay (release-once! instance runtime))]
    (assoc instance ::release_ release_)))

(defn create-player-impl [opts]
  (create-player-with-runtime! opts native-runtime))

(defn release-player-impl! [{::keys [release_]}]
  (force release_))

(defn- require-lifecycle! [controller]
  (when-not (:lease-lock controller)
    (throw
     (ex-info "Expected a player created by ol.vinyl/create-player."
              {:type :ol.vinyl/invalid-player})))
  controller)

(defn ensure-not-released! [{::keys [lifecycle]}]
  (lifecycle/ensure-active! (require-lifecycle! lifecycle)))

(defn- with-player-use [{::keys [lifecycle player]} f]
  (lifecycle/with-use (require-lifecycle! lifecycle)
    (fn [] (f player))))

(defn get-repeat-impl [instance]
  (with-player-use instance api/get-repeat))

(defn get-channel-impl [instance]
  (with-player-use instance api/get-channel))

(defn get-delay-impl [instance]
  (with-player-use instance api/get-delay))

(defn get-equalizer-impl [instance]
  (with-player-use instance api/get-equalizer))

(defn muted?-impl [instance]
  (with-player-use instance api/muted?))

(defn get-output-device-impl [instance]
  (with-player-use instance api/get-output-device))

(defn get-output-devices-impl [instance]
  (with-player-use instance api/get-output-devices))

(defn get-volume-impl [instance]
  (with-player-use instance api/get-volume))

(defn playable?-impl [instance]
  (with-player-use instance api/playable?))

(defn playing?-impl [instance]
  (with-player-use instance api/playing?))

(defn seekable?-impl [instance]
  (with-player-use instance api/seekable?))

(defn can-pause?-impl [instance]
  (with-player-use instance api/can-pause?))

(defn get-length-impl [instance]
  (with-player-use instance api/get-length))

(defn get-time-impl [instance]
  (with-player-use instance api/get-time))

(defn get-position-impl [instance]
  (with-player-use instance api/get-position))

(defn get-rate-impl [instance]
  (with-player-use instance api/get-rate))

(defn get-video-outputs-impl [instance]
  (with-player-use instance api/get-video-outputs))

(defn get-state-impl [instance]
  (with-player-use instance api/get-state))

(defn get-current-impl [{::keys [player] :as instance}]
  (with-player-use
    instance
    (fn [_player]
      (let [current-queue-track (playback/get-current instance)]
        (if current-queue-track
          (merge current-queue-track
                 {:position (api/get-position player)
                  :state (api/get-state player)
                  :time (api/get-time player)})
          (when-let [current-media (api/get-media player)]
            (try
              {:duration (api/get-duration player)
               :meta (parsing/meta-for current-media)
               :mrl (api/get-mrl player)
               :position (api/get-position player)
               :state (api/get-state player)
               :time (api/get-time player)}
              (finally
                (.release current-media)))))))))

(defn get-media-mrl-impl [instance]
  (with-player-use instance api/get-mrl))

(defn get-media-type-impl [instance]
  (with-player-use instance api/get-type))

(defn get-media-duration-impl [instance]
  (with-player-use instance api/get-duration))

(defn get-media-tracks-impl [instance & track-types]
  (with-player-use instance
    (fn [player]
      (apply api/get-tracks player track-types))))

(defn get-audio-tracks-impl [instance]
  (with-player-use instance api/get-audio-tracks))

(defn get-video-tracks-impl [instance]
  (with-player-use instance api/get-video-tracks))

(defn get-text-tracks-impl [instance]
  (with-player-use instance api/get-text-tracks))

(defn get-media-statistics-impl [instance]
  (with-player-use instance api/get-statistics))

(defn list-queue-impl [instance]
  (with-player-use
    instance
    (fn [_player]
      (playback/list-all instance))))

(defn parse-meta [{::keys [lifecycle player] :as _instance} paths]
  (let [release-use! (lifecycle/acquire-use!
                      (require-lifecycle! lifecycle))
        parsing-opts {:media-player-factory
                      (:vlc/media-player-factory player)}]
    (if (empty? paths)
      (try
        (parsing/parse-meta paths parsing-opts)
        (finally
          (release-use!)))
      (let [result_ (promise)]
        (try
          (util/thread
            (try
              (let [result
                    (try
                      @(parsing/parse-meta paths parsing-opts)
                      (catch Throwable error
                        error))]
                (deliver result_ result))
              (finally
                (release-use!))))
          (catch Throwable error
            (release-use!)
            (deliver result_ error)))
        result_))))
