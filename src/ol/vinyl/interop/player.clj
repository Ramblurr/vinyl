;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.interop.player
  (:require
   [clojure.core.async :as async]
   [ol.vinyl.interop.listener :as listener])
  (:import
   [uk.co.caprica.vlcj.factory MediaPlayerFactory]
   [uk.co.caprica.vlcj.player.base  MediaPlayer State]))

#_(defn handler! [ev]
    (try
      (async/put! internal-ch ev)
      (catch Exception e
        (log/error e "player put internal-ch error"))))

;; see package-private uk.co.caprica.vlcj.player.component/MediaPlayerComponentDefaults
(def audio-media-player-args (into-array String ["--quiet" "--intf=dummy"]))
;; see package-private uk.co.caprica.vlcj.player.component/EMBEDDED_MEDIA_PLAYER_ARGS
#_(def embedded-media-player-args
    (into-array String ["--video-title=vlcj video output" "--no-snapshot-preview" "--quiet" "--intf=dummy"]))

(defn create-audio-player!
  "Create a VLC audio player with the default settings.
  Returns an opaque map that can be passed to other functions under the .interop ns named 'player'.
  When the player is no longer needed, it should be released by calling `release!`."
  ([]
   (create-audio-player! nil))

  ([^MediaPlayerFactory factory]
   (let [media-player-factory (or factory (MediaPlayerFactory. audio-media-player-args))
         vlc-event-chan (async/chan (async/sliding-buffer 32))
         ^MediaPlayer media-player (.newMediaPlayer (.mediaPlayers media-player-factory))
         event-listener (listener/create-media-player-listener (fn [ev] (async/put! vlc-event-chan ev)))
         media-event-listener (listener/create-media-event-listener (fn [ev] (async/put! vlc-event-chan ev)))]

     (.addMediaPlayerEventListener (.events media-player) event-listener)
     (.addMediaEventListener (.events media-player) media-event-listener)

     {:vlc/media-player media-player
      :vlc/media-player-factory media-player-factory
      :vlc/own-factory? (nil? factory)
      :vlc/event-listener event-listener
      :vlc/media-event-listener media-event-listener
      :vlc/<events vlc-event-chan})))

(defn release!
  "Release the media player component and the associated native media player resources."
  [{:vlc/keys [media-player media-player-factory <events own-factory? event-listener media-event-listener]}]
  (async/close! <events)
  (.removeMediaPlayerEventListener (.events media-player) event-listener)
  (.removeMediaEventListener (.events media-player) media-event-listener)
  (loop [attempt 0]
    (if (or (>= attempt 20) (contains? #{State/STOPPED State/ERROR} (-> media-player .status .state)))
      (do
        (Thread/sleep 2000)
        (.release media-player)
        (when own-factory?
          (.release media-player-factory)))
      (do
        (-> media-player .controls .stop)
        (Thread/sleep 50)
        (recur (inc attempt)))))
  nil)

(defn run-on-player-thread
  "Runs the function `f` asynchronously on the vlcj player thread.


  This is useful in particular for event handling code as native events are
  generated on a native event callback thread and it is not allowed to call back
  into LibVLC from this callback thread. If you do, either the call will be
  ineffective, strange behaviour will happen, or a fatal JVM crash may occur."
  [{:vlc/keys [^MediaPlayer media-player]} f]
  (assert (instance? MediaPlayer media-player) "run-on-player-thread requires a valid MediaPlayer instance")
  (assert (fn? f) "run-on-player-thread requires a function to run on the player thread")
  (.submit media-player f))

(comment
  (def _fac (MediaPlayerFactory.))
  (def _mp (.newMediaPlayer (.mediaPlayers _fac)))

  (.addMediaPlayerEventListener (.events _mp)
                                (listener/wait-for-stop-listener (fn []
                                                                   (tap> :got-stop))))
  (.addMediaPlayerEventListener (.events _mp)
                                (listener/create-media-player-listener (fn [ev]
                                                                         (tap> [:player ev]))))
  (.addMediaEventListener (.events _mp)
                          (listener/create-media-event-listener (fn [ev]
                                                                  (tap> [:media ev]))))

  (-> _mp .status .state)
  (-> _mp .controls .stop)
  (.release _mp)
  ;;
  )