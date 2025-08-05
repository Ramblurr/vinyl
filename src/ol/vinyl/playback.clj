(ns ol.vinyl.playback
  (:require
   [ol.vinyl.interop.api :as api]
   [ol.vinyl.bus :as bus]
   [ol.vinyl.commands :as cmd]
   [ol.vinyl.interop.parsing :as parsing]
   [ol.vinyl.queue :as queue]))

(defn handle-player-event [{:ol.vinyl.impl/keys [state_] :as instance} event]
  (when-not (contains? #{:vlc/time-changed :vlc/position-changed} (:event event))
    (tap> event)))

(defn init! [{:ol.vinyl.impl/keys [state_] :as instance}]
  (let [sub-id (bus/subscribe-impl! instance (constantly true) (fn [e] (handle-player-event instance e)))]
    (swap! state_ update :playback (fn [_]
                                     {:queue (queue/create-queue)
                                      :sub-id sub-id}))))

(defn- queue [{:ol.vinyl.impl/keys [state_] :as _instance}]
  (get-in @state_ [:playback :queue]))

(defn- set-queue [{:ol.vinyl.impl/keys [state_] :as _instance} new-queue]
  (tap> [:set-queue new-queue])
  (swap! state_ assoc-in [:playback :queue] new-queue))

(defn release! [{:ol.vinyl.impl/keys [state_] :as instance}]
  (bus/unsubscribe-impl! instance (get-in @state_ [:playback :sub-id]))
  (set-queue instance (queue/clear-all (queue instance))))

(defn list-all [instance]
  (queue/list-all (queue instance)))

(defn get-current [instance]
  (queue/get-current (queue instance)))

(defn notify-player
  "Notify the player play or stop playing based on the current track"
  [instance new-queue]
  (if-let [current (queue/get-current new-queue)]
    (bus/dispatch-event! instance
                         {:event :vlcj.media-api/play
                          :mrl-or-media-ref (:mrl current)})
    (bus/dispatch-event! instance {:event :vlcj.controls-api/stop})))

(defn update-queue-and-player [instance new-queue]
  (set-queue instance new-queue)
  (notify-player instance new-queue))

(defn expand-paths [instance tracks]
  (let [result @(parsing/parse-meta tracks {:media-player-factory (get-in instance [:ol.vinyl.impl/player :vlc/media-player-factory])})]
    (if (isa? (type result) Exception)
      (throw result)
      (if (every? #(= :media-parsed-status/done (:parse-status %)) result)
        result
        (throw (ex-info "Failed to parse media tracks" {:parse-result result}))))))

(defmethod cmd/dispatch :playback/advance
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)]
    (tap> [:playback/advance :instance instance :event e :prev-queue prev-queue :can-advance? (queue/can-advance? prev-queue)])
    (when (queue/can-advance? prev-queue)
      (update-queue-and-player instance (queue/advance prev-queue)))))

(defmethod cmd/dispatch :playback/append
  [instance {:keys [paths] :as e}]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)
        tracks (expand-paths instance paths)]
    (tap> [:playback/append :instance instance :event e :tracks tracks :paths paths :prev-queue prev-queue])
    (when (seq tracks)
      (let [new-queue (queue/append prev-queue tracks)]
        (set-queue instance new-queue)
        (when (and (nil? (queue/get-current prev-queue))
                   (queue/get-current new-queue))
          (notify-player instance new-queue))))))

(defmethod cmd/dispatch :playback/clear-all
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)]
    (tap> [:playback/clear-all :instance instance :event e :prev-queue prev-queue])
    (update-queue-and-player instance (queue/clear-all prev-queue))
    (bus/dispatch-event! instance {:event :vlcj.media-api/reset})))

(defmethod cmd/dispatch :playback/next-track
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)]
    (tap> [:playback/next-track :instance instance :event e :prev-queue prev-queue])
    (when (queue/can-advance? prev-queue)
      (update-queue-and-player instance (queue/next-track prev-queue)))))

(defmethod cmd/dispatch :playback/previous-track
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)]
    (tap> [:playback/previous-track :instance instance :event e :prev-queue prev-queue])
    (when (queue/can-rewind? prev-queue)
      (update-queue-and-player instance (queue/prev-track prev-queue)))))

(defmethod cmd/dispatch :playback/set-shuffle
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)
        shuffle? (:shuffle? e)]
    (tap> [:playback/set-shuffle :instance instance :event e :shuffle? shuffle? :prev-queue prev-queue])
    (set-queue instance (queue/set-shuffle prev-queue shuffle?))))

(defmethod cmd/dispatch :playback/set-repeat
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)
        mode (:mode e)]
    (tap> [:playback/set-repeat :instance instance :event e :mode mode :prev-queue prev-queue])
    (set-queue instance (queue/set-repeat prev-queue mode))))

(defmethod cmd/dispatch :playback/clear-upcoming
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)]
    (tap> [:playback/clear-upcoming :instance instance :event e :prev-queue prev-queue])
    (set-queue instance (queue/clear-upcoming prev-queue))))

(defmethod cmd/dispatch :playback/move
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)
        from (:from e)
        to (:to e)]
    (tap> [:playback/move :instance instance :event e :from from :to to :prev-queue prev-queue])
    (set-queue instance (queue/move prev-queue from to))))

(defmethod cmd/dispatch :playback/replace-at
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)
        position (:position e)
        tracks (expand-paths instance (:paths e))]
    (tap> [:playback/replace-at :instance instance :event e :position position :tracks tracks :prev-queue prev-queue])
    (when (seq tracks)
      (let [new-queue (queue/replace-at prev-queue position tracks)]
        (if (= position 0)
          (update-queue-and-player instance new-queue)
          (set-queue instance new-queue))))))

(defmethod cmd/dispatch :playback/remove-at
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)
        position (:position e)]
    (tap> [:playback/remove-at :instance instance :event e :position position :prev-queue prev-queue])
    (let [new-queue (queue/remove-at prev-queue [position])]
      (if (= position 0)
        (update-queue-and-player instance new-queue)
        (set-queue instance new-queue)))))

(defmethod cmd/dispatch :playback/add-next
  [instance e]
  (cmd/ensure-valid! e)
  (let [prev-queue (queue instance)
        tracks (expand-paths instance (:paths e))]
    (tap> [:playback/add-next :instance instance :event e :tracks tracks :prev-queue prev-queue])
    (when (seq tracks)
      (let [new-queue (queue/add-next prev-queue tracks)]
        (set-queue instance new-queue)
        (when (and (nil? (queue/get-current prev-queue))
                   (queue/get-current new-queue))
          (notify-player instance new-queue))))))

(defmethod cmd/dispatch :playback/play
  [instance e]
  (cmd/ensure-valid! e)
  (let [queue (queue instance)
        current (queue/get-current queue)
        state (api/get-state (:ol.vinyl.impl/player instance))
        stopped? (= :state/stopped  state)
        paused? (= :state/paused  state)]
    (cond (and paused? current)
          (bus/dispatch-event! instance {:event :vlcj.controls-api/play})

          (and stopped? current)
          (notify-player instance queue)

          (and (nil? current) (queue/can-advance? queue))
          (cmd/dispatch instance {:event :playback/advance})

          :else nil)))

(defmethod cmd/dispatch :playback/stop
  [instance e]
  (cmd/ensure-valid! e)
  (bus/dispatch-event! instance {:event :vlcj.controls-api/stop}))
