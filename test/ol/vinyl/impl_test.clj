;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.impl-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [ol.vinyl :as vinyl]
   [ol.vinyl.bus :as bus]
   [ol.vinyl.impl :as impl]
   [ol.vinyl.interop.api :as api]
   [ol.vinyl.interop.parsing :as parsing]
   [ol.vinyl.lifecycle :as lifecycle])
  (:import
   [java.lang.ref WeakReference]))

(defn- create-with-runtime-var []
  (ns-resolve 'ol.vinyl.impl 'create-player-with-runtime!))

(defn- thrown [f]
  (try
    (f)
    nil
    (catch Throwable t
      t)))

(defn- impl-runtime
  ([events_]
   (impl-runtime events_ {}))
  ([events_ {:keys [deadlines_
                    drain-native-fn
                    drain-timeout-ms
                    fail-at
                    join-result
                    native-player-value
                    playback-release-error
                    release-gate]
             :or {drain-timeout-ms 1000
                  join-result true
                  native-player-value {:native :player}}}]
   (let [record! (fn [event value]
                   (swap! events_ conj event)
                   (when (= fail-at event)
                     (throw (ex-info (str "Failed at " event) {:event event})))
                   value)]
     {:create-native-player! (fn [_events _factory]
                               (record! :create-native-player
                                        native-player-value))
      :drain-native-player! (fn [native-player deadline-nanos]
                              (when deadlines_
                                (swap! deadlines_ conj
                                       [:drain-native-player deadline-nanos]))
                              (record! :drain-native-player nil)
                              (when drain-native-fn
                                (drain-native-fn native-player deadline-nanos)))
      :drain-timeout-ms drain-timeout-ms
      :join-loop! (fn [loop-name _thread deadline-nanos]
                    (when deadlines_
                      (swap! deadlines_ conj [loop-name deadline-nanos]))
                    (record! [:join loop-name] join-result))
      :playback-init! (fn [_instance]
                        (record! :playback-init nil))
      :playback-release! (fn [_instance]
                           (record! :playback-release nil)
                           (when playback-release-error
                             (throw playback-release-error)))
      :release-native-player!
      (fn release-native-player!
        ([native-player]
         (release-native-player! native-player nil))
        ([_native-player deadline-nanos]
         (when deadlines_
           (swap! deadlines_ conj [:release-native-player deadline-nanos]))
         (swap! events_ conj :release-native-player-start)
         (when release-gate
           @release-gate)
         (record! :release-native-player nil)))
      :start-control-loop! (fn [_instance]
                             (record! :start-control-loop :control-loop))
      :start-event-loop! (fn [_instance]
                           (record! :start-event-loop :event-loop))})))

(defn- create-with-runtime [runtime]
  ((create-with-runtime-var) {} runtime))

(deftest high-level-construction-rolls-back-started-resources-test
  (if (create-with-runtime-var)
    (let [cases [{:fail-at :playback-init
                  :expected [:create-native-player :playback-init
                             :release-native-player-start :release-native-player]}
                 {:fail-at :start-control-loop
                  :expected [:create-native-player :playback-init
                             :start-control-loop :playback-release
                             :release-native-player-start :release-native-player]}
                 {:fail-at :start-event-loop
                  :expected [:create-native-player :playback-init
                             :start-control-loop :start-event-loop
                             [:join :control] :playback-release
                             :release-native-player-start :release-native-player]}]
          outcomes
          (mapv (fn [{:keys [expected fail-at]}]
                  (let [events_ (atom [])
                        error (thrown #(create-with-runtime
                                        (impl-runtime events_ {:fail-at fail-at})))]
                    {:error-event (:event (ex-data error))
                     :events @events_
                     :expected expected}))
                cases)]
      (is (= (mapv (fn [{:keys [expected fail-at]}]
                     {:error-event fail-at
                      :events expected
                      :expected expected})
                   cases)
             outcomes)))
    (is false "The high-level transactional constructor is not implemented.")))

(deftest concurrent-high-level-release-runs-native-release-once-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          release-gate (promise)
          instance (create-with-runtime
                    (impl-runtime events_ {:release-gate release-gate}))
          first-result_ (future (impl/release-player-impl! instance))]
      (loop [attempt 0]
        (when (and (not (some #{:release-native-player-start} @events_))
                   (< attempt 100))
          (Thread/sleep 5)
          (recur (inc attempt))))
      (let [second-result_ (future (impl/release-player-impl! instance))]
        (deliver release-gate true)
        (is (= {:first nil
                :native-releases 1
                :second nil}
               {:first (deref first-result_ 1000 ::timeout)
                :native-releases (count (filter #{:release-native-player} @events_))
                :second (deref second-result_ 1000 ::timeout)}))))
    (is false "The high-level transactional constructor is not implemented.")))

(deftest release-waits-for-an-admitted-query-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          query-entered_ (promise)
          query-finish_ (promise)
          instance (create-with-runtime (impl-runtime events_))]
      (with-redefs [api/get-volume (fn [_player]
                                     (deliver query-entered_ true)
                                     @query-finish_
                                     73)]
        (let [query-result_ (future (impl/get-volume-impl instance))]
          @query-entered_
          (let [release-result_ (future (impl/release-player-impl! instance))]
            (is (= ::query-active
                   (deref release-result_ 50 ::query-active)))
            (is (not (some #{:release-native-player-start} @events_)))
            (deliver query-finish_ true)
            (is (= {:query 73
                    :release nil
                    :release-count 1}
                   {:query (deref query-result_ 1000 ::timeout)
                    :release (deref release-result_ 1000 ::timeout)
                    :release-count (count (filter #{:release-native-player}
                                                  @events_))}))))))
    (is false "The high-level transactional constructor is not implemented.")))

(deftest query-after-release-begins-never-reaches-native-boundary-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          release-gate (promise)
          native-calls_ (atom 0)
          instance (create-with-runtime
                    (impl-runtime events_ {:release-gate release-gate}))
          release-result_ (future (impl/release-player-impl! instance))]
      (loop [attempt 0]
        (when (and (not (some #{:release-native-player-start} @events_))
                   (< attempt 100))
          (Thread/sleep 5)
          (recur (inc attempt))))
      (with-redefs [api/get-volume (fn [_player]
                                     (swap! native-calls_ inc))]
        (let [error (thrown #(impl/get-volume-impl instance))]
          (deliver release-gate true)
          @release-result_
          (is (= {:error-type :ol.vinyl.lifecycle/not-active
                  :native-calls 0}
                 {:error-type (:type (ex-data error))
                  :native-calls @native-calls_})))))
    (is false "The high-level transactional constructor is not implemented.")))

(deftest metadata-promise-holds-use-until-it-settles-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          parser-result_ (promise)
          instance (create-with-runtime (impl-runtime events_))]
      (with-redefs [parsing/parse-meta (fn [_paths _opts]
                                         parser-result_)]
        (let [result_ (impl/parse-meta instance ["test:track"])
              release-result_ (future (impl/release-player-impl! instance))]
          (is (= ::parsing (deref release-result_ 50 ::parsing)))
          (is (not (some #{:release-native-player-start} @events_)))
          (deliver parser-result_ [{:mrl "test:track"}])
          (is (= {:parse-result [{:mrl "test:track"}]
                  :release-result nil}
                 {:parse-result (deref result_ 1000 ::timeout)
                  :release-result (deref release-result_ 1000 ::timeout)})))))
    (is false "The high-level transactional constructor is not implemented.")))

(deftest drain-timeout-does-not-call-native-release-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          query-entered_ (promise)
          query-finish_ (promise)
          instance (create-with-runtime
                    (impl-runtime events_ {:drain-timeout-ms 50}))]
      (with-redefs [api/get-volume (fn [_player]
                                     (deliver query-entered_ true)
                                     @query-finish_
                                     50)]
        (let [query-result_ (future (impl/get-volume-impl instance))]
          @query-entered_
          (let [error (thrown #(impl/release-player-impl! instance))]
            (deliver query-finish_ true)
            @query-result_
            (is (= {:error-type :ol.vinyl.lifecycle/drain-timeout
                    :native-release-starts 0}
                   {:error-type (:type (ex-data error))
                    :native-release-starts
                    (count (filter #{:release-native-player-start} @events_))}))))))
    (is false "The high-level transactional constructor is not implemented.")))

(deftest queue-inspection-is-rejected-after-release-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          instance (create-with-runtime (impl-runtime events_))]
      (impl/release-player-impl! instance)
      (let [error (thrown #(vinyl/list-queue instance))]
        (is (= :ol.vinyl.lifecycle/not-active
               (:type (ex-data error))))))
    (is false "The high-level transactional constructor is not implemented.")))

(deftest construction-loop-timeout-does-not-release-native-player-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          error (thrown
                 #(create-with-runtime
                   (impl-runtime events_
                                 {:fail-at :start-event-loop
                                  :join-result false})))]
      (is (= {:events [:create-native-player
                       :playback-init
                       :start-control-loop
                       :start-event-loop
                       [:join :control]
                       :playback-release]
              :primary-event :start-event-loop
              :suppressed-types [:ol.vinyl.impl/loop-drain-timeout]}
             {:events @events_
              :primary-event (:event (ex-data error))
              :suppressed-types (mapv (comp :type ex-data)
                                      (.getSuppressed ^Throwable error))})))
    (is false "The high-level transactional constructor is not implemented.")))

(defn- collected? [^WeakReference reference]
  (loop [attempt 0]
    (cond
      (nil? (.get reference)) true
      (>= attempt 100) false
      :else (do
              (System/gc)
              (let [_pressure (object-array 10000)]
                (Thread/sleep 10))
              (recur (inc attempt))))))

(defn- failed-native-resources []
  (some-> (ns-resolve 'ol.vinyl.interop.player
                      'failed-native-resources_)
          var-get))

(defn- release-with-playback-error [playback-error]
  (let [events_ (atom [])
        native-player (Object.)
        instance
        (create-with-runtime
         (impl-runtime events_
                       {:native-player-value native-player
                        :playback-release-error playback-error}))
        error (thrown #(impl/release-player-impl! instance))]
    {:events @events_
     :primary? (identical? playback-error error)
     :reference (WeakReference. native-player)}))

(deftest successful-native-release-is-not-quarantined-for-playback-cleanup-error-test
  (if (and (create-with-runtime-var) (failed-native-resources))
    (let [quarantine (failed-native-resources)
          playback-error (ex-info "Playback cleanup failed." {})]
      (.clear ^java.util.Set quarantine)
      (let [{:keys [events primary? reference]}
            (release-with-playback-error playback-error)]
        (is (= {:collected? true
                :events [:create-native-player
                         :playback-init
                         :start-control-loop
                         :start-event-loop
                         [:join :control]
                         [:join :event]
                         :drain-native-player
                         :playback-release
                         :release-native-player-start
                         :release-native-player]
                :primary? true}
               {:collected? (collected? reference)
                :events events
                :primary? primary?})))
      (.clear ^java.util.Set quarantine))
    (is false "The failed native resource quarantine is not implemented.")))

(deftest release-phases-share-one-monotonic-deadline-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          deadlines_ (atom [])
          instance (create-with-runtime
                    (impl-runtime events_ {:deadlines_ deadlines_}))]
      (impl/release-player-impl! instance)
      (is (= {:phases #{:control
                        :drain-native-player
                        :event
                        :release-native-player}
              :unique-deadlines 1}
             {:phases (into #{} (map first) @deadlines_)
              :unique-deadlines (count (into #{} (map second) @deadlines_))})))
    (is false "The high-level transactional constructor is not implemented.")))

(deftest native-executor-drains-before-exclusive-use-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          first-check-passed_ (promise)
          continue-check_ (promise)
          task-result_ (promise)
          real-ensure-active! lifecycle/ensure-active!
          instance
          (create-with-runtime
           (impl-runtime
            events_
            {:drain-native-fn
             (fn [_player _deadline-nanos]
               (deliver continue-check_ true)
               @task-result_)}))
          checks_ (atom 0)]
      (with-redefs [lifecycle/ensure-active!
                    (fn [controller]
                      (real-ensure-active! controller)
                      (when (= 1 (swap! checks_ inc))
                        (deliver first-check-passed_ true)
                        @continue-check_))]
        (let [task_ (future
                      (deliver
                       task-result_
                       (thrown
                        #(lifecycle/with-use
                           (::impl/lifecycle instance)
                           (fn [] :used)))))
              _ @first-check-passed_
              release_ (future (impl/release-player-impl! instance))
              release-result (deref release_ 1000 ::timeout)]
          (deliver continue-check_ true)
          @task_
          (let [event-indexes (into {}
                                    (map-indexed (fn [index event]
                                                   [event index]))
                                    @events_)]
            (is (= {:drained-before-release? true
                    :release-result nil
                    :task-error-type :ol.vinyl.lifecycle/not-active}
                   {:drained-before-release?
                    (< (get event-indexes :drain-native-player)
                       (get event-indexes :release-native-player-start))
                    :release-result release-result
                    :task-error-type (:type (ex-data @task-result_))}))))))
    (is false "The high-level transactional constructor is not implemented.")))

(deftest parse-meta-returns-promise-before-worker-settles-test
  (let [controller (lifecycle/create)
        continue_ (promise)
        entered_ (promise)
        instance {::impl/lifecycle controller
                  ::impl/player {:vlc/media-player-factory :factory}}]
    (with-redefs [parsing/parse-meta
                  (fn [_paths _opts]
                    (deliver entered_ true)
                    @continue_
                    (delay [{:mrl "test:track"}]))]
      (let [result_ (impl/parse-meta instance ["test:track"])]
        @entered_
        (is (not (realized? result_)))
        (deliver continue_ true)
        (is (= [{:mrl "test:track"}]
               (deref result_ 1000 ::timeout)))))))

(deftest release-from-subscriber-callback-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          callback-result_ (promise)
          instance (create-with-runtime (impl-runtime events_))]
      (vinyl/subscribe!
       instance
       (fn [_event]
         (deliver callback-result_
                  (thrown #(vinyl/release-player! instance)))))
      (bus/notify-subs (:subscriptions @(::impl/state_ instance))
                       {:ol.vinyl/event :test/event})
      (let [callback-result (deref callback-result_ 1000 ::timeout)
            repeated-result (vinyl/release-player! instance)]
        (is (= {:callback-result nil
                :native-releases 1
                :repeated-result nil}
               {:callback-result callback-result
                :native-releases
                (count (filter #{:release-native-player} @events_))
                :repeated-result repeated-result}))))
    (is false "The high-level transactional constructor is not implemented.")))

(deftest release-from-event-predicate-does-not-join-event-loop-thread-test
  (if-let [runtime-var (ns-resolve 'ol.vinyl.impl 'native-runtime)]
    (let [events_ (atom [])
          release-result_ (promise)
          native-join-loop! (:join-loop! (var-get runtime-var))
          runtime (assoc (impl-runtime events_ {:drain-timeout-ms 200})
                         :join-loop! native-join-loop!
                         :start-control-loop! bus/start-control-loop!
                         :start-event-loop! bus/start-event-loop!)
          instance (create-with-runtime runtime)]
      (vinyl/subscribe!
       instance
       (fn [_event])
       (fn [_event]
         (deliver release-result_
                  (thrown #(vinyl/release-player! instance)))
         false))
      (async/>!! (::impl/<events instance)
                 {:ol.vinyl/event :test/event})
      (let [release-result (deref release-result_ 2000 ::timeout)]
        (is (= {:native-releases 1
                :release-result nil}
               {:native-releases
                (count (filter #{:release-native-player} @events_))
                :release-result release-result}))))
    (is false "The high-level native runtime is not implemented.")))

(deftest interrupted-loop-join-preserves-interruption-and-stops-waiting-test
  (if-let [runtime-var (ns-resolve 'ol.vinyl.impl 'native-runtime)]
    (let [events_ (atom [])
          finish-loops_ (promise)
          joins_ (atom [])
          native-join-loop! (:join-loop! (var-get runtime-var))
          runtime
          (assoc (impl-runtime events_ {:drain-timeout-ms 100})
                 :join-loop!
                 (fn [loop-name thread deadline-nanos]
                   (swap! joins_ conj loop-name)
                   (native-join-loop! loop-name thread deadline-nanos))
                 :start-control-loop!
                 (fn [_instance]
                   (Thread/startVirtualThread
                    (fn [] @finish-loops_)))
                 :start-event-loop!
                 (fn [_instance]
                   (Thread/startVirtualThread
                    (fn [] @finish-loops_))))
          instance (create-with-runtime runtime)
          result_
          (future
            (.interrupt (Thread/currentThread))
            (try
              (let [error (thrown #(vinyl/release-player! instance))]
                {:error-class (class error)
                 :interrupted? (.isInterrupted (Thread/currentThread))
                 :joins @joins_
                 :native-releases
                 (count (filter #{:release-native-player} @events_))})
              (finally
                (Thread/interrupted))))]
      (try
        (is (= {:error-class InterruptedException
                :interrupted? true
                :joins [:control]
                :native-releases 0}
               (deref result_ 2000 ::timeout)))
        (finally
          (deliver finish-loops_ true))))
    (is false "The high-level native runtime is not implemented.")))

(deftest empty-parse-meta-result-is-already-realized-test
  (let [caller-thread (Thread/currentThread)
        continue-worker_ (promise)
        controller (lifecycle/create)
        parser-result_ (doto (promise) (deliver []))
        worker-entered_ (promise)
        instance {::impl/lifecycle controller
                  ::impl/player {:vlc/media-player-factory :factory}}]
    (with-redefs [parsing/parse-meta
                  (fn [_paths _opts]
                    (when-not (identical? caller-thread
                                          (Thread/currentThread))
                      (deliver worker-entered_ true)
                      @continue-worker_)
                    parser-result_)]
      (let [result_ (impl/parse-meta instance [])]
        (try
          (is (realized? result_))
          (finally
            (deliver continue-worker_ true)))))))

(deftest parse-meta-rejects-a-malformed-player-clearly-test
  (let [error (thrown #(impl/parse-meta {} ["test:track"]))]
    (is (= {:error-type :ol.vinyl/invalid-player
            :npe? false}
           {:error-type (:type (ex-data error))
            :npe? (instance? NullPointerException error)}))))