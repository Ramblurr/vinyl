;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.interop.parsing
  (:require
   [ol.vinyl.protocols :as protocols]
   [clojure.core.async :as async :refer [<! >! chan close! go]]
   [ol.vinyl.interop.enum :as enum]
   [ol.vinyl.interop.listener :as listener])
  (:import
   [uk.co.caprica.vlcj.factory MediaPlayerFactory]
   [uk.co.caprica.vlcj.media
    AudioTrackInfo
    Media
    MediaParsedStatus
    MediaType
    MetaData
    ParseFlag]))

(defn make-medias!
  "Create a new Media object for every item in paths.  Returns a vector of Medias."
  [^MediaPlayerFactory factory paths]
  (->> paths
       (map (fn [path]
              (-> factory (.media) (.newMedia path nil))))))

(defn release-medias!
  "Release all the Medias in medias."
  [medias]
  (doseq [media medias]
    (when media
      (doto media (.release)))))

#_(-> "dir:///home/ramblurr/src/sparklestories/media/audiobooks/A.A. Milne/Disc 1 - Introducing Pooh and Piglet/"
      (.replaceAll "^dir://" "")
      (Paths/get (into-array String []))
      .toAbsolutePath
      .toFile
      .isDirectory)
#_(-> "dir:///home/ramblurr/src/sparklestories/media/audiobooks/A.A. Milne/Disc 1 - Introducing Pooh and Piglet/"
      (.replaceAll "^dir://" "")
      (Paths/get (into-array String []))
      .toAbsolutePath
      .toUri
      str)
#_(->
   (Paths/get "dir:///home/ramblurr/src/sparklestories/media/audiobooks/A.A. Milne/Disc 1 - Introducing Pooh and Piglet/" (into-array String []))
   .toFile
   .exists)

(defn metadata->map [^MetaData metadata]
  (->> (.values metadata)
       (reduce (fn [acc [k v]]
                 (assoc acc (enum/enum->keyword k) v)) {})))

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
   {:mrl (-> media (.info) (.mrl))
    :meta (-> media (.meta) (.asMetaData) metadata->map)
    :duration (-> media (.info) (.duration))
    :audio-tracks (map audio-track->info (-> media (.info) (.audioTracks)))
    :media-type (-> media (.info) (.type) enum/enum->keyword)}))

(defn meta-for [^Media media]
  (some-> media (.meta) (.asMetaData) metadata->map))

(def enum-parse-flag  (enum/create-enum-converters ParseFlag))
(def enum-media-parsed-status (enum/create-enum-converters MediaParsedStatus))

(:kw->enum-map enum-parse-flag)
;; => #:parse-flag{:parse-local #object[uk.co.caprica.vlcj.media.ParseFlag 0x2423d9d9 "PARSE_LOCAL"], :parse-network #object[uk.co.caprica.vlcj.media.ParseFlag 0x2ef9df7f "PARSE_NETWORK"], :fetch-local #object[uk.co.caprica.vlcj.media.ParseFlag 0x7352e176 "FETCH_LOCAL"], :fetch-network #object[uk.co.caprica.vlcj.media.ParseFlag 0x208b4087 "FETCH_NETWORK"], :do-interact #object[uk.co.caprica.vlcj.media.ParseFlag 0x1adca85e "DO_INTERACT"]}

(defn process-subitems
  [^Media media]
  (when-let [subitems (.subitems media)]
    (when-let [media-list (.newMediaList subitems)]
      (let [media-api (.media media-list)
            count (.count media-api)]
        (when (> count 0)
          (vec (for [i (range count)]
                 (.newMedia media-api i))))))))

(defn parse-media-to-channel
  "Parse a media and send result to channel. Assumes channel will be closed by caller."
  [^Media media timeout-ms parse-flags chan]
  (try
    (-> media
        (.events)
        (.addMediaEventListener
         (listener/parse-event-listener
          (fn [^Media m ^MediaParsedStatus status]
            (go (>! chan {:media m :parse-status status}))))))
    (.parse (.parsing media) (or timeout-ms -1) parse-flags)
    (catch Throwable t
      (go (>! chan {:media media :parse-status MediaParsedStatus/FAILED :error t})))))

(defn parse-media-recursive
  "Recursively parse a media item and all its subitems.
   Returns a channel that will contain parsed media items with their status.
   current-depth tracks how deep we are in the recursion.
   recurse-limit sets the maximum depth (nil for unlimited)."
  [^Media media {:keys [exclude-containers? current-depth recurse-limit timeout-ms parse-flags] :as opts}]
  (let [<out-chan (chan)]
    (go
      (let [<parse (chan 1)
            ;; track if we need to release this media
            should-release? (atom false)]
        (try
          (parse-media-to-channel media timeout-ms parse-flags <parse)
          ;; wait for parse result
          (when-let [{:keys [media] :as result} (<! <parse)]
            (close! <parse)
            ;; process subitems
            (let [subitems (process-subitems media)
                  has-subitems? (seq subitems)
                  at-depth-limit? (and recurse-limit (>= current-depth recurse-limit))]

              ;; determine if we're keeping this media
              (if (or (not exclude-containers?)
                      (not has-subitems?)
                      at-depth-limit?)
                ;; We're returning this media with its status
                (>! <out-chan result)
                ;; we're NOT returning this media, we must release it
                (reset! should-release? true))

              ;; Process subitems recursively if not at depth limit
              (when (and has-subitems? (not at-depth-limit?))
                (let [sub-chans (mapv #(parse-media-recursive % (update opts :current-depth inc))
                                      subitems)]
                  ;; collect all results from sub-parsing
                  (doseq [<sub-chan sub-chans]
                    (loop []
                      (when-let [sub-result (<! <sub-chan)]
                        (>! <out-chan sub-result)
                        (recur))))))))

          ;; Release this media if we didn't send it downstream
          (when @should-release?
            (.release media))

          (catch Throwable t
            (tap> [:error "Error parsing media" (.getMessage t) t])
            (.release media)
            (throw t))
          (finally
            (close! <out-chan)))))
    <out-chan))

(defn parse-medias-recursive
  "Parse multiple media paths recursively, returning a promise that will resolve to all discovered media items with their parse status.
   Options:
   - :exclude-containers? - if true, directories/playlists won't be in results, only their contents (default: true)
   - :depth-limit - maximum depth to recurse (nil or omitted for unlimited)
   - :timeout-ms - timeout in milliseconds for each parse operation (default: 5000)
   - :parse-flags - flags to use for parsing (default: [:parse-flag/fetch-local :parse-flag/parse-local])
   - :media-player-factory - MediaPlayerFactory instance to use for parsing (default: a new instance)

   Returns a promise that resolves to a vector of {:media Media :parse-status MediaParsedStatus}"
  ([paths]
   (parse-medias-recursive paths {}))
  ([paths {:keys [depth-limit parse-flags media-player-factory exclude-containers? timeout-ms]
           :or {exclude-containers? true
                parse-flags [:parse-flag/fetch-local :parse-flag/parse-local]}
           :as _opts}]
   (if (seq paths)
     (let [own-factory? (nil? media-player-factory)
           factory (or media-player-factory (MediaPlayerFactory.))
           mrls (map protocols/mrl paths)
           medias (make-medias! factory mrls)
           parse-flags (into-array ParseFlag (map (:kw->enum enum-parse-flag) parse-flags))
           result-chan (chan)
           result (promise)]
       (go
         (try
            ;; Parse all root media items (starting at depth 0)
           (let [parse-chans (mapv #(parse-media-recursive %
                                                           {:exclude-containers? exclude-containers?
                                                            :current-depth 0
                                                            :recurse-limit depth-limit
                                                            :timeout-ms timeout-ms
                                                            :parse-flags parse-flags}) medias)
                 results (atom [])]
              ;; collect all results
             (doseq [parse-chan parse-chans]
               (loop []
                 (when-let [media-result (<! parse-chan)]
                   (swap! results conj media-result)
                   (recur))))
              ;; signal completion
             (let [final-results @results]
               (>! result-chan final-results)
               (close! result-chan)))
           (catch Throwable t
              ;; On error, close with exception
             (tap> [:error "Error in parse-medias-recursive" t])
             (>! result-chan t)
             (close! result-chan))))

       (go
         (let [final-results (<! result-chan)]
           (when own-factory? (.release factory))
           (deliver result final-results)))
       result)
     (doto (promise) (deliver [])))))

(defn parse-meta
  "Parse media files recursively and extract their metadata as Clojure data structures.

   This function wraps parse-medias-recursive to provide a higher-level API that:
   - Automatically extracts metadata from parsed Media objects using media->info
   - Includes the parse status (:parse-status) in the metadata
   - Releases Media objects after extraction to prevent memory leaks
   - Returns plain Clojure data instead of Java Media objects

   Parameters:
   - paths: A sequence of file paths, directory paths, or URLs to parse
   - opts: Options map (see parse-medias-recursive for all options)

   Returns:
   A promise that will resolve to either:
   - A vector of metadata maps with :parse-status included on success
   - A Throwable on error

   The metadata maps contain fields from media->info plus:
   - :parse-status - one of :done, :failed, :skipped, :timeout

   Example:
   (let [metadata-promise (parse-meta [\"/music/album\"]
                                     {:timeout-ms 10000})]
     (println @metadata-promise))
   ;; => [{:title \"Song 1\" :duration 180000 :parse-status :done} ...]"
  ([paths]
   (parse-meta paths {}))
  ([paths opts]
   (let [result (promise)
         status-converter (or (:enum->kw enum-media-parsed-status)
                              (fn [status] (keyword "parse-status"
                                                    (.toLowerCase (.name status)))))]
     (future
       (try
         (let [media-results (deref (parse-medias-recursive paths opts))]
           (try
             (->> media-results
                  (map (fn [{:keys [media parse-status error]}]
                         (cond-> (media->info media)
                           parse-status (assoc :parse-status (status-converter parse-status))
                           error (assoc :error error))))
                  (deliver result))
             (finally
               (when (sequential? media-results)
                 (release-medias! media-results)))))
         (catch Throwable t
           (deliver result t))))
     result)))

(comment
  (vals (:enum->kw-map (enum/create-enum-converters ParseFlag)))
  (:kw->enum-map (enum/create-enum-converters ParseFlag))
  (enum/enum->keyword MediaType/FILE)
  (enum/keyword->enum MediaType :media-type/file)
  (protocols/coerce-to-mrl (protocols/coerce-to-mrl "/home/ramblurr/src/sparklestories/media/audiobooks/A.A. Milne/Disc 1 - Introducing Pooh and Piglet/"))
  (protocols/coerce-to-mrl "/home/ramblurr/src/sparklestories/media/audiobooks/A.A. Milne/Disc 1 - Introducing Pooh and Piglet/Story 1 - In which we are introduced.mp3")
  (def factory (MediaPlayerFactory.))
  (let [m1 "/home/ramblurr/src/sparklestories/media/audiobooks/A.A. Milne/Disc 1 - Introducing Pooh and Piglet/Story 1 - In which we are introduced.mp3"
        m2 "/home/ramblurr/src/sparklestories/media/audiobooks/A.A. Milne/Disc 1 - Introducing Pooh and Piglet/"
        m3 "/home/ramblurr/src/sparklestories/media/audiobooks/A.A. Milne/"
        m4 "/home/ramblurr/src/sparklestories/media/playlists/LibbyDish1.m3u"
        #_#_medias (parse-medias-sync factory [m1 m2 m3])]
    #_(parse [m3] {:media-player-factory factory})
    #_(->>
       (parse-medias-recursive [m3] {:media-player-factory factory})
       deref)
    @(parse-meta [m4] {:media-player-factory factory})
    #_(try
        (-> medias
            (nth 2) ;; a directory
            (.subitems)
            (.newMediaList) ;; gets a MediaList, a native list of Medias
            (.media)        ;; get the media api
            (.newMedia 0) ;; create a media object for the first item in the list
            (media->info) ;; convert to our friendly map
            )

        (finally
          (release-medias! medias))))
  ;; rcf

  ;;
  )