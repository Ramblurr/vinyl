;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.interop.player-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [ol.vinyl.interop.player :as player])
  (:import
   [java.lang.ref WeakReference]
   [java.util.concurrent RejectedExecutionException]))

(defn- create-with-runtime-var []
  (ns-resolve 'ol.vinyl.interop.player 'create-audio-player-with-runtime!))

(defn- event-runtime
  ([events_]
   (event-runtime events_ {}))
  ([events_ {:keys [drain-timeout-ms
                    errors
                    factory-value
                    fail-at
                    media-listener-value
                    player-listener-value
                    player-value
                    release-gate
                    state
                    submit!]
             :or {drain-timeout-ms 50
                  errors {}
                  factory-value :owned-factory
                  media-listener-value :media-listener
                  player-listener-value :player-listener
                  player-value :media-player
                  state :stopped}}]
   (let [record! (fn [event value]
                   (swap! events_ conj event)
                   (when (= fail-at event)
                     (throw (ex-info (str "Failed at " event) {:event event})))
                   (when-let [error (get errors event)]
                     (throw error))
                   value)]
     {:add-media-listener! (fn [_player _listener]
                             (record! :add-media-listener nil))
      :add-player-listener! (fn [_player _listener]
                              (record! :add-player-listener nil))
      :drain-timeout-ms drain-timeout-ms
      :new-factory! (fn []
                      (record! :new-factory factory-value))
      :new-media-listener! (fn [_callback]
                             (record! :new-media-listener media-listener-value))
      :new-player! (fn [_factory]
                     (record! :new-player player-value))
      :new-player-listener! (fn [_callback]
                              (record! :new-player-listener
                                       player-listener-value))
      :new-stop-listener! (fn [_callback] :stop-listener)
      :player-state (fn [_player] state)
      :release-factory! (fn [_factory]
                          (record! :release-factory nil))
      :release-player! (fn [_player]
                         (when release-gate
                           (swap! events_ conj :release-player-start)
                           @release-gate)
                         (record! :release-player nil))
      :remove-media-listener! (fn [_player _listener]
                                (record! :remove-media-listener nil))
      :remove-player-listener! (fn [_player _listener]
                                 (record! :remove-player-listener nil))
      :stop! (fn [_player]
               (record! :stop nil))
      :stop-timeout-ms 10
      :stopped? #(contains? #{:error :stopped} %)
      :submit! (or submit!
                   (fn [_player task]
                     (task)))})))

(defn- create-with-runtime [factory runtime]
  ((create-with-runtime-var) (async/chan 1) factory runtime))

(defn- thrown [f]
  (try
    (f)
    nil
    (catch Throwable t
      t)))

(deftest partial-construction-rolls-back-in-reverse-order-test
  (if (create-with-runtime-var)
    (let [cases
          [{:fail-at :new-player
            :expected [:new-factory :new-player :release-factory]}
           {:fail-at :new-player-listener
            :expected [:new-factory :new-player :new-player-listener
                       :release-player :release-factory]}
           {:fail-at :new-media-listener
            :expected [:new-factory :new-player :new-player-listener
                       :new-media-listener :release-player :release-factory]}
           {:fail-at :add-player-listener
            :expected [:new-factory :new-player :new-player-listener
                       :new-media-listener :add-player-listener
                       :remove-player-listener :release-player :release-factory]}
           {:fail-at :add-media-listener
            :expected [:new-factory :new-player :new-player-listener
                       :new-media-listener :add-player-listener
                       :add-media-listener :remove-media-listener
                       :remove-player-listener :release-player :release-factory]}]
          outcomes
          (mapv (fn [{:keys [expected fail-at]}]
                  (let [events_ (atom [])
                        error (thrown #(create-with-runtime
                                        nil
                                        (event-runtime events_ {:fail-at fail-at})))]
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
    (is false "The transactional player constructor is not implemented.")))

(deftest supplied-factory-is-never-released-during-construction-failure-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          error (thrown #(create-with-runtime
                          :supplied-factory
                          (event-runtime events_ {:fail-at :new-player})))]
      (is (= {:error-event :new-player
              :events [:new-player]}
             {:error-event (:event (ex-data error))
              :events @events_})))
    (is false "The transactional player constructor is not implemented.")))

(deftest rollback-preserves-primary-error-and-skips-factory-after-player-release-failure-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          construction-error (ex-info "Attachment failed." {:kind :construction})
          detach-error (ex-info "Detach failed." {:kind :detach})
          release-error (ex-info "Player release failed." {:kind :release})
          error (thrown #(create-with-runtime
                          nil
                          (event-runtime
                           events_
                           {:errors {:add-media-listener construction-error
                                     :release-player release-error
                                     :remove-media-listener detach-error}})))]
      (is (= {:events [:new-factory :new-player :new-player-listener
                       :new-media-listener :add-player-listener
                       :add-media-listener :remove-media-listener
                       :remove-player-listener :release-player]
              :primary? true
              :suppressed [detach-error release-error]}
             {:events @events_
              :primary? (identical? construction-error error)
              :suppressed (vec (.getSuppressed ^Throwable error))})))
    (is false "The transactional player constructor is not implemented.")))

(deftest release-is-once-only-for-repeated-and-concurrent-callers-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          release-gate (promise)
          interop-player (create-with-runtime
                          nil
                          (event-runtime events_ {:release-gate release-gate}))]
      (reset! events_ [])
      (let [first-result_ (future (player/release! interop-player))
            release-started? (loop [attempt 0]
                               (if (or (some #{:release-player-start} @events_)
                                       (>= attempt 100))
                                 (some #{:release-player-start} @events_)
                                 (do
                                   (Thread/sleep 5)
                                   (recur (inc attempt)))))
            second-result_ (future (player/release! interop-player))]
        (deliver release-gate true)
        (let [first-result (deref first-result_ 1000 ::timeout)
              second-result (deref second-result_ 1000 ::timeout)]
          (is (= {:factory-releases 1
                  :first nil
                  :player-releases 1
                  :release-started? true
                  :second nil}
                 {:factory-releases (count (filter #{:release-factory} @events_))
                  :first first-result
                  :player-releases (count (filter #{:release-player} @events_))
                  :release-started? (boolean release-started?)
                  :second second-result})))))
    (is false "The transactional player constructor is not implemented.")))

(deftest release-failure-is-cached-and-owned-factory-is-not-released-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          release-error (ex-info "Player release failed." {})
          interop-player (create-with-runtime
                          nil
                          (event-runtime events_
                                         {:errors {:release-player release-error}}))]
      (reset! events_ [])
      (let [first-error (thrown #(player/release! interop-player))
            second-error (thrown #(player/release! interop-player))]
        (is (= {:factory-releases 0
                :first-error? true
                :player-releases 1
                :same-error? true}
               {:factory-releases (count (filter #{:release-factory} @events_))
                :first-error? (identical? release-error first-error)
                :player-releases (count (filter #{:release-player} @events_))
                :same-error? (identical? first-error second-error)}))))
    (is false "The transactional player constructor is not implemented.")))

(deftest executor-drain-timeout-does-not-release-native-resources-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          interop-player (create-with-runtime
                          nil
                          (event-runtime
                           events_
                           {:submit! (fn [_player _task]
                                       nil)}))]
      (reset! events_ [])
      (let [error (thrown #(player/release! interop-player))]
        (is (= {:error-type :ol.vinyl.interop.player/drain-timeout
                :factory-releases 0
                :player-releases 0}
               {:error-type (:type (ex-data error))
                :factory-releases (count (filter #{:release-factory} @events_))
                :player-releases (count (filter #{:release-player} @events_))}))))
    (is false "The transactional player constructor is not implemented.")))

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

(deftest listener-cleanup-failure-does-not-skip-native-release-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          detach-error (AssertionError. "Listener detach failed.")
          interop-player
          (create-with-runtime
           nil
           (event-runtime
            events_
            {:errors {:remove-player-listener detach-error}}))]
      (reset! events_ [])
      (let [error (thrown #(player/release! interop-player))]
        (is (= {:events [:remove-player-listener
                         :remove-media-listener
                         :release-player
                         :release-factory]
                :primary? true}
               {:events @events_
                :primary? (identical? detach-error error)}))))
    (is false "The transactional player constructor is not implemented.")))

(deftest normal-release-never-releases-a-supplied-factory-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          interop-player (create-with-runtime
                          :supplied-factory
                          (event-runtime events_))]
      (reset! events_ [])
      (player/release! interop-player)
      (is (= [:remove-player-listener
              :remove-media-listener
              :release-player]
             @events_)))
    (is false "The transactional player constructor is not implemented.")))

(deftest uncertain-release-keeps-native-player-strongly-reachable-test
  (if (and (create-with-runtime-var) (failed-native-resources))
    (let [quarantine (failed-native-resources)
          release-error (ex-info "Native release failed." {})]
      (.clear ^java.util.Set quarantine)
      (let [reference
            (let [native-player (Object.)
                  events_ (atom [])
                  interop-player
                  (create-with-runtime
                   nil
                   (event-runtime
                    events_
                    {:errors {:release-player release-error}
                     :player-value native-player}))]
              (thrown #(player/release! interop-player))
              (WeakReference. native-player))]
        (is (false? (collected? reference)))
        (.clear ^java.util.Set quarantine)))
    (is false "The failed native resource quarantine is not implemented.")))

(deftest cleanup-task-timeout-does-not-release-native-resources-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          submissions_ (atom 0)
          interop-player
          (create-with-runtime
           nil
           (event-runtime
            events_
            {:submit! (fn [_player task]
                        (when (= 1 (swap! submissions_ inc))
                          (task)))}))]
      (reset! events_ [])
      (let [error (thrown #(player/release! interop-player))]
        (is (= {:error-type :ol.vinyl.interop.player/drain-timeout
                :factory-releases 0
                :player-releases 0}
               {:error-type (:type (ex-data error))
                :factory-releases (count (filter #{:release-factory} @events_))
                :player-releases (count (filter #{:release-player} @events_))}))))
    (is false "The transactional player constructor is not implemented.")))

(deftest low-level-release-uses-one-monotonic-deadline-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          runtime
          (event-runtime
           events_
           {:drain-timeout-ms 100
            :submit! (fn [_player task]
                       (Thread/startVirtualThread
                        (fn []
                          (Thread/sleep 40)
                          (task))))})
          interop-player (create-with-runtime nil runtime)
          error (thrown #(player/release! interop-player))]
      (is (= {:error-type :ol.vinyl.interop.player/drain-timeout
              :factory-releases 0
              :player-releases 0}
             {:error-type (:type (ex-data error))
              :factory-releases (count (filter #{:release-factory} @events_))
              :player-releases (count (filter #{:release-player} @events_))})))
    (is false "The transactional player constructor is not implemented.")))

(defn- factory-failure-references [factory-error]
  (let [events_ (atom [])
        factory (Object.)
        native-player (Object.)
        interop-player
        (create-with-runtime
         nil
         (event-runtime events_
                        {:errors {:release-factory factory-error}
                         :factory-value factory
                         :player-value native-player}))
        error (thrown #(player/release! interop-player))]
    {:factory-reference (WeakReference. factory)
     :player-reference (WeakReference. native-player)
     :same-error? (identical? factory-error error)}))

(deftest factory-only-release-failure-quarantines-only-factory-test
  (if (and (create-with-runtime-var) (failed-native-resources))
    (let [quarantine (failed-native-resources)
          factory-error (ex-info "Factory release failed." {})]
      (.clear ^java.util.Set quarantine)
      (let [{:keys [factory-reference player-reference same-error?]}
            (factory-failure-references factory-error)]
        (is (= {:factory-collected? false
                :player-collected? true
                :same-error? true}
               {:factory-collected? (collected? factory-reference)
                :player-collected? (collected? player-reference)
                :same-error? same-error?})))
      (.clear ^java.util.Set quarantine))
    (is false "The failed native resource quarantine is not implemented.")))

(deftest running-player-stop-callback-only-publishes-completion-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          callback-thread_ (atom nil)
          release-thread_ (atom nil)
          remove-thread_ (atom nil)
          stop-listener_ (atom nil)
          runtime
          (assoc
           (event-runtime events_ {:state :playing})
           :add-player-listener!
           (fn [_player event-listener]
             (if (map? event-listener)
               (do
                 (reset! stop-listener_ event-listener)
                 (swap! events_ conj :add-stop-listener))
               (swap! events_ conj :add-app-listener)))
           :new-stop-listener!
           (fn [callback]
             (swap! events_ conj :new-stop-listener)
             {:callback callback})
           :release-player!
           (fn [_player]
             (reset! release-thread_ (Thread/currentThread))
             (swap! events_ conj :release-player))
           :remove-player-listener!
           (fn [_player event-listener]
             (if (map? event-listener)
               (do
                 (reset! remove-thread_ (Thread/currentThread))
                 (swap! events_ conj :remove-stop-listener))
               (swap! events_ conj :remove-app-listener)))
           :stop!
           (fn [_player]
             (Thread/startVirtualThread
              (fn []
                (reset! callback-thread_ (Thread/currentThread))
                (swap! events_ conj :callback)
                ((:callback @stop-listener_))))))
          interop-player (create-with-runtime nil runtime)]
      (reset! events_ [])
      (player/release! interop-player)
      (is (= [:new-stop-listener
              :add-stop-listener
              :callback
              :remove-stop-listener
              :remove-app-listener
              :remove-media-listener
              :release-player
              :release-factory]
             @events_))
      (is (not (identical? @callback-thread_ @remove-thread_)))
      (is (not (identical? @callback-thread_ @release-thread_))))
    (is false "The transactional player constructor is not implemented.")))

(deftest interrupted-executor-wait-does-not-release-native-resources-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          release-thread (Thread/currentThread)
          interop-player
          (create-with-runtime
           nil
           (event-runtime
            events_
            {:submit! (fn [_player _task]
                        (.interrupt release-thread))}))]
      (reset! events_ [])
      (try
        (let [error (thrown #(player/release! interop-player))]
          (is (= {:error-class InterruptedException
                  :factory-releases 0
                  :interrupted? true
                  :player-releases 0}
                 {:error-class (class error)
                  :factory-releases (count (filter #{:release-factory} @events_))
                  :interrupted? (.isInterrupted release-thread)
                  :player-releases (count (filter #{:release-player} @events_))})))
        (finally
          (Thread/interrupted))))
    (is false "The transactional player constructor is not implemented.")))

(deftest proven-executor-drain-is-not-repeated-under-exclusive-release-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          submissions_ (atom 0)
          runtime (event-runtime
                   events_
                   {:submit! (fn [_player task]
                               (swap! submissions_ inc)
                               (task))})
          interop-player (create-with-runtime nil runtime)
          deadline-nanos (+ (System/nanoTime) 1000000000)]
      (player/drain-before! interop-player deadline-nanos)
      (player/release-before! interop-player deadline-nanos)
      (is (= 3 @submissions_)))
    (is false "The transactional player constructor is not implemented.")))

(deftest cleanup-submission-failure-does-not-release-native-resources-test
  (if (create-with-runtime-var)
    (let [events_ (atom [])
          submissions_ (atom 0)
          interop-player
          (create-with-runtime
           nil
           (event-runtime
            events_
            {:submit! (fn [_player task]
                        (if (= 1 (swap! submissions_ inc))
                          (task)
                          (throw
                           (RejectedExecutionException.
                            "Executor rejected cleanup."))))}))]
      (reset! events_ [])
      (let [error (thrown #(player/release! interop-player))]
        (is (= {:error-type :ol.vinyl.interop.player/submission-failed
                :factory-releases 0
                :player-releases 0
                :submissions 2}
               {:error-type (:type (ex-data error))
                :factory-releases (count (filter #{:release-factory} @events_))
                :player-releases (count (filter #{:release-player} @events_))
                :submissions @submissions_}))))
    (is false "The transactional player constructor is not implemented.")))