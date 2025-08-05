;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.commands
  (:import
   [uk.co.caprica.vlcj.media MediaRef]
   [uk.co.caprica.vlcj.player.base AudioChannel Equalizer])
  (:require
   [ol.vinyl.interop.enum :as enum]
   [ol.vinyl.protocols :as protocols]
   [malli.util :as mu]
   [malli.core :as m]))

(def audio-channel-enum (into [:enum] (vals (:enum->kw-map (enum/create-enum-converters AudioChannel)))))

(def empty-payload [:map {:closed true}
                    [:event {:doc "event type, one of the keys in `events`"} :keyword]])

(def media-ref-schema [:fn #(instance? MediaRef %)])
(def playable-schema [:fn protocols/playable?])

(defn with-payload [& args]
  (reduce (fn [schema [kw options arg-schema]]
            (mu/assoc schema [kw options] arg-schema)) empty-payload
          args))

(def native-event-ns #{"mixer" "player" "control"})

(defn native? [event]
  (contains? native-event-ns (-> event :event namespace)))

;; These are low level vlcj MediaPlayer controls, you probably want to use the playback-controls below
(def player-controls
  "Corresponds to commands on [[uk.co.caprica.vlcj.player.base.MediaApi]]"
  {:vlcj.media-api/play ["Set new media and play it."
                         (with-payload
                           [:mrl-or-media-ref {:doc "media resource locator or MediaRef. The handler takes responsibility for freeing the media resource"} [:or :string media-ref-schema]]
                           [:options {:optional true :doc "zero or more options to attach to the new media"} [:sequential :string]])]
   :vlcj.media-api/prepare ["Prepare new media (set it, do not play it)."
                            (with-payload
                              [:mrl-or-media-ref {:doc "media resource locator or MediaRef. The handler takes responsibility for freeing the media resource"} [:or :string media-ref-schema]]
                              [:options {:optional true :doc "zero or more options to attach to the new media"} [:sequential :string]])]
   :vlcj.media-api/reset  ["Reset the media (i.e. unset it)." empty-payload]})

(def  control-controls
  "Corresponds to commands on [[uk.co.caprica.vlcj.player.base.ControlsApi]]"
  {:vlcj.controls-api/play ["Begin play-back. If called when the play-back is paused, the play-back will resume from the current position." empty-payload]
   :vlcj.controls-api/pause ["Pause play-back. If the play-back is currently paused it will begin playing." empty-payload]
   :vlc.controlsj-api/set-pause ["Pause/resume."
                                 (with-payload [:paused? {:doc "true to pause, false to play/resume"} :boolean])]
   :vlcj.controls-api/stop ["Stop play-back. A subsequent play will play-back from the start." empty-payload]
   :vlcj.controls-api/skip-time ["Skip forward or backward by a period of time. To skip backwards specify a negative delta."
                                 (with-payload [:delta-ms {:doc "delta in milliseconds, positive to skip forward, negative to skip backward"} :int])]
   :vlcj.controls-api/skip-position ["Skip forward or backward by a change in position. To skip backwards specify a negative delta."
                                     (with-payload [:delta {:doc "amount in percentage, positive to skip forward, negative to skip backward"} :float])]
   :vlcj.controls-api/set-time  ["Jump to a specific moment. If the requested time is less than zero, it is normalised to zero."
                                 (with-payload [:time-ms {:doc "time since the beginning in milliseconds"} :int])]
   :vlcj.controls-api/set-position ["Jump to a specific position in the track. If the requested position is less than zero, it is normalised to zero."
                                    (with-payload [:position-ms {:doc "position value, a percentage (e.g. 0.15 is 15%)"} :float])]
   :vlcj.controls-api/set-repeat ["Set whether or not the media player should automatically repeat playing the media when it has finished playing."
                                  (with-payload [:repeat? {:doc "true to automatically replay the media, otherwise false"} :boolean])]})

(def mixer-controls
  "Corresponds to commands on [[uk.co.caprica.vlcj.player.base.AudioApi]]"
  {:vlcj.audio-api/mute ["Toggle volume mute." empty-payload]
   :vlcj.audio-api/set-mute ["Mute or un-mute the volume."
                             (with-payload [:muted? {:doc "true to mute the volume, false to un-mute it"} :boolean])]
   :vlcj.audio-api/set-volume ["Set the volume. The volume is actually a percentage of full volume, setting a volume over 100 may cause audible distortion."
                               (with-payload [:level {:doc "volume, a percentage of full volume in the range 0 to 200"} :int])]
   :vlcj.audio-api/set-channel ["Set the audio channel. see `audio-channels`"
                                (with-payload [:channel {:doc "audio channel, one of the values in `audio-channels`"} audio-channel-enum])]
   :vlcj.audio-api/set-delay ["Set the audio delay. The audio delay is set for the current item only and will be reset to zero each time the media changes."
                              (with-payload [:delay {:doc "desired audio delay, in microseconds"} :int])]
   :vlcj.audio-api/set-equalizer ["Set the audio equalizer."
                                  (with-payload [:equalizer {:doc "equalizer, or null to disable the audio equalizer"} [:fn #(instance? Equalizer %)]])]
   :vlcj.audio-api/set-output ["Set the desired audio output. The change will not be applied until the media player has been stopped and then played again"
                               (with-payload [:output {:doc "name of the desired audio output"} :string])]
   :vlcj.audio-api/set-output-device ["Set the desired audio output device. The change will not be applied until the media player has been stopped and then played again."
                                      (with-payload
                                        [:output {:doc "name of the desired audio output"} :string]
                                        [:output-device-id {:doc "id of the desired audio output device"} :string])]})

;; Our user-friendly porcelain playback controls.
;; these are not from vlc/vlcj, but provided by our playback/queue ns
(def playback-controls {:playback/advance ["Advance the queue respecting repeat modes" empty-payload]
                        :playback/next-track ["Play the next track in the queue regardless of repeat mode" empty-payload empty-payload]
                        :playback/previous-track ["Play the previous track in the queue" empty-payload]
                        :playback/set-shuffle ["Set the shuffle mode for the queue"
                                               (with-payload [:shuffle? {:doc "true to enable shuffle, false to disable it"} :boolean])]
                        :playback/set-repeat ["Set the repeat mode for the queue"
                                              (with-payload [:mode {:doc "the repeat mode"} [:enum :none :track :list]])]
                        :playback/clear-upcoming ["Clear the upcoming queue" empty-payload]
                        :playback/clear-all ["Clear the upcoming queue and history" empty-payload]
                        :playback/move ["Move a track in the queue to a new position"
                                        (with-payload
                                          [:from {:doc "the current position of the track"} :int]
                                          [:to {:doc "the new position of the track"} :int])]

                        :playback/replace-at ["Replace a track in the queue at a specific position"
                                              (with-payload
                                                [:position {:doc "the position to replace"} :int]
                                                [:paths {:doc "the paths to insert"} [:sequential playable-schema]])]
                        :playback/remove-at ["Remove a track from the queue at a specific position"
                                             (with-payload [:position {:doc "the position to remove"} :int])]
                        :playback/add-next ["Adds tracks to the end of the priority queue."
                                            (with-payload [:paths {:doc "the paths to add"} [:sequential playable-schema]])]
                        :playback/append ["Append tracks to the end of the queue."
                                          (with-payload [:paths {:doc "the paths to append"} [:sequential playable-schema]])]
                        :playback/play  ["Begin play-back of the current item in the queue, or the next item in the queue if there is no current. If called when the play-back is paused, the play-back will resume from the current position." empty-payload]
                        :playback/stop ["Stop play-back. A subsequent play will play-back from the start." empty-payload]})

(def commands (merge mixer-controls control-controls player-controls playback-controls))

(def command-aliases
  "Some of the lower-level vlcj commands can be directly exposed, but we want to do it with a pretty porcelain api "
  {:mixer/mute :vlcj.audio-api/mute
   :mixer/set-mute  :vlcj.audio-api/set-mute
   :mixer/set-volume  :vlcj.audio-api/set-volume
   :playback/pause :vlcj.controls-api/pause
   :playback/set-pause :vlcj.controls-api/set-pause
   :playback/skip-time :vlcj.controls-api/skip-time
   :playback/skip-position :vlcj.controls-api/skip-position
   :playback/set-position :vlcj.controls-api/set-position})

(defn resolve-alias [{:keys [event] :as e}]
  (assoc e :event (get command-aliases event event)))

(defn named-event-schema [event]
  (when-let [[_ event-schema] (get commands event)]
    #_(tap> [:named-event-schema event event-schema])
    (mu/assoc event-schema :event [:fn #(= % event)])))

(defn validate-event [{:keys [event] :as event-map}]
  (if-let [event-schema (named-event-schema event)]
    (m/validate event-schema event-map)
    (throw (ex-info "Unknown event type" {:event event :message (str "No schema defined for event: " event)}))))

(defn explain-event [{:keys [event] :as event-map}]
  (if-let [event-schema (named-event-schema event)]
    (m/explain event-schema event-map)
    (throw (ex-info "Unknown event type" {:event event :message (str "No schema defined for event: " event)}))))

(defn ensure-valid! [event]
  (when-not (validate-event event)
    (throw (ex-info "invalid event" {:event event :explanation (explain-event event)}))))

(comment
  (validate-event {:event :vlcj.controls-api/play})
  (explain-event {:event :vlcj.controls-api/play})
  (explain-event {:event :vlcj.controls-api/play :thing :paused}))

(defmulti dispatch
  "Dispatch an event to vlc.
  The first argument is the player, and the second is the event map.
  Dispatch is based on the :event key of the event map."
  (fn [_ event] (:event event)))

(defmethod dispatch :default
  [_instance event]
  (throw (ex-info "Unknown event type" {:event (:event event)
                                        :message (str "No handler defined for event: " (:event event))})))