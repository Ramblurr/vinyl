;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.impl
  (:require
   [clojure.core.async :as async]
   [ol.vinyl.bus :as bus]
   [ol.vinyl.interop.api :as api]
   [ol.vinyl.interop.parsing :as parsing]
   [ol.vinyl.interop.player :as player]
   [ol.vinyl.playback :as playback]))

(defn create-player-impl
  [{:keys [media-player-factory] :as _opts}]
  (let [<control (async/chan (async/sliding-buffer 32))
        <events (async/chan (async/sliding-buffer 32))
        <close (async/chan)
        instance {::player (player/create-audio-player! <events media-player-factory)
                  ::<control <control
                  ::<close <close
                  ::state_ (atom {:subscriptions {}})}]
    (bus/start-control-loop! instance)
    (bus/start-event-loop! instance)
    (playback/init! instance)
    instance))

(defn release-player-impl!
  [{::keys [<events player <close state_] :as instance}]
  (when-not (= @state_ :released)
    (async/>!! <close :close)
    (async/close! <events)
    (player/release! player)
    (playback/release! instance)
    (reset! state_ :released)))

(defn ensure-not-released! [{::keys [state_] :as _i}]
  (when (= @state_ :released)
    (throw (ex-info "Player has been released and cannot be used anymore." {}))))

(defn get-repeat-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-repeat player))

(defn get-channel-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-channel player))

(defn get-delay-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-delay player))

(defn get-equalizer-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-equalizer player))

(defn muted?-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/muted? player))

(defn get-output-device-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-output-device player))

(defn get-output-devices-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-output-devices player))

(defn get-volume-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-volume player))

(defn playable?-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/playable? player))

(defn playing?-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/playing? player))

(defn seekable?-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/seekable? player))

(defn can-pause?-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/can-pause? player))

(defn get-length-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-length player))

(defn get-time-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-time player))

(defn get-position-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-position player))

(defn get-rate-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-rate player))

(defn get-video-outputs-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-video-outputs player))

(defn get-state-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-state player))

(defn get-current-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (let [current-queue-track (playback/get-current i)]
    (if current-queue-track
      (merge current-queue-track
             {:state (api/get-state player)
              :position (api/get-position player)
              :time (api/get-time player)})
      (when-let [current-media (api/get-media player)]
        (try
          {:duration (api/get-duration player)
           :position (api/get-position player)
           :time     (api/get-time player)
           :mrl      (api/get-mrl player)
           :state    (api/get-state player)
           :meta     (parsing/meta-for current-media)}
          (finally
            (when current-media
              (.release current-media))))))))

(defn get-media-mrl-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-mrl player))

(defn get-media-type-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-type player))

(defn get-media-duration-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-duration player))

(defn get-media-tracks-impl
  [{::keys [player] :as i} & track-types]
  (ensure-not-released! i)
  (apply api/get-tracks player track-types))

(defn get-audio-tracks-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-audio-tracks player))

(defn get-video-tracks-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-video-tracks player))

(defn get-text-tracks-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-text-tracks player))

(defn get-media-statistics-impl
  [{::keys [player] :as i}]
  (ensure-not-released! i)
  (api/get-statistics player))

(defn parse-meta
  [instance paths]
  (parsing/parse-meta paths {:media-player-factory (get-in instance [::player :vlc/media-player-factory])}))
