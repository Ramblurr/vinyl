(ns ol.vinyl.queue
  "Audio playback queue with dual-queue architecture and unified indexing.

  ## User Documentation

  ### Overview
  
  This queue manages tracks in an audio player with features like add-next,
  shuffle, repeat modes, and full playback history. All operations are pure
  functions that return new queue states.

  ### Key Concepts

  **Unified Indexing**: Access any track using a single index system:
  - Index 0 is always the current track
  - Negative indices access history (-1 was just played)
  - Positive indices access upcoming tracks

  **Add-Next**: Queue tracks to play right after the current one without
  disrupting the rest of your playlist. Multiple add-next calls queue tracks
  in the order they were added.

  **Repeat Modes**:
  - `:none` - Stop when queue ends
  - `:track` - Keep playing current track
  - `:list` - Loop back to start after last track

  **Shuffle**: Randomizes all upcoming tracks. Add-next still works after
  shuffling to insert priority tracks.
  
  **Resource Cleanup**: The queue supports a cleanup function for releasing
  native resources (like JNA references) when tracks are removed.

  ### Common Usage Patterns

  ```clojure
  ;; Create queue and add tracks
  (-> (create-queue)
      (append tracks))
  
  ;; Create queue with cleanup function for JNA resources
  (-> (create-queue (fn [track] (.release track)))
      (append tracks))
  
  ;; Add track to play next
  (add-next queue [track])
  
  ;; Skip current track
  (advance queue)
  
  ;; Go back
  (prev-track queue)
  
  ;; Enable shuffle
  (set-shuffle queue true)
  ```

  ## Developer Documentation

  ### Internal Architecture

  The queue maintains four separate collections:
  - `:history` - Vector of previously played tracks (newest at end)
  - `:current` - Currently playing track or nil
  - `:priority` - Vector for add-next tracks
  - `:normal` - Vector for regular queued tracks
  - `:cleanup-fn` - Function called when tracks are removed (for resource cleanup)

  ### Index Mapping

  The unified index system maps to internal collections as follows:
  - Negative indices: Index into history (reversed)
  - Index 0: Current track
  - Positive indices: First exhaust priority queue, then normal queue

  Example: history=[A,B], current=C, priority=[D,E], normal=[F,G]
  - Index -2→A, -1→B, 0→C, 1→D, 2→E, 3→F, 4→G

  ### State Transitions

  Key invariants maintained across operations:
  - If current is nil, advance pulls from priority first, then normal
  - Removing current auto-advances to next available track
  - Priority queue preserves insertion order for add-next semantics
  - Shuffle merges priority→normal and clears priority
  - Cleanup function is called whenever tracks are removed from the queue

  ### Implementation Notes

  - All operations use structural sharing for efficiency
  - Index calculations are O(1) for determining target collection
  - Replace operations maintain position within same collection
  - Move operations handle index adjustment after removal
  - Resource cleanup happens immediately when tracks are removed")

(defn create-queue
  "Creates an empty queue with default configuration.
  
  Optionally accepts a cleanup-fn that will be called with each track
  when it's removed from the queue. This is useful for releasing
  resources like JNA references."
  ([] (create-queue identity))
  ([cleanup-fn]
   {:history []
    :current nil
    :priority []
    :normal []
    :shuffle? false
    :repeat :none
    :cleanup-fn cleanup-fn}))

(defn get-current
  "Returns the currently playing track or nil if empty."
  [queue]
  (:current queue))

(defn- cleanup-tracks
  "Calls the cleanup function on each track. Uses doall to realize
  the lazy sequence and ensure cleanup happens immediately."
  [queue tracks]
  (when-let [cleanup-fn (:cleanup-fn queue)]
    (when (seq tracks)
      (doall (map cleanup-fn tracks)))))

(defn- unified-index->collection
  "Determines which collection and index to use for a unified index.
  Returns [collection local-index] or nil if out of bounds."
  [{:keys [history current priority normal]} index]
  (let [history-size (count history)
        priority-size (count priority)
        normal-size (count normal)]
    (cond
      (nil? current) nil
      (= index 0) [:current 0]
      (< index 0)
      (let [hist-idx (+ history-size index)]
        (when (>= hist-idx 0)
          [:history hist-idx]))
      (> index 0)
      (cond
        (<= index priority-size)
        [:priority (dec index)]
        (<= index (+ priority-size normal-size))
        [:normal (- index priority-size 1)]
        :else nil))))

(defn get-at
  "Gets track at unified index position.
  Index 0 is current, negative for history, positive for future."
  [queue index]
  (if (nil? (:current queue))
    ;; When no current, handle specially
    (cond
      (= index 0) nil ; Index 0 is always current
      (< index 0) ; History works the same
      (get-in queue [:history (+ (count (:history queue)) index)])
      (> index 0) ; Positive indices offset by 1
      (let [adj-idx (dec index)
            priority-size (count (:priority queue))]
        (if (< adj-idx priority-size)
          (get-in queue [:priority adj-idx])
          (get-in queue [:normal (- adj-idx priority-size)]))))
    ;; When current exists, use unified-index->collection
    (when-let [[coll local-idx] (unified-index->collection queue index)]
      (case coll
        :current (:current queue)
        :history (get-in queue [:history local-idx])
        :priority (get-in queue [:priority local-idx])
        :normal (get-in queue [:normal local-idx])
        nil))))

(defn get-slice
  "Returns tracks from start to end index (exclusive).
  Handles unified indexing across history, current, priority, and normal."
  [queue start end]
  (let [indices (range start end)]
    (vec (keep #(get-at queue %) indices))))

(defn list-all
  "Returns tracks in the queue"
  [{:keys [history current priority normal]}]
  {:history history
   :current current
   :upcoming (vec (concat priority normal))})

(defn append
  "Adds tracks to the end of the normal queue."
  [queue tracks]
  (update queue :normal #(vec (concat % tracks))))

(defn add-next
  "Adds tracks to the end of the priority queue."
  [queue tracks]
  (update queue :priority #(vec (concat % tracks))))

(defn- insert-into-unified
  "Helper to insert tracks at a specific unified index."
  [{:keys [history current priority normal] :as queue} index tracks]
  (cond
    (nil? current)
    ;; When no current, index 0 is nil, positive indices start from 1
    (cond
      (empty? tracks) queue

      (< index 0)
      ;; Insert into history
      (let [hist-idx (+ (count history) index)]
        (if (>= hist-idx 0)
          (let [[before after] (split-at hist-idx history)]
            (assoc queue :history (vec (concat before tracks after))))
          queue))

      (= index 0)
      ;; Insert at index 0 - first track becomes current
      (assoc queue :current (first tracks)
             :normal (vec (concat (rest tracks) priority normal)))

      (> index 0)
      ;; Positive indices map to priority/normal with offset of 1
      (let [priority-size (count priority)
            adj-index (dec index)] ; Adjust for index 0 being current
        (cond
          (< adj-index priority-size)
          ;; Insert within priority
          (let [[before after] (split-at adj-index priority)]
            (assoc queue :priority (vec (concat before tracks after))))

          :else
          ;; Insert within or after normal
          (let [normal-idx (- adj-index priority-size)
                [before after] (split-at normal-idx normal)]
            (assoc queue :normal (vec (concat before tracks after)))))))

    ;; When current exists, use original logic
    (= index 0)
    (assoc queue :current (first tracks)
           :priority (vec (concat (rest tracks) [current] priority)))

    (< index 0)
    (let [hist-idx (+ (count history) index)]
      (if (>= hist-idx 0)
        (let [[before after] (split-at hist-idx history)]
          (assoc queue :history (vec (concat before tracks after))))
        queue))

    (> index 0)
    (let [priority-size (count priority)
          total-future (+ priority-size (count normal))]
      (cond
        ;; Insert within or at the end of priority queue
        (<= index (inc priority-size))
        (let [[before after] (split-at (dec index) priority)]
          (assoc queue :priority (vec (concat before tracks after))))

        ;; Insert within normal queue
        (<= index total-future)
        (let [normal-idx (- index priority-size 1)
              [before after] (split-at normal-idx normal)]
          (assoc queue :normal (vec (concat before tracks after))))

        ;; Append to the end
        :else
        (assoc queue :normal (vec (concat normal tracks)))))))

(defn insert-at
  "Inserts tracks at unified index position."
  [queue index tracks]
  (insert-into-unified queue index tracks))

(defn advance
  "Move forward respecting repeat mode."
  [{:keys [current priority normal repeat] :as queue}]
  (cond
    (nil? current)
    (cond
      (seq priority)
      (assoc queue :current (first priority)
             :priority (subvec priority 1))
      (seq normal)
      (assoc queue :current (first normal)
             :normal (subvec normal 1))
      :else queue)

    (= repeat :track) queue

    (seq priority)
    (-> queue
        (update :history conj current)
        (assoc :current (first priority))
        (update :priority subvec 1))

    (seq normal)
    (-> queue
        (update :history conj current)
        (assoc :current (first normal))
        (update :normal subvec 1))

    (= repeat :list)
    (let [all-data (list-all queue)
          all-tracks (concat (reverse (:history all-data))
                             (when (:current all-data) [(:current all-data)])
                             (:upcoming all-data))]
      (if (> (count all-tracks) 1)
        (-> queue
            (assoc :history []
                   :current (first all-tracks)
                   :priority []
                   :normal (vec (rest all-tracks))))
        queue))

    :else queue))

(defn next-track
  "Move to next track (ignoring repeat)."
  [queue]
  (let [original-repeat (:repeat queue)]
    (-> queue
        (assoc :repeat :none)
        advance
        (assoc :repeat original-repeat))))

(defn prev-track
  "Move to previous track."
  [{:keys [history current] :as queue}]
  (if (and current (seq history))
    (-> queue
        (update :priority #(vec (cons current %)))
        (assoc :current (last history))
        (update :history #(vec (butlast %))))
    queue))

(defn play-from
  "Jump to specific index position."
  [queue index]
  (if (get-at queue index)
    (cond
      (= index 0) queue

      (< index 0)
      (let [moves (- index)]
        (nth (iterate prev-track queue) moves))

      (> index 0)
      (let [moves index]
        (nth (iterate advance queue) moves)))
    queue))

(defn can-advance?
  "Returns true if advance operation will change the current track."
  [{:keys [current repeat priority normal]}]
  (boolean
   (or (and current
            (or (= repeat :track)
                (= repeat :list)
                (seq priority)
                (seq normal)))
       (and (nil? current)
            (or (seq priority)
                (seq normal))))))

(defn can-rewind?
  "Returns true if prev operation will change the current track."
  [{:keys [history]}]
  (boolean (seq history)))

(defn queue-empty?
  "Returns true if queue has no current track."
  [queue]
  (nil? (:current queue)))

(defn total-size
  "Returns total number of tracks in queue."
  [{:keys [history current priority normal]}]
  (+ (count history)
     (if current 1 0)
     (count priority)
     (count normal)))

(defn future-size
  "Returns number of upcoming tracks."
  [{:keys [priority normal]}]
  (+ (count priority) (count normal)))

(defn history-size
  "Returns number of previously played tracks."
  [queue]
  (count (:history queue)))

(defn current-index
  "Always returns 0 if current exists, nil if empty."
  [queue]
  (when (:current queue) 0))

(defn get-next-index
  "Returns 1 if has next, respecting repeat mode."
  [queue]
  (when (can-advance? queue) 1))

(defn get-prev-index
  "Returns -1 if has previous."
  [queue]
  (when (can-rewind? queue) -1))

(defn set-shuffle
  "Sets shuffle mode. When enabling, merges priority into normal and shuffles."
  [{:keys [priority normal] :as queue} enabled?]
  (if enabled?
    (let [merged (vec (concat priority normal))
          shuffled (shuffle merged)]
      (assoc queue
             :shuffle? true
             :priority []
             :normal shuffled))
    (assoc queue :shuffle? false)))

(defn set-repeat
  "Sets repeat mode to :none, :track, or :list."
  [queue mode]
  (assoc queue :repeat mode))

(defn shuffle?
  "Returns true if shuffle mode is enabled."
  [queue]
  (:shuffle? queue))

(defn repeat-mode
  "Returns current repeat mode."
  [queue]
  (:repeat queue))

(defn remove-at
  "Removes tracks at specified indices."
  [queue indices]
  (let [sorted-indices (sort > indices)
        ;; Collect tracks to be removed for cleanup
        tracks-to-remove (keep #(get-at queue %) indices)]
    ;; Cleanup removed tracks
    (cleanup-tracks queue tracks-to-remove)
    ;; Now remove them from the queue
    (reduce (fn [q idx]
              (cond
                ;; When current is nil and index > 0, need special handling
                (and (nil? (:current q)) (= idx 0))
                ;; Can't remove nil current
                q

                (and (nil? (:current q)) (> idx 0))
                ;; Adjust index down by 1 since index 0 is nil
                (let [adj-idx (dec idx)
                      priority-size (count (:priority q))]
                  (if (< adj-idx priority-size)
                    (update q :priority #(vec (concat (take adj-idx %)
                                                      (drop (inc adj-idx) %))))
                    (let [normal-idx (- adj-idx priority-size)]
                      (update q :normal #(vec (concat (take normal-idx %)
                                                      (drop (inc normal-idx) %)))))))

                (= idx 0)
                (if (seq (:priority q))
                  (-> q
                      (assoc :current (first (:priority q)))
                      (update :priority subvec 1))
                  (if (seq (:normal q))
                    (-> q
                        (assoc :current (first (:normal q)))
                        (update :normal subvec 1))
                    (assoc q :current nil)))

                (< idx 0)
                (let [hist-idx (+ (count (:history q)) idx)]
                  (if (>= hist-idx 0)
                    (update q :history #(vec (concat (take hist-idx %)
                                                     (drop (inc hist-idx) %))))
                    q))

                :else
                (let [priority-size (count (:priority q))]
                  (if (<= idx priority-size)
                    (update q :priority #(vec (concat (take (dec idx) %)
                                                      (drop idx %))))
                    (let [normal-idx (- idx priority-size 1)]
                      (update q :normal #(vec (concat (take normal-idx %)
                                                      (drop (inc normal-idx) %)))))))))
            queue
            sorted-indices)))

(defn replace-at
  "Replaces item at index with multiple tracks."
  [queue index tracks]
  (if-let [[coll local-idx] (unified-index->collection queue index)]
    (let [;; Get the track being replaced for cleanup
          replaced-track (case coll
                           :current (:current queue)
                           :history (get-in queue [:history local-idx])
                           :priority (get-in queue [:priority local-idx])
                           :normal (get-in queue [:normal local-idx]))]
      ;; Cleanup the replaced track
      (when replaced-track
        (cleanup-tracks queue [replaced-track]))
      ;; Now replace it
      (case coll
        :current (assoc queue :current (first tracks)
                        :priority (vec (concat (rest tracks) (:priority queue))))
        :history (update queue :history
                         #(vec (concat (take local-idx %)
                                       tracks
                                       (drop (inc local-idx) %))))
        :priority (update queue :priority
                          #(vec (concat (take local-idx %)
                                        tracks
                                        (drop (inc local-idx) %))))
        :normal (update queue :normal
                        #(vec (concat (take local-idx %)
                                      tracks
                                      (drop (inc local-idx) %))))))
    queue))

(defn move
  "Moves track from one position to another."
  [queue from-index to-index]
  (if (= from-index to-index)
    queue
    (if-let [track (get-at queue from-index)]
      (-> queue
          (remove-at [from-index])
          (insert-at to-index [track]))
      queue)))

(defn clear-upcoming
  "Clears priority and normal queues."
  [{:keys [priority normal] :as queue}]
  ;; Cleanup all upcoming tracks
  (cleanup-tracks queue (concat priority normal))
  (assoc queue :priority [] :normal []))

(defn clear-all
  "Clears everything including history and current."
  [{:keys [history current priority normal cleanup-fn] :as queue}]
  ;; Cleanup all tracks
  (let [all-tracks (concat history
                           (when current [current])
                           priority
                           normal)]
    (cleanup-tracks queue all-tracks))
  ;; Return a new queue with the same cleanup function
  (create-queue cleanup-fn))
