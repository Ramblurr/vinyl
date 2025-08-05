;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.playback
  (:require
   [ol.vinyl.bus :as bus]
   [ol.vinyl.commands :as cmd]
   [ol.vinyl.interop.api :as api]
   [ol.vinyl.interop.parsing :as parsing]
   [ol.vinyl.queue :as queue]))

(defn handle-player-event [{:ol.vinyl.impl/keys [_state_]} event]
  (when-not (contains? #{:vlc/time-changed :vlc/position-changed} (:ol.vinyl/event event))
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
    (bus/dispatch-command! instance
                           {:ol.vinyl/command :vlcj.media-api/play
                            :mrl-or-media-ref (:mrl current)})
    (bus/dispatch-command! instance {:ol.vinyl/command :vlcj.controls-api/stop})))

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
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)]
    (tap> [:playback/advance :instance instance :ol.vinyl/command cmd :prev-queue prev-queue :can-advance? (queue/can-advance? prev-queue)])
    (when (queue/can-advance? prev-queue)
      (update-queue-and-player instance (queue/advance prev-queue)))))

(defmethod cmd/dispatch :playback/append
  [instance {:keys [paths] :as cmd}]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        tracks (expand-paths instance paths)]
    (tap> [:playback/append :instance instance :ol.vinyl/command cmd :tracks tracks :paths paths :prev-queue prev-queue])
    (when (seq tracks)
      (let [new-queue (queue/append prev-queue tracks)]
        (set-queue instance new-queue)
        (when (and (nil? (queue/get-current prev-queue))
                   (queue/get-current new-queue))
          (notify-player instance new-queue))))))

(defmethod cmd/dispatch :playback/clear-all
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)]
    (tap> [:playback/clear-all :instance instance :ol.vinyl/command cmd :prev-queue prev-queue])
    (update-queue-and-player instance (queue/clear-all prev-queue))
    (bus/dispatch-command! instance {:ol.vinyl/command :vlcj.media-api/reset})))

(defmethod cmd/dispatch :playback/next-track
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)]
    (tap> [:playback/next-track :instance instance :ol.vinyl/command cmd :prev-queue prev-queue])
    (when (queue/can-advance? prev-queue)
      (update-queue-and-player instance (queue/next-track prev-queue)))))

(defmethod cmd/dispatch :playback/previous-track
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)]
    (tap> [:playback/previous-track :instance instance :ol.vinyl/command cmd :prev-queue prev-queue])
    (when (queue/can-rewind? prev-queue)
      (update-queue-and-player instance (queue/prev-track prev-queue)))))

(defmethod cmd/dispatch :playback/set-shuffle
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        shuffle? (:shuffle? cmd)]
    (tap> [:playback/set-shuffle :instance instance :ol.vinyl/command cmd :shuffle? shuffle? :prev-queue prev-queue])
    (set-queue instance (queue/set-shuffle prev-queue shuffle?))))

(defmethod cmd/dispatch :playback/set-repeat
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        mode (:mode cmd)]
    (tap> [:playback/set-repeat :instance instance :ol.vinyl/command cmd :mode mode :prev-queue prev-queue])
    (set-queue instance (queue/set-repeat prev-queue mode))))

(defmethod cmd/dispatch :playback/clear-upcoming
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)]
    (tap> [:playback/clear-upcoming :instance instance :ol.vinyl/command cmd :prev-queue prev-queue])
    (set-queue instance (queue/clear-upcoming prev-queue))))

(defmethod cmd/dispatch :playback/move
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        from (:from cmd)
        to (:to cmd)]
    (tap> [:playback/move :instance instance :ol.vinyl/command cmd :from from :to to :prev-queue prev-queue])
    (set-queue instance (queue/move prev-queue from to))))

(defmethod cmd/dispatch :playback/replace-at
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        position (:position cmd)
        tracks (expand-paths instance (:paths cmd))]
    (tap> [:playback/replace-at :instance instance :ol.vinyl/command cmd :position position :tracks tracks :prev-queue prev-queue])
    (when (seq tracks)
      (let [new-queue (queue/replace-at prev-queue position tracks)]
        (if (= position 0)
          (update-queue-and-player instance new-queue)
          (set-queue instance new-queue))))))

(defmethod cmd/dispatch :playback/remove-at
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        position (:position cmd)]
    (tap> [:playback/remove-at :instance instance :ol.vinyl/command cmd :position position :prev-queue prev-queue])
    (let [new-queue (queue/remove-at prev-queue [position])]
      (if (= position 0)
        (update-queue-and-player instance new-queue)
        (set-queue instance new-queue)))))

(defmethod cmd/dispatch :playback/add-next
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        tracks (expand-paths instance (:paths cmd))]
    (tap> [:playback/add-next :instance instance :ol.vinyl/command cmd :tracks tracks :prev-queue prev-queue])
    (when (seq tracks)
      (let [new-queue (queue/add-next prev-queue tracks)]
        (set-queue instance new-queue)
        (when (and (nil? (queue/get-current prev-queue))
                   (queue/get-current new-queue))
          (notify-player instance new-queue))))))

(defmethod cmd/dispatch :playback/play
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [queue (queue instance)
        current (queue/get-current queue)
        state (api/get-state (:ol.vinyl.impl/player instance))
        stopped? (= :state/stopped  state)
        paused? (= :state/paused  state)]
    (cond (and paused? current)
          (bus/dispatch-command! instance {:ol.vinyl/command :vlcj.controls-api/play})

          (and stopped? current)
          (notify-player instance queue)

          (and (nil? current) (queue/can-advance? queue))
          (cmd/dispatch instance {:ol.vinyl/command :playback/advance})

          :else nil)))

(defmethod cmd/dispatch :playback/stop
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (bus/dispatch-command! instance {:ol.vinyl/command :vlcj.controls-api/stop}))
