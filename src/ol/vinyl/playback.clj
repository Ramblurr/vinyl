;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.playback
  (:require
   [clojure.core.async :as async]
   [ol.vinyl.bus :as bus]
   [ol.vinyl.commands :as cmd]
   [ol.vinyl.interop.api :as api]
   [ol.vinyl.interop.parsing :as parsing]
   [ol.vinyl.queue :as queue]
   [ol.vinyl.schema :as s]))

(defn- queue [instance]
  (let [state_ (if-let [state_ (:ol.vinyl.impl/state_ instance)]
                 @state_
                 instance)]
    (get-in state_ [:playback :queue])))

(defn emit [{:ol.vinyl.impl/keys [<events]} event-name & {:as payload}]
  (let [ev (merge payload {:ol.vinyl/event event-name})]
    (s/ensure-valid! ev)
    (async/put! <events ev)))

(defn notify-player
  "Notify the player play or stop playing based on the current track"
  [instance current]
  (if current
    (bus/dispatch-command! instance
                           {:ol.vinyl/command :vlcj.media-api/play
                            :mrl-or-media-ref (:mrl current)})
    (bus/dispatch-command! instance {:ol.vinyl/command :vlcj.controls-api/stop})))

(defn emit-queue-changed [i olds news]
  (let [before (queue/list-all olds)
        after (queue/list-all news)]
    (when-not (= before after)
      (emit i ::queue-changed
            :before-queue  before
            :after-queue after))))

(defn emit-current-changed [i olds news]
  (let [current-before (queue/get-current olds)
        current-after (queue/get-current news)]
    (when-not (= current-before current-after)
      (notify-player i current-after)
      (emit i ::current-track-changed
            :current-track current-after))))

(defn emit-repeat-changed [i olds news]
  (let [mode-before (queue/repeat-mode olds)
        mode-after (queue/repeat-mode news)]
    (when-not (= mode-before mode-after)
      (emit i ::repeat-changed
            :mode-before mode-before
            :mode-after mode-after))))

(defn emit-shuffle-changed [i olds news]
  (let [shuffle-before (queue/shuffle? olds)
        shuffle-after (queue/shuffle? news)]
    (when-not (= shuffle-before shuffle-after)
      (emit i ::shuffle-changed
            :shuffle? shuffle-after))))

(defn- set-queue [{:ol.vinyl.impl/keys [state_] :as i} new-queue]
  (let [[olds news] (swap-vals! state_ assoc-in [:playback :queue] new-queue)
        oldq (queue olds)
        newq (queue news)]
    (emit-queue-changed i oldq newq)
    (emit-repeat-changed i oldq newq)
    (emit-shuffle-changed i oldq newq)
    (emit-current-changed i oldq newq)))

(defn list-all [instance]
  (queue/list-all (queue instance)))

(defn get-current [instance]
  (queue/get-current (queue instance)))

(defn release! [{:ol.vinyl.impl/keys [state_] :as instance}]
  (bus/unsubscribe-impl! instance (get-in @state_ [:playback :sub-id]))
  (set-queue instance (queue/clear-all (queue instance))))

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
    (when (queue/can-advance? prev-queue)
      (set-queue instance (queue/advance prev-queue)))))

(defmethod cmd/dispatch :playback/append
  [instance {:keys [paths] :as cmd}]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        tracks (expand-paths instance paths)]
    (when (seq tracks)
      (let [new-queue (queue/append prev-queue tracks)]
        (set-queue instance new-queue)))))

(defmethod cmd/dispatch :playback/clear-all
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)]
    (set-queue instance (queue/clear-all prev-queue))
    (bus/dispatch-command! instance {:ol.vinyl/command :vlcj.media-api/reset})))

(defmethod cmd/dispatch :playback/next-track
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)]
    (when (queue/can-advance? prev-queue)
      (set-queue instance (queue/next-track prev-queue)))))

(defmethod cmd/dispatch :playback/previous-track
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)]
    (when (queue/can-rewind? prev-queue)
      (set-queue instance (queue/prev-track prev-queue)))))

(defmethod cmd/dispatch :playback/set-shuffle
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        shuffle? (:shuffle? cmd)]
    (set-queue instance (queue/set-shuffle prev-queue shuffle?))))

(defmethod cmd/dispatch :playback/set-repeat
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        mode (:mode cmd)]
    (set-queue instance (queue/set-repeat prev-queue mode))))

(defmethod cmd/dispatch :playback/clear-upcoming
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)]
    (set-queue instance (queue/clear-upcoming prev-queue))))

(defmethod cmd/dispatch :playback/move
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        from (:from cmd)
        to (:to cmd)]
    (set-queue instance (queue/move prev-queue from to))))

(defmethod cmd/dispatch :playback/replace-at
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        position (:position cmd)
        tracks (expand-paths instance (:paths cmd))]
    (when (seq tracks)
      (let [new-queue (queue/replace-at prev-queue position tracks)]
        (if (= position 0)
          (set-queue instance new-queue)
          (set-queue instance new-queue))))))

(defmethod cmd/dispatch :playback/remove-at
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        position   (:position cmd)
        new-queue  (queue/remove-at prev-queue [position])]
    (if (= position 0)
      (set-queue instance new-queue)
      (set-queue instance new-queue))))

(defmethod cmd/dispatch :playback/add-next
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (let [prev-queue (queue instance)
        tracks (expand-paths instance (:paths cmd))]
    (when (seq tracks)
      (let [new-queue (queue/add-next prev-queue tracks)]
        (set-queue instance new-queue)))))

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

(defmethod cmd/dispatch :playback/play-from
  [instance cmd]
  (cmd/ensure-valid! cmd)
  (tap> [:play-from cmd (queue/play-from (queue instance) (:index cmd))])
  (set-queue instance (queue/play-from (queue instance) (:index cmd))))

(defn handle-player-event [i event]
  (let [q (queue i)]
    #_(tap> event)
    (condp = (:ol.vinyl/event event)
      :vlc/finished (when (queue/can-advance? q)
                      (bus/dispatch-command! i {:ol.vinyl/command :playback/advance}))
      nil
      #_(tap> [:unhandled-event (:ol.vinyl/event event) event]))))

(defn init! [{:ol.vinyl.impl/keys [state_] :as instance}]
  (let [sub-id (bus/subscribe-impl! instance #{:vlc/finished :vlc/opening :vlc/paused :vlc/playing :vlc/stopped :vlc/error} (fn [e] (handle-player-event instance e)))]
    (swap! state_ update :playback (fn [_]
                                     {:queue (queue/create-queue)
                                      :sub-id sub-id}))))
