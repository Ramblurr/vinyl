;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.bus
  (:require
   [clojure.core.async :as async]
   [ol.vinyl.commands :as cmd]
   [ol.vinyl.interop.player :as player]
   [ol.vinyl.util :as util]))

(defn ensure-not-released! [{:ol.vinyl.impl/keys [state_]
                             :as i}]
  (when (= @state_ :released)
    (throw (ex-info "Player has been released and cannot be used anymore." {}))))

(defn notify-subs [subscriptions event]
  (doseq [[sub-id {:keys [pred cb]}] subscriptions
          :when (pred event)]
    (util/thread
      (try
        (cb event)
        (catch Exception e
          (tap> [:error "Uncaught exception in subscriber"
                 {:sub-id sub-id
                  :event-type (:event event)
                  :error (.getMessage e)}]))))))

(defn dispatch-control-event [instance e]
  (ensure-not-released! instance)
  (if (cmd/native? e)
    (player/run-on-player-thread (:ol.vinyl.impl/player instance)
                                 (fn []
                                   (try
                                     (ensure-not-released! instance)
                                     (cmd/dispatch instance e)
                                     (catch Exception e
                                       (tap> [:error "Error processing native control event" e])))))
    (cmd/dispatch instance e)))

(defn start-control-loop! [{:ol.vinyl.impl/keys [<control <close] :as instance}]
  (util/thread
    (let [halt* (promise)]
      (loop []
        (try
          (async/alt!! [<close] (do (async/close! <control) (async/close! <close) (deliver halt* :halt))
                       [<control] ([event]
                                   (dispatch-control-event instance event)))

          (catch Exception e
            (tap> [:error "Error processing control event" e])))
        (when-not (realized? halt*)
          (recur))))))

(defn start-event-loop! [{:ol.vinyl.impl/keys [player <close state_] :as _instance}]
  (let [<events (:vlc/<events player)]
    (util/thread
      (let [halt_ (promise)]
        (loop []
          (try
            (async/alt!! [<close] (do (async/close! <events) (async/close! <close) (deliver halt_ :halt))
                         [<events] ([event]
                                    (notify-subs (:subscriptions @state_) event)))
            (catch Exception e
              (tap> [:error "Error processing VLC event" e])))
          (when-not (realized? halt_)
            (recur)))))))

(defn dispatch-event!
  "Puts a control event onto the internal event channel for processing.

  This is the entry point for all user-initiated commands. It performs a
  non-blocking put to the channel."
  [{:ol.vinyl.impl/keys [<control] :as i} event]
  (ensure-not-released! i)
  (let [event (cmd/resolve-alias event)]
    (cmd/ensure-valid! event)
    (async/put! <control event)))

(defn normalize-event-predicate
  "Normalizes the event predicate into a function that can be used to filter events.

    - If `event-pred` is a keyword, returns a function that checks for that event type.
    - If `event-pred` is a set of keywords, returns a function that checks if the event type is in the set.
    - If `event-pred` is already a function, returns it as-is."
  [event-pred]
  (cond
    (keyword? event-pred) #(= (:event %) event-pred)
    (set? event-pred) #(contains? event-pred (:event %))
    (fn? event-pred) event-pred
    :else (throw (ex-info "Invalid event predicate" {:event-pred event-pred}))))

(defn subscribe-impl!
  [{:ol.vinyl.impl/keys [state_]} event-pred callback-fn]
  (let [subscription-id (java.util.UUID/randomUUID)
        pred-fn (normalize-event-predicate event-pred)]
    (swap! state_ assoc-in [:subscriptions subscription-id] {:pred pred-fn :cb callback-fn})
    subscription-id))

(defn unsubscribe-impl!
  [{:ol.vinyl.impl/keys [state_]} subscription-id]
  (swap! state_ update :subscriptions dissoc subscription-id))