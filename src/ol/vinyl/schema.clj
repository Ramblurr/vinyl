(ns ol.vinyl.schema
  (:require
   [malli.core :as m]
   [malli.util :as mu]
   [ol.vinyl.interop.enum :as enum]
   [ol.vinyl.protocols :as protocols])
  (:import
   [uk.co.caprica.vlcj.media MediaRef]
   [uk.co.caprica.vlcj.player.base AudioChannel]))

(def audio-channel-enum (into [:enum] (vals (:enum->kw-map (enum/create-enum-converters AudioChannel)))))
(def media-ref-schema [:fn #(instance? MediaRef %)])
(def playable-schema [:fn protocols/playable?])
(def track-schema [:fn protocols/track?])
(def tracklist-schema [:vector track-schema])
(def repeat-mode-schema
  [:enum {:doc "The repeat mode for the playback queue"}
   :none :list :track])
(def queue-schema [:map
                   [:history tracklist-schema]
                   [:normal tracklist-schema]
                   [:priority tracklist-schema]
                   [:current [:maybe track-schema]]])

(defn event-map [doc]
  [:map {:doc doc
         :closed true}
   [:ol.vinyl/event {:doc "The name of the event"} :keyword]])

(defn event-with-payload [doc & payload-opts]
  (let [empty-payload (event-map doc)]
    (reduce (fn [schema [kw options arg-schema]]
              (mu/assoc schema [kw options] arg-schema)) empty-payload
            payload-opts)))

(def events
  {:ol.vinyl.playback/queue-changed (event-with-payload "either the current track or one of the history, priority, or normal queues changed"
                                                        [:before-queue "the queue before the change" queue-schema]
                                                        [:after-queue "the queue after the change" queue-schema])
   :ol.vinyl.playback/current-track-changed (event-with-payload "the current track changed, or if nil, was stopped/removed"
                                                                [:current-track "" [:maybe track-schema]])
   :ol.vinyl.playback/repeat-changed (event-with-payload "the repeat mode changed"
                                                         [:mode-before "the previous repeat mode" repeat-mode-schema]
                                                         [:mode-after "the new repeat mode" repeat-mode-schema])
   :ol.vinyl.playback/shuffle-changed (event-with-payload "the shuffle mode changed"
                                                          [:shuffle? "the current shuffle state" :boolean])})

(defn named-event-schema [event]
  (when-let [event-schema (get events event)]
    (mu/assoc event-schema :ol.vinyl/event [:fn #(= % event)])))

(defn validate-event [{event :ol.vinyl/event :as cmd-map}]
  (if-let [event-schema (named-event-schema event)]
    (m/validate event-schema cmd-map)
    (throw (ex-info "Unknown event type" {:ol.vinyl/event event :message (str "No schema defined for event: " event)}))))

(defn explain-event [{event :ol.vinyl/event :as event-map}]
  (if-let [event-schema (named-event-schema event)]
    (m/explain event-schema event-map)
    (throw (ex-info "Unknown event type" {:ol.vinyl/event event :message (str "No schema defined for event: " event)}))))

(defn ensure-valid! [event]
  (when-not (validate-event event)
    (throw (ex-info "invalid event" {:ol.vinyl/event event :explanation (explain-event event)}))))
