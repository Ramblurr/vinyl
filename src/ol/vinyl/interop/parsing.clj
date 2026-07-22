;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.interop.parsing
  (:require
   [ol.vinyl.interop.enum :as enum]
   [ol.vinyl.interop.listener :as listener]
   [ol.vinyl.protocols :as protocols])
  (:import
   [java.util.concurrent
    CompletableFuture
    ExecutionException
    ExecutorService
    Executors
    ThreadFactory
    TimeUnit
    TimeoutException]
   [uk.co.caprica.vlcj.factory MediaPlayerFactory]
   [uk.co.caprica.vlcj.media
    AudioTrackInfo
    Media
    MediaEventListener
    MediaParsedStatus
    MetaData
    ParseFlag]
   [uk.co.caprica.vlcj.medialist MediaList]))

(defn make-medias!
  "Creates one owned vlcj `Media` wrapper for each path.

  The returned vector is fully realized. The caller owns every wrapper."
  [^MediaPlayerFactory factory paths]
  (loop [remaining (seq paths)
         medias []]
    (if-let [^String path (first remaining)]
      (let [outcome (try
                      [:ok (.newMedia (.media factory) path nil)]
                      (catch Throwable t
                        [:error t]))]
        (if (= :error (first outcome))
          (let [^Throwable error (second outcome)]
            (doseq [^Media media medias]
              (try
                (.release media)
                (catch Throwable release-error
                  (when-not (identical? error release-error)
                    (.addSuppressed error release-error)))))
            (throw error))
          (recur (next remaining) (conj medias (second outcome)))))
      medias)))

(defn release-medias!
  "Releases every non-nil vlcj `Media` wrapper in `medias`."
  [medias]
  (doseq [^Media media medias]
    (when media
      (.release media))))

(defn metadata->map [^MetaData metadata]
  (reduce (fn [result [metadata-type value]]
            (assoc result (enum/enum->keyword metadata-type) value))
          {}
          (.values metadata)))

(defn audio-track->info [^AudioTrackInfo track]
  {:channels (.channels track)
   :rate (.rate track)
   :bit-rate (.bitRate track)
   :codec (.codec track)
   :codec-description (.codecDescription track)
   :codec-name (.codecName track)
   :description (.description track)
   :id (.id track)
   :language (.language track)
   :level (.level track)
   :profile (.profile track)})

(defn media->info [^Media media]
  (protocols/map->Track
   {:mrl (.mrl (.info media))
    :meta (metadata->map (.asMetaData (.meta media)))
    :duration (.duration (.info media))
    :audio-tracks (mapv audio-track->info (.audioTracks (.info media)))
    :media-type (enum/enum->keyword (.type (.info media)))}))

(defn meta-for [^Media media]
  (some-> media .meta .asMetaData metadata->map))

(def enum-parse-flag (enum/create-enum-converters ParseFlag))
(def enum-media-parsed-status
  (enum/create-enum-converters MediaParsedStatus))

(def ^:private native-runtime
  {:new-factory! (fn [] (MediaPlayerFactory.))
   :release-factory! (fn [^MediaPlayerFactory factory]
                       (.release factory))
   :parse-flags (fn [parse-flags]
                  (into-array ParseFlag
                              (map (:kw->enum enum-parse-flag) parse-flags)))
   :new-media! (fn [^MediaPlayerFactory factory ^String mrl]
                 (.newMedia (.media factory) mrl nil))
   :release-media! (fn [^Media media]
                     (.release media))
   :new-listener listener/parse-event-listener
   :add-listener! (fn [^Media media ^MediaEventListener event-listener]
                    (.addMediaEventListener (.events media) event-listener))
   :remove-listener! (fn [^Media media ^MediaEventListener event-listener]
                       (.removeMediaEventListener (.events media)
                                                  event-listener))
   :start-parse! (fn [^Media media timeout-ms parse-flags]
                   (.parse (.parsing media) (int timeout-ms) parse-flags))
   :stop-parse! (fn [^Media media]
                  (.stop (.parsing media)))
   :new-media-list! (fn [^Media media]
                      (.newMediaList (.subitems media)))
   :media-list-size (fn [^MediaList media-list]
                      (.count (.media media-list)))
   :new-list-media! (fn [^MediaList media-list index]
                      (.newMedia (.media media-list) (int index)))
   :release-media-list! (fn [^MediaList media-list]
                          (.release media-list))
   :media-info media->info})

(def ^:private parser-thread-factory
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. ^Runnable runnable "vinyl-metadata-parser")
        (.setDaemon true)))))

(defonce ^:private ^ExecutorService parser-executor
  (Executors/newSingleThreadExecutor parser-thread-factory))

(defn- delivered-promise [value]
  (doto (promise) (deliver value)))

(defn- enqueue! [work]
  (let [result (promise)]
    (try
      (.execute parser-executor
                ^Runnable
                (fn []
                  (deliver result
                           (try
                             (work)
                             (catch Throwable t
                               t)))))
      (catch Throwable t
        (deliver result t)))
    result))

(defn- combine-errors [errors]
  (when-let [^Throwable error (first (remove nil? errors))]
    (doseq [^Throwable suppressed (rest (remove nil? errors))]
      (when-not (identical? error suppressed)
        (.addSuppressed error suppressed)))
    error))

(defn- attempt-error [f]
  (try
    (f)
    nil
    (catch Throwable t
      t)))

(defn- release-media-errors [runtime medias]
  (into []
        (keep (fn [media]
                (attempt-error #((:release-media! runtime) media))))
        medias))

(defn- throw-errors! [errors]
  (when-let [error (combine-errors errors)]
    (throw error)))

(defn- failed-result [media error]
  {:media media
   :parse-status MediaParsedStatus/FAILED
   :error error})

(defn- start-failed-result [media]
  (failed-result
   media
   (ex-info "The native media parse request did not start."
            {:reason :start-failed})))

(defn- timeout-result [media timeout-ms]
  {:media media
   :parse-status MediaParsedStatus/TIMEOUT
   :error (ex-info "Media parsing timed out."
                   {:reason :timeout
                    :timeout-ms timeout-ms})})

(defn- interrupted-result [media]
  (failed-result
   media
   (ex-info "Media parsing was interrupted." {:reason :cancelled})))

(defn- stop-with-error [runtime media]
  (attempt-error #((:stop-parse! runtime) media)))

(defn- parse-while-attached! [runtime media timeout-ms parse-flags completion]
  (let [start-outcome (try
                        [:ok ((:start-parse! runtime)
                              media
                              timeout-ms
                              parse-flags)]
                        (catch Throwable t
                          [:error t]))]
    (if (= :error (first start-outcome))
      (failed-result media
                     (combine-errors [(second start-outcome)
                                      (stop-with-error runtime media)]))
      (if-not (second start-outcome)
        (start-failed-result media)
        (try
          {:media media
           :parse-status (.get ^CompletableFuture completion
                               (long timeout-ms)
                               TimeUnit/MILLISECONDS)}
          (catch TimeoutException _
            (let [result (timeout-result media timeout-ms)]
              (if-let [stop-error (stop-with-error runtime media)]
                (assoc result :error
                       (combine-errors [(:error result) stop-error]))
                result)))
          (catch InterruptedException _
            (.interrupt (Thread/currentThread))
            (let [result (interrupted-result media)]
              (if-let [stop-error (stop-with-error runtime media)]
                (assoc result :error
                       (combine-errors [(:error result) stop-error]))
                result)))
          (catch ExecutionException e
            (failed-result media (.getCause e))))))))

(defn- parse-media! [runtime media timeout-ms parse-flags]
  (let [^CompletableFuture completion (CompletableFuture.)
        event-listener ((:new-listener runtime)
                        (fn [_media status]
                          (.complete completion status)))
        add-error (attempt-error
                   #((:add-listener! runtime) media event-listener))]
    (if add-error
      (let [remove-error
            (attempt-error
             #((:remove-listener! runtime) media event-listener))]
        (if remove-error
          (throw (combine-errors [add-error remove-error]))
          (failed-result media add-error)))
      (let [parse-outcome (try
                            (parse-while-attached! runtime
                                                   media
                                                   timeout-ms
                                                   parse-flags
                                                   completion)
                            (catch Throwable t
                              (failed-result media t)))
            remove-error (attempt-error
                          #((:remove-listener! runtime)
                            media
                            event-listener))]
        (if remove-error
          (throw (combine-errors [(:error parse-outcome) remove-error]))
          parse-outcome)))))

(defn- create-children! [runtime media-list]
  (let [media-count ((:media-list-size runtime) media-list)]
    (loop [index 0
           children []]
      (if (= index media-count)
        {:children children}
        (let [child-outcome
              (try
                [:ok ((:new-list-media! runtime) media-list index)]
                (catch Throwable t
                  [:error t]))]
          (if (= :error (first child-outcome))
            {:children []
             :error (combine-errors
                     (into [(second child-outcome)]
                           (release-media-errors runtime children)))}
            (recur (inc index) (conj children (second child-outcome)))))))))

(defn- owned-subitems! [runtime media]
  (let [list-outcome (try
                       [:ok ((:new-media-list! runtime) media)]
                       (catch Throwable t
                         [:error t]))]
    (if (= :error (first list-outcome))
      {:children []
       :error (second list-outcome)}
      (if-let [media-list (second list-outcome)]
        (let [children-outcome
              (try
                (create-children! runtime media-list)
                (catch Throwable t
                  {:children [] :error t}))
              {:keys [children error]} children-outcome
              list-error (attempt-error
                          #((:release-media-list! runtime) media-list))]
          (if-let [ownership-error (combine-errors [error list-error])]
            {:children []
             :error (combine-errors
                     (into [ownership-error]
                           (release-media-errors runtime children)))}
            {:children children}))
        {:children []}))))

(defn process-subitems
  "Creates and realizes the child media wrappers owned by `media`.

  The temporary `MediaList` is always released. The caller owns every returned
  child `Media`."
  [^Media media]
  (let [{:keys [children error]} (owned-subitems! native-runtime media)]
    (if error
      (throw error)
      (when (seq children)
        children))))

(defn- result-with-error [result error]
  (if error
    (failed-result (:media result)
                   (combine-errors [(:error result) error]))
    result))

(defn- child-work [children depth]
  (mapv (fn [media]
          {:media media
           :depth (inc depth)})
        children))

(defn- parse-work-item!
  [runtime pending results cleanup-errors opts]
  (let [{:keys [depth media]} (first pending)
        parse-result (parse-media! runtime
                                   media
                                   (:timeout-ms opts)
                                   (:parse-flags opts))
        {:keys [children error]} (owned-subitems! runtime media)]
    (try
      (let [parse-result (result-with-error parse-result error)
            has-children? (seq children)
            at-depth-limit? (and (:depth-limit opts)
                                 (>= depth (:depth-limit opts)))
            keep-media? (or (not (:exclude-containers? opts))
                            (not has-children?)
                            at-depth-limit?)
            released-children (if at-depth-limit? children [])
            next-children (if at-depth-limit? [] children)
            releases (concat
                      (when-not keep-media? [media])
                      released-children)
            remaining (subvec pending 1)
            next-pending (into (child-work next-children depth) remaining)
            next-results (cond-> results
                           keep-media? (conj parse-result))
            next-errors (into cleanup-errors
                              (release-media-errors runtime releases))]
        {:cleanup-errors next-errors
         :pending next-pending
         :results next-results})
      (catch Throwable t
        (throw-errors!
         (into [t] (release-media-errors runtime children)))))))

(defn- parse-worklist! [runtime root-medias opts]
  (loop [pending (mapv (fn [media]
                         {:media media
                          :depth 0})
                       root-medias)
         results []
         cleanup-errors []]
    (if (seq pending)
      (let [step-outcome
            (try
              [:ok (parse-work-item! runtime
                                     pending
                                     results
                                     cleanup-errors
                                     opts)]
              (catch Throwable t
                [:error t]))]
        (if (= :error (first step-outcome))
          (throw-errors!
           (concat
            [(second step-outcome)]
            cleanup-errors
            (release-media-errors runtime (map :media pending))
            (release-media-errors runtime (map :media results))))
          (let [{:keys [cleanup-errors pending results]}
                (second step-outcome)]
            (recur pending results cleanup-errors))))
      (if (seq cleanup-errors)
        (throw-errors!
         (into cleanup-errors
               (release-media-errors runtime (map :media results))))
        results))))

(defn- create-root-medias! [runtime factory paths]
  (loop [remaining (seq paths)
         medias []]
    (if-let [path (first remaining)]
      (let [media-outcome
            (try
              [:ok ((:new-media! runtime) factory (protocols/mrl path))]
              (catch Throwable t
                [:error t]))]
        (if (= :error (first media-outcome))
          (throw-errors!
           (into [(second media-outcome)]
                 (release-media-errors runtime medias)))
          (recur (next remaining) (conj medias (second media-outcome)))))
      medias)))

(defn- metadata-results! [runtime media-results]
  (let [status-converter (or (:enum->kw enum-media-parsed-status)
                             (fn [^MediaParsedStatus status]
                               (keyword "parse-status"
                                        (.toLowerCase (.name status)))))
        metadata-outcome
        (try
          [:ok
           (mapv (fn [{:keys [error media parse-status]}]
                   (cond-> ((:media-info runtime) media)
                     parse-status (assoc :parse-status
                                         (status-converter parse-status))
                     error (assoc :error error)))
                 media-results)]
          (catch Throwable t
            [:error t]))
        release-errors (release-media-errors runtime (map :media media-results))
        error (combine-errors
               (into (when (= :error (first metadata-outcome))
                       [(second metadata-outcome)])
                     release-errors))]
    (if error
      (throw error)
      (second metadata-outcome))))

(defn- transform-results! [runtime media-results transform consumes-media?]
  (let [outcome (try
                  [:ok (transform runtime media-results)]
                  (catch Throwable t
                    [:error t]))
        release-errors (if (and (= :error (first outcome))
                                (not consumes-media?))
                         (release-media-errors runtime
                                               (map :media media-results))
                         [])
        error (combine-errors
               (concat
                (when (= :error (first outcome))
                  [(second outcome)])
                release-errors))]
    (if error
      (throw error)
      (second outcome))))

(defn- execute-request! [paths opts transform consumes-media?]
  (let [runtime (or (::runtime opts) native-runtime)
        own-factory? (nil? (:media-player-factory opts))
        factory (or (:media-player-factory opts)
                    ((:new-factory! runtime)))
        request-outcome
        (try
          (let [parse-flags ((:parse-flags runtime) (:parse-flags opts))
                parse-opts (assoc opts :parse-flags parse-flags)
                root-medias (create-root-medias! runtime factory paths)
                media-results (parse-worklist! runtime root-medias parse-opts)]
            [:ok (transform-results! runtime
                                     media-results
                                     transform
                                     consumes-media?)])
          (catch Throwable t
            [:error t]))
        factory-error (when own-factory?
                        (attempt-error
                         #((:release-factory! runtime) factory)))
        transfer-cleanup-errors
        (if (and factory-error
                 (= :ok (first request-outcome))
                 (not consumes-media?))
          (release-media-errors runtime
                                (map :media (second request-outcome)))
          [])
        error (combine-errors
               (concat
                (when (= :error (first request-outcome))
                  [(second request-outcome)])
                [factory-error]
                transfer-cleanup-errors))]
    (if error
      (throw error)
      (second request-outcome))))

(def default-parse-options
  {:exclude-containers? true
   :parse-flags [:parse-flag/fetch-local :parse-flag/parse-local]
   :timeout-ms 5000})

(defn parse-medias-recursive
  "Parses paths recursively and returns owned vlcj media wrappers.

  The returned promise settles to a vector of maps containing `:media` and
  `:parse-status`, or to a `Throwable`. The caller owns every returned `Media`.

  Options include `:exclude-containers?`, `:depth-limit`, `:timeout-ms`,
  `:parse-flags`, and `:media-player-factory`."
  ([paths]
   (parse-medias-recursive paths {}))
  ([paths opts]
   (if (seq paths)
     (let [opts (merge default-parse-options opts)]
       (enqueue!
        #(execute-request! paths opts (fn [_runtime results] results) false)))
     (delivered-promise []))))

(defn parse-meta
  "Parses paths recursively and returns eagerly realized Clojure metadata.

  Options are the same as [[parse-medias-recursive]]. The returned promise
  settles to a metadata vector or a `Throwable`. All native media wrappers are
  released before the promise settles."
  ([paths]
   (parse-meta paths {}))
  ([paths opts]
   (if (seq paths)
     (let [opts (merge default-parse-options opts)]
       (enqueue!
        #(execute-request! paths opts metadata-results! true)))
     (delivered-promise []))))
