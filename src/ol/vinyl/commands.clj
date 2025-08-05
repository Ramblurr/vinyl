;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.commands
  (:require
   [malli.core :as m]
   [malli.util :as mu]
   [ol.vinyl.interop.enum :as enum]
   [ol.vinyl.protocols :as protocols])
  (:import
   [uk.co.caprica.vlcj.media MediaRef]
   [uk.co.caprica.vlcj.player.base AudioChannel Equalizer]))

(def audio-channel-enum (into [:enum] (vals (:enum->kw-map (enum/create-enum-converters AudioChannel)))))
(def media-ref-schema [:fn #(instance? MediaRef %)])
(def playable-schema [:fn protocols/playable?])

(defn command-map [doc]
  [:map {:doc doc
         :closed true}
   [:ol.vinyl/command {:doc "The name of the command"} :keyword]])

(defn with-payload [doc & payload-opts]
  (let [empty-payload (command-map doc)]
    (reduce (fn [schema [kw options arg-schema]]
              (mu/assoc schema [kw options] arg-schema)) empty-payload
            payload-opts)))

(def native-cmd-ns #{"vlcj.media-api"
                     "vlcj.controls-api"
                     "vlcj.audio-api"})

(def porcelain-cmd-ns #{"playback" "mixer"})

(defn native? [command]
  (contains? native-cmd-ns (-> command :ol.vinyl/command namespace)))

(defn porcelain? [command]
  (contains? porcelain-cmd-ns (-> command :ol.vinyl/command namespace)))

;; Our user-friendly porcelain playback controls.
(def playback-controls
  {:playback/advance (command-map "Advance the queue respecting repeat modes")
   :playback/next-track (command-map "Play the next track in the queue regardless of repeat mode")
   :playback/previous-track (command-map "Play the previous track in the queue")
   :playback/set-shuffle (with-payload "Set the shuffle mode for the queue"
                           [:shuffle? {:doc "true to enable shuffle, false to disable it"} :boolean])
   :playback/set-repeat (with-payload "Set the repeat mode for the queue"
                          [:mode {:doc "the repeat mode"} [:enum :none :track :list]])
   :playback/clear-upcoming (command-map "Clear the upcoming queue")
   :playback/clear-all (command-map "Clear the upcoming queue and history")
   :playback/move (with-payload "Move a track in the queue to a new position"
                    [:from {:doc "the current position of the track"} :int]
                    [:to {:doc "the new position of the track"} :int])
   :playback/replace-at (with-payload "Replace a track in the queue at a specific position"
                          [:position {:doc "the position to replace"} :int]
                          [:paths {:doc "the paths to insert"} [:sequential playable-schema]])
   :playback/remove-at (with-payload "Remove a track from the queue at a specific position"
                         [:position {:doc "the position to remove"} :int])
   :playback/add-next (with-payload "Adds tracks to the end of the priority queue."
                        [:paths {:doc "the paths to add"} [:sequential playable-schema]])
   :playback/append (with-payload "Append tracks to the end of the queue."
                      [:paths {:doc "the paths to append"} [:sequential playable-schema]])
   :playback/play (command-map "Begin play-back of the current item in the queue, or the next item in the queue if there is no current. If called when the play-back is paused, the play-back will resume from the current position.")
   :playback/stop (command-map "Stop play-back. A subsequent play will play-back from the start.")})

;; These are low level vlcj MediaPlayer controls, you probably want to use the porcelain playback-controls above
(def player-controls
  "Corresponds to commands on [[uk.co.caprica.vlcj.player.base.MediaApi]]"
  {:vlcj.media-api/play (with-payload "Set new media and play it."
                          [:mrl-or-media-ref {:doc "media resource locator or MediaRef. The handler takes responsibility for freeing the media resource"} [:or :string media-ref-schema]]
                          [:options {:optional true :doc "zero or more options to attach to the new media"} [:sequential :string]])
   :vlcj.media-api/prepare (with-payload "Prepare new media (set it, do not play it)."
                             [:mrl-or-media-ref {:doc "media resource locator or MediaRef. The handler takes responsibility for freeing the media resource"} [:or :string media-ref-schema]]
                             [:options {:optional true :doc "zero or more options to attach to the new media"} [:sequential :string]])
   :vlcj.media-api/reset (command-map "Reset the media (i.e. unset it).")})

(def control-controls
  "Corresponds to commands on [[uk.co.caprica.vlcj.player.base.ControlsApi]]"
  {:vlcj.controls-api/play (command-map "Begin play-back. If called when the play-back is paused, the play-back will resume from the current position.")
   :vlcj.controls-api/pause (command-map "Pause play-back. If the play-back is currently paused it will begin playing.")
   :vlcj.controls-api/set-pause (with-payload "Pause/resume."
                                  [:paused? {:doc "true to pause, false to play/resume"} :boolean])
   :vlcj.controls-api/stop (command-map "Stop play-back. A subsequent play will play-back from the start.")
   :vlcj.controls-api/skip-time (with-payload "Skip forward or backward by a period of time. To skip backwards specify a negative delta."
                                  [:delta-ms {:doc "delta in milliseconds, positive to skip forward, negative to skip backward"} :int])
   :vlcj.controls-api/skip-position (with-payload "Skip forward or backward by a change in position. To skip backwards specify a negative delta."
                                      [:delta {:doc "amount in percentage, positive to skip forward, negative to skip backward"} :float])
   :vlcj.controls-api/set-time (with-payload "Jump to a specific moment. If the requested time is less than zero, it is normalised to zero."
                                 [:time-ms {:doc "time since the beginning in milliseconds"} :int])
   :vlcj.controls-api/set-position (with-payload "Jump to a specific position in the track. If the requested position is less than zero, it is normalised to zero."
                                     [:position-ms {:doc "position value, a percentage (e.g. 0.15 is 15%)"} :float])
   :vlcj.controls-api/set-repeat (with-payload "Set whether or not the media player should automatically repeat playing the media when it has finished playing."
                                   [:repeat? {:doc "true to automatically replay the media, otherwise false"} :boolean])})

(def mixer-controls
  "Corresponds to commands on [[uk.co.caprica.vlcj.player.base.AudioApi]]"
  {:vlcj.audio-api/mute (command-map "Toggle volume mute.")
   :vlcj.audio-api/set-mute (with-payload "Mute or un-mute the volume."
                              [:muted? {:doc "true to mute the volume, false to un-mute it"} :boolean])
   :vlcj.audio-api/set-volume (with-payload "Set the volume. The volume is actually a percentage of full volume, setting a volume over 100 may cause audible distortion."
                                [:level {:doc "volume, a percentage of full volume in the range 0 to 200"} :int])
   :vlcj.audio-api/set-channel (with-payload "Set the audio channel. see `audio-channels`"
                                 [:channel {:doc "audio channel, one of the values in `audio-channels`"} audio-channel-enum])
   :vlcj.audio-api/set-delay (with-payload "Set the audio delay. The audio delay is set for the current item only and will be reset to zero each time the media changes."
                               [:delay {:doc "desired audio delay, in microseconds"} :int])
   :vlcj.audio-api/set-equalizer (with-payload "Set the audio equalizer."
                                   [:equalizer {:doc "equalizer, or null to disable the audio equalizer"} [:fn #(instance? Equalizer %)]])
   :vlcj.audio-api/set-output (with-payload "Set the desired audio output. The change will not be applied until the media player has been stopped and then played again"
                                [:output {:doc "name of the desired audio output"} :string])
   :vlcj.audio-api/set-output-device (with-payload "Set the desired audio output device. The change will not be applied until the media player has been stopped and then played again."
                                       [:output {:doc "name of the desired audio output"} :string]
                                       [:output-device-id {:doc "id of the desired audio output device"} :string])})

(def commands (merge mixer-controls control-controls player-controls playback-controls))

(defn find-first [pred coll] (first (filter pred coll)))
(defn properties-for [schema k] (second (find-first #(= k (first %)) (m/children schema))))

(def command-aliases
  "Some of the lower-level vlcj commands can be directly exposed, but we want to do it with a pretty porcelain api "
  {:mixer/mute :vlcj.audio-api/mute
   :mixer/set-mute :vlcj.audio-api/set-mute
   :mixer/set-volume :vlcj.audio-api/set-volume
   :playback/pause :vlcj.controls-api/pause
   :playback/set-pause :vlcj.controls-api/set-pause
   :playback/skip-time :vlcj.controls-api/skip-time
   :playback/skip-position :vlcj.controls-api/skip-position
   :playback/set-position :vlcj.controls-api/set-position})

(def porcelain-commands
  (merge
   (->> commands
        (filter (fn [[k _]] (porcelain? {:ol.vinyl/command k})))
        (into {}))
   (into {}
         (for [[alias target] command-aliases
               :when (porcelain? {:ol.vinyl/command alias})]
           [alias (get commands target)]))))

;; our sample schema
(second (vals mixer-controls))
;; => [:map {:doc "Mute or un-mute the volume.", :closed true} [:ol.vinyl/command {:doc "The name of the command"} :keyword] [:muted? {:doc "true to mute the volume, false to un-mute it"} :boolean]]

;; get the top-level schema properties
(m/properties (second (vals mixer-controls)))
;; => {:doc "Mute or un-mute the volume.", :closed true}

;; get the keys of the schema
(mu/keys (second (vals mixer-controls)))
;; => (:ol.vinyl/command :muted?)

;; get the type of a child
(m/type (mu/get-in (second (vals mixer-controls)) [:muted?]))
;; => :boolean

;; get the properties of a child
(properties-for (second (vals mixer-controls)) :muted?)
;; => {:doc "true to mute the volume, false to un-mute it"}

(def doc-porcelain-commands
  "A tabluar structure documenting the available options, ready to tap> with portal or print with clojure.pprint/print-table"
  (with-meta
    (vec
     (for [[cmd-name schema] (sort-by key porcelain-commands)]
       (let [doc (get (m/properties schema) :doc "")
             payload-keys (remove #{:ol.vinyl/command} (mu/keys schema))
             payload-summary (when (seq payload-keys)
                               (with-meta
                                 (for [k payload-keys]
                                   (let [props (properties-for schema k)
                                         opt? (:optional props)
                                         type (m/type (mu/get-in schema [k]))]
                                     {:key k
                                      :doc (str (when opt? "(optional) ") (:doc props))
                                      :type type}))
                                 {:portal.viewer/default :portal.viewer/table
                                  :portal.viewer/table {:columns [:key :doc :type]}}))]
         {:ol.vinyl/command cmd-name
          :doc doc
          :payload payload-summary})))
    {:portal.viewer/default :portal.viewer/table
     :portal.viewer/table {:columns [:ol.vinyl/command :doc :payload]}}))

(defn resolve-alias
  "Change the :ol.vinyl/command name to the canonical name if it is an alias."
  [{command :ol.vinyl/command :as cmd}]
  (assoc cmd :ol.vinyl/command (get command-aliases command command)))

(defn named-command-schema [command]
  (when-let [event-schema (get commands command)]
    (mu/assoc event-schema :ol.vinyl/command [:fn #(= % command)])))

(defn validate-command [{command :ol.vinyl/command :as cmd-map}]
  (if-let [event-schema (named-command-schema command)]
    (m/validate event-schema cmd-map)
    (throw (ex-info "Unknown command type" {:ol.vinyl/command command :message (str "No schema defined for command: " command)}))))

(defn explain-command [{command :ol.vinyl/command :as command-map}]
  (if-let [command-schema (named-command-schema command)]
    (m/explain command-schema command-map)
    (throw (ex-info "Unknown command type" {:ol.vinyl/command command :message (str "No schema defined for command: " command)}))))

(defn ensure-valid! [command]
  (when-not (validate-command command)
    (throw (ex-info "invalid event" {:ol.vinyl/command command :explanation (explain-command command)}))))

(comment
  (validate-command {:ol.vinyl/command :vlcj.controls-api/play})
  (explain-command {:ol.vinyl/command :vlcj.controls-api/play})
  (explain-command {:ol.vinyl/command :vlcj.controls-api/play :thing :paused}))

(defmulti dispatch
  "Dispatch an event to vlc.
  The first argument is the player, and the second is the command, a map.
  Dispatch is based on the :ol.vinyl/command key of the command map."
  (fn [_ command] (:ol.vinyl/command command)))

(defmethod dispatch :default
  [_instance command]
  (throw (ex-info (format "Unknown event type '%s'" (:ol.vinyl/command command))
                  {:command command
                   :message (str "No handler defined for event: " (:ol.vinyl/command command))})))
