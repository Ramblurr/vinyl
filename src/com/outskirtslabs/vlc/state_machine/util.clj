(ns com.outskirtslabs.vlc.state-machine.util
  (:require
   [fairy.box.audio.queue :as queue]
   [clojure.tools.logging :as log]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :refer [data-model script-fn state on-entry on-exit transition]]
   [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as loop]
   [com.fulcrologic.statecharts.runtime :as runtime]
   [com.fulcrologic.statecharts.simple :as simple]))

(defn send-event!
  "Send an event to a player instance"
  [player event-name & [event-data]]
  (let [{:keys [env session-id]} player]
    (log/info "Sending event to" session-id ":" event-name event-data)
    (simple/send! env {:target session-id
                       :event event-name
                       :data event-data})))

(defn get-state
  "Get current state of a player instance"
  [player]
  (let [{:keys [env session-id]} player]
    {:state (runtime/current-configuration env session-id)
     :data (runtime/session-data env session-id)}))

(defn create-player!
  "Create a new player instance.
  Returns a map with :env, :session-id, :event-loop-running?, and :shutdown-promise keys.
  The shutdown-promise will be delivered with :stopped when the player reaches the stopped state."
  []
  (let [env (simple/simple-env)
        session-id (keyword (str "player-" (System/currentTimeMillis)))
        event-loop-running? (loop/run-event-loop! env 100)]
    (simple/register! env ::player-chart player-chart)
    ;; Need to set the shutdown promise in the initial data
    (simple/start! env ::player-chart session-id)
    (log/info "Player created with session-id:" session-id)
    {:env env
     :session-id session-id
     :event-loop-running? event-loop-running?}))

(defn stop-player!
  "Stop and cleanup a player instance. Returns a promise that will be delivered with nil on successful shutdown or an error if it fails."
  [player]
  (let [{:keys [env session-id event-loop-running?]} player
        result (promise)]
    (future
      (log/info "Sending shutdown event to player:" session-id)
      (simple/send! env {:target session-id :event :shutdown})
      (let [{:keys [shutdown-promise]} (:data (get-state player))]
        (try
          (deref shutdown-promise)
          (log/info "Player shutdown successfully")
          (deliver result nil)
          (catch Throwable e
            (deliver result e)
            (log/error e "Error waiting for shutdown promise"))
          (finally
            (reset! event-loop-running? false)
            (log/info "Event loop stopped for player:" session-id)))))
    result))
