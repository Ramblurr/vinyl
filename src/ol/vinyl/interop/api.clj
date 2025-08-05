;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.interop.api
  (:require
   [ol.vinyl.commands :as cmd]
   [ol.vinyl.interop.enum :as enum])
  (:import
   [uk.co.caprica.vlcj.media
    AudioTrackInfo
    Media
    MediaRef
    MediaType
    TextTrackInfo
    TrackType
    VideoTrackInfo]
   [uk.co.caprica.vlcj.player.base
    AudioChannel
    AudioDevice
    Equalizer
    MediaApi
    MediaPlayer
    State]))

(def audio-channel-converters (enum/create-enum-converters AudioChannel))
(def media-type-converters (enum/create-enum-converters MediaType))
(def state-converters (enum/create-enum-converters State))
(def track-type-converters (enum/create-enum-converters TrackType))

;; --- MediaApi Commands ---
;; uk.co.caprica.vlcj.player.base.MediaApi

(defmethod cmd/dispatch :vlcj.media-api/play
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player :as _instance} {:keys [mrl-or-media-ref options] :as cmd}]
  (cmd/ensure-valid! cmd)
  (let [^MediaApi media-api (.media media-player)
        ^String/1 options (when options (into-array String options))]
    (if (string? mrl-or-media-ref)
      (.play media-api ^String mrl-or-media-ref options)
      (let [^MediaRef media-ref mrl-or-media-ref]
        (try
          (.play media-api media-ref options)
          (finally
            (.release media-ref)))))))

(defmethod cmd/dispatch :vlcj.media-api/reset
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (let [^MediaApi media-api (.media media-player)]
    (.reset media-api)))

(defmethod cmd/dispatch :vlcj.media-api/prepare
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} {:keys [mrl-or-media-ref options] :as cmd}]
  (cmd/ensure-valid! cmd)
  (let [^MediaApi media-api (.media media-player)
        ^String/1 options (when options (into-array String options))]
    (if (string? mrl-or-media-ref)
      (.play media-api ^String mrl-or-media-ref options)
      (let [^MediaRef media-ref mrl-or-media-ref]
        (try
          (.prepare media-api media-ref options)
          (finally
            (.release media-ref)))))))

;; --- ControlsApi Commands ---
;; uk.co.caprica.vlcj.player.base.ControlsApi

(defmethod cmd/dispatch :vlcj.controls-api/play
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .controls .play))

(defmethod cmd/dispatch :vlcj.controls-api/pause
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .controls .pause))

(defmethod cmd/dispatch :vlc.controlsj-api/set-pause
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .controls (.setPause (:paused? cmd))))

(defmethod cmd/dispatch :vlcj.controls-api/stop
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .controls .stop))

(defmethod cmd/dispatch :vlc.controlsj-api/skip-time
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .controls (.skipTime (:delta-ms cmd))))

(defmethod cmd/dispatch :vlcj.controls-api/skip-position
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .controls (.skipPosition (:delta cmd))))

(defmethod cmd/dispatch :vlcj.controls-api/set-time
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .controls (.setTime (:time-ms cmd))))

(defmethod cmd/dispatch :vlcj.controls-api/set-position
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .controls (.setPosition (:position-ms cmd))))

(defmethod cmd/dispatch :vlcj.controls-api/set-repeat
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .controls (.setRepeat (:repeat? cmd))))

;; --- Controls Query API ---

(defn get-repeat
  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .controls .getRepeat))

;; --- AudioApi Commands ---
;; uk.co.caprica.vlcj.player.base.AudioApi

(defmethod cmd/dispatch :vlcj.audio-api/mute
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .audio .mute))

(defmethod cmd/dispatch :vlcj.audio-api/set-mute
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .audio (.setMute (:muted? cmd))))

(defmethod cmd/dispatch :vlcj.audio-api/set-volume
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .audio (.setVolume (:level cmd))))

(defmethod cmd/dispatch :vlcj.audio-api/set-channel
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (let [channel-enum ((:kw->enum audio-channel-converters) (:channel cmd))]
    (-> media-player .audio (.setChannel channel-enum))))

(defmethod cmd/dispatch :vlcj.audio-api/set-delay
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .audio (.setDelay (:delay cmd))))

(defmethod cmd/dispatch :vlcj.audio-api/set-equalizer
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .audio (.setEqualizer (:equalizer cmd))))

(defmethod cmd/dispatch :vlcj.audio-api/set-output
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .audio (.setOutput (:output cmd))))
(defmethod cmd/dispatch :vlcj.audio-api/set-output-device
  [{{:vlc/keys [^MediaPlayer media-player]} :ol.vinyl.impl/player} cmd]
  (cmd/ensure-valid! cmd)
  (-> media-player .audio (.setOutputDevice (:output cmd) (:output-device-id cmd))))

;; --- AudioApi Query API ---

(defn get-channel [{:vlc/keys [^MediaPlayer media-player]}]
  (let [->kw (:enum->kw audio-channel-converters)]
    (-> media-player .audio .channel ->kw)))

(defn get-delay [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .audio .delay))

(defn get-equalizer ^Equalizer [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .audio .equalizer))

(defn muted? [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .audio .isMute))

(defn get-output-device ^String [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .audio .outputDevice))

(defn get-output-devices [{:vlc/keys [^MediaPlayer media-player]}]
  (or (mapv (fn [^AudioDevice ad]
              {:audio-device/device-id (.getDeviceId ad)
               :audio-device/long-name (.getLongName ad)})
            (-> media-player .audio .outputDevices))
      []))

(defn get-volume [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .audio .volume))

;; --- Status Query API ---
;; uk.co.caprica.vlcj.player.base.StatusApi

(defn can-pause?
  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .status .canPause))

(defn playable?
  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .status .isPlayable))

(defn playing?
  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .status .isPlaying))

(defn seekable?
  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .status .isSeekable))

(defn get-length
  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .status .length))

(defn get-position
  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .status .position))

(defn get-rate
  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .status .rate))

(defn get-state
  [{:vlc/keys [^MediaPlayer media-player]}]
  (let [->kw (:enum->kw state-converters)]
    (-> media-player .status .state ->kw)))

(defn get-time
  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .status .time))

(defn get-video-outputs
  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .status .videoOutputs))

;; --- Media InfoApi Query ---
;; uk.co.caprica.vlcj.media.InfoApi

(defn get-media ^Media  [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .media .newMedia))

(defn get-mrl [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .media .info .mrl))

(defn get-type [{:vlc/keys [^MediaPlayer media-player]}]
  (let [->kw (:enum->kw media-type-converters)]
    (-> media-player .media .info .type ->kw)))

(defn get-duration [{:vlc/keys [^MediaPlayer media-player]}]
  (-> media-player .media .info .duration))

(defn- audio-track->map [^AudioTrackInfo track]
  {:track/type :audio
   :track/channels (.channels track)
   :track/rate (.rate track)
   :track/bit-rate (.bitRate track)
   :track/codec (.codec track)
   :track/codec-description (.codecDescription track)
   :track/codec-name (.codecName track)
   :track/description (.description track)
   :track/id (.id track)
   :track/language (.language track)
   :track/level (.level track)
   :track/profile (.profile track)})

(defn- video-track->map [^VideoTrackInfo track]
  {:track/type :video
   :track/width (.width track)
   :track/height (.height track)
   :track/frame-rate (.frameRate track)
   :track/frame-rate-base (.frameRateBase track)
   :track/codec (.codec track)
   :track/codec-description (.codecDescription track)
   :track/codec-name (.codecName track)
   :track/description (.description track)
   :track/id (.id track)
   :track/language (.language track)
   :track/level (.level track)
   :track/profile (.profile track)})

(defn- text-track->map [^TextTrackInfo track]
  {:track/type :text
   :track/encoding (.encoding track)
   :track/codec (.codec track)
   :track/codec-description (.codecDescription track)
   :track/codec-name (.codecName track)
   :track/description (.description track)
   :track/id (.id track)
   :track/language (.language track)
   :track/level (.level track)
   :track/profile (.profile track)})

(defn get-tracks
  [{:vlc/keys [^MediaPlayer media-player]} & track-types]
  (let [types (if (seq track-types)
                (into-array TrackType (map #((:kw->enum track-type-converters) %) track-types))
                (into-array TrackType []))]
    (->> (-> media-player .media .info (.tracks types))
         (mapv (fn [track]
                 (cond
                   (instance? AudioTrackInfo track) (audio-track->map track)
                   (instance? VideoTrackInfo track) (video-track->map track)
                   (instance? TextTrackInfo track) (text-track->map track)
                   :else {:track/type :unknown}))))))

(defn get-audio-tracks [{:vlc/keys [^MediaPlayer media-player]}]
  (->> (-> media-player .media .info .audioTracks)
       (mapv audio-track->map)))

(defn get-video-tracks [{:vlc/keys [^MediaPlayer media-player]}]
  (->> (-> media-player .media .info .videoTracks)
       (mapv video-track->map)))

(defn get-text-tracks [{:vlc/keys [^MediaPlayer media-player]}]
  (->> (-> media-player .media .info .textTracks)
       (mapv text-track->map)))

(defn get-statistics [{:vlc/keys [^MediaPlayer media-player]}]
  (when-let [stats (-> media-player .media .info .statistics)]
    {:stats/decoded-audio (.decodedAudio stats)
     :stats/decoded-video (.decodedVideo stats)
     :stats/demux-bit-rate (.demuxBitrate stats)
     :stats/demux-corrupted (.demuxCorrupted stats)
     :stats/demux-discontinuity (.demuxDiscontinuity stats)
     :stats/demux-bytes-read (.demuxBytesRead stats)
     :stats/pictures-displayed (.picturesDisplayed stats)
     :stats/input-bit-rate (.inputBitrate stats)
     :stats/input-bytes-read (.inputBytesRead stats)
     :stats/audio-buffers-lost (.audioBuffersLost stats)
     :stats/pictures-lost (.picturesLost stats)
     :stats/audio-buffers-played (.audioBuffersPlayed stats)
     :stats/sent-bytes (.sentBytes stats)
     :stats/sent-packets (.sentPackets stats)}))
