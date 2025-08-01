(ns com.outskirtslabs.vlc.state-machine
  (:require
   [fairy.box.audio.queue :as queue]
   [clojure.tools.logging :as log]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :refer [data-model script-fn state on-entry on-exit transition]]
   [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as loop]
   [com.fulcrologic.statecharts.runtime :as runtime]
   [com.fulcrologic.statecharts.simple :as simple]))

(def player-chart
  "Audio player state machine definition.

  States:
  - :idle       - Initial state, no media loaded or activity
  - :waiting    - Waiting for media item to be prepared (parsed/validated)
  - :loading    - Media is being loaded into the player
  - :playing    - Media is actively playing
  - :paused     - Playback is paused, can be resumed
  - :stopped    - Terminal state, all resources freed, playback ended

  State Transitions:
  :idle → :waiting     [event: :load]
                       Triggered when loading new media. Stores item in :loading-item.

  :idle → :stopped     [event: :shutdown]
                       Clean shutdown from idle state.

  :waiting → :loading  [event: :item-ready]
                       Media preparation complete, ready to load into player.

  :waiting → :idle     [event: :item-error]
                       Media preparation failed, return to idle.

  :waiting → :stopped  [event: :shutdown]
                       Clean shutdown while waiting.

  :loading → :playing  [event: :media-ready]
                       Media successfully loaded, start playback.

  :loading → :idle     [event: :load-error]
                       Media loading failed, return to idle.

  :loading → :stopped  [event: :shutdown]
                       Clean shutdown while loading (releases resources).

  :playing → :paused   [event: :pause]
                       Pause active playback.

  :playing → :stopped  [event: :stop]
                       Stop playback, free all resources. Terminal state.

  :playing → :loading  [event: :next]
                       Load next item in queue.

  :playing → :idle     [event: :error]
                       Playback error occurred, cleanup and return to idle.

  :paused → :playing   [event: :play]
                       Resume paused playback.

  :paused → :stopped   [event: :stop]
                       Stop from paused state. Terminal state.

  Data Model:
  - :queue          - Our play queue (see fairy.box.audio.queue)
  - :loading-item   - Item being prepared in :waiting state
  - :mock-media     - Simulated media resource (added in :loading)
  - :playback-started-at - Timestamp when playback started

  Resource Management:
  - Media resources are acquired in :loading entry
  - Resources are released in :loading exit (if not going to :playing)
  - Resources are always released when exiting :playing
  - :stopped state frees all resources on entry and has no exit transitions
  - :shutdown event ensures clean termination from any non-playing state"
  (chart/statechart {}
                    (data-model {:expr {:queue (queue/create-queue) :shutdown-promise (promise)}})
                    (state {:id :idle}
                           (on-entry {}
                                     (script-fn [_ _]
                                                (tap> "Entering :idle state")
                                                []))
                           (transition {:event  :load
                                        :target :waiting}
                                       (script-fn [_env {:keys [_event] :as _data}]
                                                  (log/info "Load event data:" _event)
                                                  (if-let [item (get-in _event [:data :item])]
                                                    [(ops/assign :loading-item item)]
                                                    [])))
                           (transition {:event  :shutdown
                                        :target :stopped}))

                    (state {:id          :waiting
                            :description "Waiting for item to be ready (parsing/preparing)"}
                           (transition {:event  :item-ready
                                        :target :loading})
                           (transition {:event  :item-error
                                        :target :idle})
                           (transition {:event  :shutdown
                                        :target :stopped}))

                    (state {:id          :loading
                            :description "Loading media into player"}
                           (on-entry {}
                                     (script-fn [_env _]
                                                (tap> "Entering :loading state - acquiring resources")
                                                ;; Simulate resource acquisition
                                                [(ops/assign :mock-media {:id (random-uuid) :loaded true})]))
                           (on-exit {}
                                    (script-fn [env _]
                                               (log/info "Exiting :loading state")
                                               ;; Only cleanup if not transitioning to playing
                                               (if (not= :playing (-> env :data :_next-state))
                                                 (do
                                                   (log/info "Cleaning up mock media resource")
                                                   [(ops/assign :mock-media nil)])
                                                 [])))
                           (transition {:event  :media-ready
                                        :target :playing})
                           (transition {:event  :load-error
                                        :target :idle}
                                       (script-fn [_ {:keys [_event] :as data}]
                                                  (log/error "Media load failed:" (get-in data [:data :error]))
                                                  []))
                           (transition {:event  :shutdown
                                        :target :stopped}))

                    (state {:id :playing}
                           (on-entry {}
                                     (script-fn [_ _]
                                                (log/info "Entering :playing state - playback started")
                                                [(ops/assign :playback-started-at (System/currentTimeMillis))]))
                           (on-exit {}
                                    (script-fn [env _]
                                               (log/info "Exiting :playing state - stopping playback")
                                               ;; Always cleanup media when leaving playing state
                                               (when-let [media (-> env :data :mock-media)]
                                                 (log/info "Releasing media resource:" (:id media)))
                                               [(ops/assign :mock-media nil)
                                                (ops/assign :playback-started-at nil)]))
                           (transition {:event  :pause
                                        :target :paused})
                           (transition {:event  :stop
                                        :target :stopped})
                           (transition {:event  :next
                                        :target :loading}
                                       (script-fn [_env _]
                                                  (log/info "Advancing to next item")
                                                  []))
                           (transition {:event  :error
                                        :target :idle}
                                       (script-fn [_env {:keys [_event] :as data}]
                                                  (log/error "Playback error:" (get-in data [:data :error]))
                                                  [])))

                    (state {:id :paused}
                           (on-entry {}
                                     (script-fn [_env _]
                                                (log/info "Entering :paused state")
                                                []))
                           (transition {:event  :play
                                        :target :playing})
                           (transition {:event  :stop
                                        :target :stopped}))

                    (state {:id          :stopped
                            :description "Terminal state - all resources freed"}
                           (on-entry {}
                                     (script-fn [env data]
                                                (tap> [:stopped  "Entering :stopped state" :env env :data data])
                                                ;; TODO free resources here
                                                (deliver (:shutdown-promise data) nil)
                                                [(ops/assign :mock-media nil)
                                                 (ops/assign :loading-item nil)
                                                 (ops/assign :playback-started-at nil)])))))
