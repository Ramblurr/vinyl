;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.interop.parsing-test
  (:require
   [clojure.test :refer [deftest is]]
   [ol.vinyl.interop.listener :as listener]
   [ol.vinyl.interop.parsing :as parsing])
  (:import
   [java.lang.ref WeakReference]
   [uk.co.caprica.vlcj.media MediaEventAdapter MediaParsedStatus]))

(defn parsed-meta [paths opts runtime]
  (parsing/parse-meta paths (assoc opts ::parsing/runtime runtime)))

(defn parsed-medias [paths opts runtime]
  (parsing/parse-medias-recursive paths
                                  (assoc opts ::parsing/runtime runtime)))

(defn event-count [events event-type value]
  (count (filter #(and (= event-type (first %))
                       (= value (second %)))
                 events)))

(defn events-of-type [events event-type]
  (filterv #(= event-type (first %)) events))

(defn wait-until [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (pred) true
        (< (System/nanoTime) deadline) (do
                                         (Thread/sleep 5)
                                         (recur))
        :else false))))

(defn callbacks-settled? [callback-futures_]
  (every? #(not= ::timeout (deref % 1000 ::timeout)) @callback-futures_))

(defn normalize-error [error]
  (when error
    {:class (class error)
     :reason (:reason (ex-data error))}))

(defn normalize-result [result]
  (if (instance? Throwable result)
    {:throwable (normalize-error result)}
    (mapv (fn [item]
            (cond-> item
              (:error item) (update :error normalize-error)))
          result)))

(defn fake-runtime
  ([] (fake-runtime {}))
  ([{:keys [add-listener-error behaviors factory-error info-error
            media-list-size-error new-list-media-error new-media-error
            release-factory-error release-media-error
            release-media-list-error remove-listener-error
            remove-listener-keeps-attached? stop-error subitems
            subitems-error]
     :or {behaviors {}
          subitems {}
          subitems-error #{}}}]
   (let [events_ (atom [])
         listeners_ (atom {})
         callback-futures_ (atom [])
         factory (Object.)
         runtime
         {:new-factory! (fn []
                          (when factory-error
                            (throw factory-error))
                          (swap! events_ conj [:new-factory factory])
                          factory)
          :release-factory! (fn [released-factory]
                              (swap! events_ conj
                                     [:release-factory released-factory])
                              (when release-factory-error
                                (throw release-factory-error)))
          :parse-flags vec
          :new-media! (fn [_factory mrl]
                        (swap! events_ conj [:new-media mrl])
                        (when (= new-media-error mrl)
                          (throw (ex-info "Media creation failed." {:mrl mrl})))
                        mrl)
          :release-media! (fn [media]
                            (swap! events_ conj [:release-media media])
                            (when (= release-media-error media)
                              (throw (ex-info "Media release failed."
                                              {:media media}))))
          :new-listener identity
          :add-listener! (fn [media event-listener]
                           (swap! listeners_ assoc media event-listener)
                           (swap! events_ conj
                                  [:add-listener media
                                   (.getName (Thread/currentThread))])
                           (when (= add-listener-error media)
                             (throw (ex-info "Listener attachment failed."
                                             {:media media}))))
          :remove-listener! (fn [media _event-listener]
                              (when-not (and remove-listener-keeps-attached?
                                             (= remove-listener-error media))
                                (swap! listeners_ dissoc media))
                              (swap! events_ conj
                                     [:remove-listener media
                                      (.getName (Thread/currentThread))])
                              (when (= remove-listener-error media)
                                (throw (ex-info "Listener removal failed."
                                                {:media media}))))
          :start-parse! (fn [media _timeout-ms _parse-flags]
                          (swap! events_ conj [:start-parse media])
                          (let [{:keys [callback-gate delay-ms duplicate-status
                                        start-error started? status]
                                 :or {started? true
                                      status MediaParsedStatus/DONE}}
                                (get behaviors media)]
                            (when start-error
                              (throw start-error))
                            (when (and started? status)
                              (let [event-listener (get @listeners_ media)
                                    callback-future
                                    (future
                                      (when callback-gate
                                        @callback-gate)
                                      (when delay-ms
                                        (Thread/sleep (long delay-ms)))
                                      (doseq [callback-status
                                              (cond-> [status]
                                                duplicate-status
                                                (conj duplicate-status))]
                                        (swap! events_ conj
                                               [:callback media
                                                (.getName
                                                 (Thread/currentThread))])
                                        (event-listener media callback-status)))]
                                (swap! callback-futures_ conj callback-future)))
                            started?))
          :stop-parse! (fn [media]
                         (swap! events_ conj
                                [:stop-parse media
                                 (.getName (Thread/currentThread))])
                         (when (= stop-error media)
                           (throw (ex-info "Parse stop failed."
                                           {:media media}))))
          :new-media-list! (fn [media]
                             (when (contains? subitems-error media)
                               (throw (ex-info "Subitem enumeration failed."
                                               {:media media})))
                             (when (contains? subitems media)
                               {:media media
                                :children (vec (get subitems media))}))
          :media-list-size (fn [media-list]
                             (when (= media-list-size-error (:media media-list))
                               (throw (ex-info "Media list count failed."
                                               {:media (:media media-list)})))
                             (count (:children media-list)))
          :new-list-media! (fn [media-list index]
                             (when (= new-list-media-error
                                      [(:media media-list) index])
                               (throw (ex-info "Child media creation failed."
                                               {:index index
                                                :media (:media media-list)})))
                             (nth (:children media-list) index))
          :release-media-list! (fn [media-list]
                                 (swap! events_ conj
                                        [:release-media-list
                                         (:media media-list)])
                                 (when (= release-media-list-error
                                          (:media media-list))
                                   (throw (ex-info "Media list release failed."
                                                   {:media (:media media-list)}))))
          :media-info (fn [media]
                        (swap! events_ conj [:media-info media])
                        (if (= info-error media)
                          (throw (ex-info "Metadata conversion failed."
                                          {:media media}))
                          {:id media}))}]
     {:callback-futures_ callback-futures_
      :events_ events_
      :factory factory
      :listeners_ listeners_
      :runtime runtime})))

(deftest production-parse-listener-only-publishes-events-test
  (let [calls_ (atom [])
        ^MediaEventAdapter event-listener
        (listener/parse-event-listener
         (fn [media status]
           (swap! calls_ conj [media status])))]
    (.mediaParsedChanged event-listener nil MediaParsedStatus/DONE)
    (.mediaParsedChanged event-listener nil MediaParsedStatus/FAILED)
    (is (= [[nil MediaParsedStatus/DONE]
            [nil MediaParsedStatus/FAILED]]
           @calls_))))

(deftest requests-run-serially-in-submission-order-test
  (let [first-callback (promise)
        {:keys [events_ runtime]}
        (fake-runtime
         {:behaviors {"test:first" {:callback-gate first-callback}}})
        first-result (parsed-meta ["test:first"] {} runtime)]
    (is (wait-until #(= [[:start-parse "test:first"]]
                        (events-of-type @events_ :start-parse))
                    1000))
    (let [second-result (parsed-meta ["test:second"] {} runtime)]
      (Thread/sleep 30)
      (is (= [[:start-parse "test:first"]]
             (events-of-type @events_ :start-parse)))
      (deliver first-callback true)
      (is (= [{:id "test:first"
               :parse-status :media-parsed-status/done}]
             (deref first-result 1000 ::timeout)))
      (is (= [{:id "test:second"
               :parse-status :media-parsed-status/done}]
             (deref second-result 1000 ::timeout)))
      (let [^java.util.List events @events_
            first-release-index (.indexOf events [:release-media "test:first"])
            second-start-index (.indexOf events [:start-parse "test:second"])]
        (is (< first-release-index second-start-index))))))

(deftest recursive-metadata-is-realized-before-exact-cleanup-test
  (let [{:keys [callback-futures_ events_ factory runtime]}
        (fake-runtime
         {:subitems {"test:root" ["test:a" "test:b"]}})
        result (deref (parsed-meta ["test:root"] {} runtime)
                      2000
                      ::timeout)
        callbacks-complete? (callbacks-settled? callback-futures_)
        ^java.util.List events @events_]
    (is (= {:callbacks-complete? true
            :factory-releases 1
            :list-releases 1
            :media-releases {"test:a" 1
                             "test:b" 1
                             "test:root" 1}
            :realized? true
            :result [{:id "test:a"
                      :parse-status :media-parsed-status/done}
                     {:id "test:b"
                      :parse-status :media-parsed-status/done}]}
           {:callbacks-complete? callbacks-complete?
            :factory-releases (event-count events :release-factory factory)
            :list-releases (event-count events
                                        :release-media-list
                                        "test:root")
            :media-releases (into {}
                                  (map (fn [media]
                                         [media (event-count events
                                                             :release-media
                                                             media)]))
                                  ["test:a" "test:b" "test:root"])
            :realized? (vector? result)
            :result (normalize-result result)}))
    (is (every? (fn [media]
                  (< (.indexOf events [:media-info media])
                     (.indexOf events [:release-media media])))
                ["test:a" "test:b"]))))

(deftest parse-start-false-settles-without-stopping-test
  (let [{:keys [events_ runtime]}
        (fake-runtime
         {:behaviors {"test:not-started" {:started? false}}})
        result (deref (parsed-meta ["test:not-started"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:listener-removals 1
            :media-releases 1
            :result [{:error {:class clojure.lang.ExceptionInfo
                              :reason :start-failed}
                      :id "test:not-started"
                      :parse-status :media-parsed-status/failed}]
            :stops 0}
           {:listener-removals (event-count events
                                            :remove-listener
                                            "test:not-started")
            :media-releases (event-count events
                                         :release-media
                                         "test:not-started")
            :result (normalize-result result)
            :stops (event-count events :stop-parse "test:not-started")}))))

(deftest timeout-and-late-callback-settle-and-clean-once-test
  (let [{:keys [callback-futures_ events_ runtime]}
        (fake-runtime
         {:behaviors {"test:late" {:delay-ms 100}}})
        result (deref (parsed-meta ["test:late"] {:timeout-ms 20} runtime)
                      1000
                      ::timeout)
        callbacks-complete? (callbacks-settled? callback-futures_)
        events @events_
        callback-thread (nth (first (events-of-type events :callback)) 2)
        removal-thread (nth (first (events-of-type events :remove-listener)) 2)]
    (is (= {:callbacks-complete? true
            :listener-removals 1
            :media-releases 1
            :result [{:error {:class clojure.lang.ExceptionInfo
                              :reason :timeout}
                      :id "test:late"
                      :parse-status :media-parsed-status/timeout}]
            :stops 1}
           {:callbacks-complete? callbacks-complete?
            :listener-removals (event-count events
                                            :remove-listener
                                            "test:late")
            :media-releases (event-count events :release-media "test:late")
            :result (normalize-result result)
            :stops (event-count events :stop-parse "test:late")}))
    (is (not= callback-thread removal-thread))))

(deftest duplicate-callbacks-settle-first-status-and-clean-once-test
  (let [{:keys [callback-futures_ events_ runtime]}
        (fake-runtime
         {:behaviors {"test:duplicate"
                      {:duplicate-status MediaParsedStatus/FAILED}}})
        result (deref (parsed-meta ["test:duplicate"] {} runtime)
                      1000
                      ::timeout)
        callbacks-complete? (callbacks-settled? callback-futures_)
        events @events_]
    (is (= {:callbacks 2
            :callbacks-complete? true
            :listener-removals 1
            :media-releases 1
            :result [{:id "test:duplicate"
                      :parse-status :media-parsed-status/done}]}
           {:callbacks (event-count events :callback "test:duplicate")
            :callbacks-complete? callbacks-complete?
            :listener-removals (event-count events
                                            :remove-listener
                                            "test:duplicate")
            :media-releases (event-count events
                                         :release-media
                                         "test:duplicate")
            :result (normalize-result result)}))))

(deftest parse-start-exception-stops-and-settles-test
  (let [start-error (ex-info "Native parse start failed." {})
        {:keys [events_ runtime]}
        (fake-runtime
         {:behaviors {"test:start-error" {:start-error start-error}}})
        result (deref (parsed-meta ["test:start-error"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:listener-removals 1
            :media-releases 1
            :result [{:error {:class clojure.lang.ExceptionInfo
                              :reason nil}
                      :id "test:start-error"
                      :parse-status :media-parsed-status/failed}]
            :stops 1}
           {:listener-removals (event-count events
                                            :remove-listener
                                            "test:start-error")
            :media-releases (event-count events
                                         :release-media
                                         "test:start-error")
            :result (normalize-result result)
            :stops (event-count events :stop-parse "test:start-error")}))))

(deftest metadata-conversion-error-settles-and-releases-every-media-test
  (let [{:keys [events_ factory runtime]}
        (fake-runtime
         {:info-error "test:b"
          :subitems {"test:root" ["test:a" "test:b"]}})
        result (deref (parsed-meta ["test:root"] {} runtime)
                      2000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :media-releases {"test:a" 1
                             "test:b" 1
                             "test:root" 1}
            :result {:throwable {:class clojure.lang.ExceptionInfo
                                 :reason nil}}}
           {:factory-releases (event-count events :release-factory factory)
            :media-releases (into {}
                                  (map (fn [media]
                                         [media (event-count events
                                                             :release-media
                                                             media)]))
                                  ["test:a" "test:b" "test:root"])
            :result (normalize-result result)}))))

(deftest caller-supplied-factory-remains-caller-owned-test
  (let [supplied-factory (Object.)
        {:keys [events_ runtime]}
        (fake-runtime)
        result (deref (parsed-meta ["test:supplied"]
                                   {:media-player-factory supplied-factory}
                                   runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-creations 0
            :factory-releases 0
            :result [{:id "test:supplied"
                      :parse-status :media-parsed-status/done}]}
           {:factory-creations (count (events-of-type events :new-factory))
            :factory-releases (count (events-of-type events :release-factory))
            :result (normalize-result result)}))))

(deftest recursive-media-api-transfers-leaf-ownership-test
  (let [{:keys [events_ factory runtime]}
        (fake-runtime)
        result (deref (parsed-medias ["test:leaf"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :media-releases 0
            :result [{:media "test:leaf"
                      :parse-status MediaParsedStatus/DONE}]}
           {:factory-releases (event-count events :release-factory factory)
            :media-releases (event-count events :release-media "test:leaf")
            :result result}))))

(deftest partial-root-creation-failure-releases-owned-resources-test
  (let [{:keys [events_ factory runtime]}
        (fake-runtime {:new-media-error "test:broken"})
        result (deref (parsed-meta ["test:created" "test:broken"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :media-releases 1
            :result {:throwable {:class clojure.lang.ExceptionInfo
                                 :reason nil}}}
           {:factory-releases (event-count events :release-factory factory)
            :media-releases (event-count events :release-media "test:created")
            :result (normalize-result result)}))))

(deftest failed-request-does-not-kill-parser-worker-test
  (let [factory-error (ex-info "Factory creation failed." {})
        failed-runtime (:runtime (fake-runtime {:factory-error factory-error}))
        {:keys [runtime]} (fake-runtime)
        failed-result (deref (parsed-meta ["test:failed"] {} failed-runtime)
                             1000
                             ::timeout)
        next-result (deref (parsed-meta ["test:next"] {} runtime)
                           1000
                           ::timeout)]
    (is (= {:failed {:throwable {:class clojure.lang.ExceptionInfo
                                 :reason nil}}
            :next [{:id "test:next"
                    :parse-status :media-parsed-status/done}]}
           {:failed (normalize-result failed-result)
            :next (normalize-result next-result)}))))

(deftest partial-listener-attachment-failure-detaches-before-transfer-test
  (let [{:keys [events_ factory runtime]}
        (fake-runtime {:add-listener-error "test:partial-attach"})
        result (deref (parsed-medias ["test:partial-attach"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :listener-removals 1
            :media-releases 0
            :result [{:error {:class clojure.lang.ExceptionInfo
                              :reason nil}
                      :media "test:partial-attach"
                      :parse-status MediaParsedStatus/FAILED}]
            :starts 0}
           {:factory-releases (event-count events :release-factory factory)
            :listener-removals (event-count events
                                            :remove-listener
                                            "test:partial-attach")
            :media-releases (event-count events
                                         :release-media
                                         "test:partial-attach")
            :result (normalize-result result)
            :starts (event-count events :start-parse "test:partial-attach")}))))

(deftest listener-removal-failure-prevents-media-ownership-transfer-test
  (let [{:keys [events_ factory listeners_ runtime]}
        (fake-runtime {:remove-listener-error "test:attached"
                       :remove-listener-keeps-attached? true})
        result (deref (parsed-medias ["test:attached"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :listener-retained? true
            :listener-removals 1
            :media-releases 1
            :result {:throwable {:class clojure.lang.ExceptionInfo
                                 :reason nil}}}
           {:factory-releases (event-count events :release-factory factory)
            :listener-retained? (contains? @listeners_ "test:attached")
            :listener-removals (event-count events
                                            :remove-listener
                                            "test:attached")
            :media-releases (event-count events
                                         :release-media
                                         "test:attached")
            :result (normalize-result result)}))))

(deftest aggregate-exception-releases-retained-current-and-pending-media-test
  (let [{:keys [events_ factory runtime]} (fake-runtime)
        listener-count_ (atom 0)
        runtime (assoc runtime
                       :new-listener
                       (fn [callback]
                         (if (= 2 (swap! listener-count_ inc))
                           (throw (ex-info "Listener construction failed." {}))
                           callback)))
        result (deref (parsed-meta ["test:first"
                                    "test:current"
                                    "test:pending"]
                                   {}
                                   runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :media-releases {"test:current" 1
                             "test:first" 1
                             "test:pending" 1}
            :result {:throwable {:class clojure.lang.ExceptionInfo
                                 :reason nil}}}
           {:factory-releases (event-count events :release-factory factory)
            :media-releases (into {}
                                  (map (fn [media]
                                         [media (event-count events
                                                             :release-media
                                                             media)]))
                                  ["test:current" "test:first" "test:pending"])
            :result (normalize-result result)}))))

(deftest later-failure-preserves-earlier-cleanup-errors-test
  (let [{:keys [events_ factory runtime]}
        (fake-runtime {:release-media-error "test:container"
                       :subitems {"test:container" ["test:child"]}})
        listener-count_ (atom 0)
        runtime (assoc runtime
                       :new-listener
                       (fn [callback]
                         (if (= 3 (swap! listener-count_ inc))
                           (throw (ex-info "Listener construction failed." {}))
                           callback)))
        result (deref (parsed-meta ["test:container" "test:later"]
                                   {}
                                   runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :media-releases {"test:child" 1
                             "test:container" 1
                             "test:later" 1}
            :result {:throwable {:class clojure.lang.ExceptionInfo
                                 :reason nil}}
            :suppressed-messages ["Media release failed."]}
           {:factory-releases (event-count events :release-factory factory)
            :media-releases (into {}
                                  (map (fn [media]
                                         [media (event-count events
                                                             :release-media
                                                             media)]))
                                  ["test:child" "test:container" "test:later"])
            :result (normalize-result result)
            :suppressed-messages (mapv #(.getMessage ^Throwable %)
                                       (.getSuppressed ^Throwable result))}))))

(deftest media-list-count-failure-releases-list-and-media-test
  (let [{:keys [events_ factory runtime]}
        (fake-runtime {:media-list-size-error "test:root"
                       :subitems {"test:root" ["test:child"]}})
        result (deref (parsed-meta ["test:root"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :list-releases 1
            :media-releases 1
            :result [{:error {:class clojure.lang.ExceptionInfo
                              :reason nil}
                      :id "test:root"
                      :parse-status :media-parsed-status/failed}]}
           {:factory-releases (event-count events :release-factory factory)
            :list-releases (event-count events
                                        :release-media-list
                                        "test:root")
            :media-releases (event-count events :release-media "test:root")
            :result (normalize-result result)}))))

(deftest partial-child-creation-releases-list-and-created-child-test
  (let [{:keys [events_ factory runtime]}
        (fake-runtime {:new-list-media-error ["test:root" 1]
                       :subitems {"test:root" ["test:a" "test:b"]}})
        result (deref (parsed-meta ["test:root"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :list-releases 1
            :media-releases {"test:a" 1
                             "test:root" 1}
            :result [{:error {:class clojure.lang.ExceptionInfo
                              :reason nil}
                      :id "test:root"
                      :parse-status :media-parsed-status/failed}]}
           {:factory-releases (event-count events :release-factory factory)
            :list-releases (event-count events
                                        :release-media-list
                                        "test:root")
            :media-releases (into {}
                                  (map (fn [media]
                                         [media (event-count events
                                                             :release-media
                                                             media)]))
                                  ["test:a" "test:root"])
            :result (normalize-result result)}))))

(deftest cleanup-errors-settle-after-all-release-attempts-test
  (let [factory-error (ex-info "Factory release failed." {})
        {:keys [events_ factory runtime]}
        (fake-runtime {:release-factory-error factory-error
                       :release-media-error "test:cleanup"})
        result (deref (parsed-meta ["test:cleanup"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :media-releases 1
            :result {:throwable {:class clojure.lang.ExceptionInfo
                                 :reason nil}}}
           {:factory-releases (event-count events :release-factory factory)
            :media-releases (event-count events
                                         :release-media
                                         "test:cleanup")
            :result (normalize-result result)}))))

(deftest timeout-cleanup-errors-still-attempt-every-cleanup-test
  (let [{:keys [events_ factory runtime]}
        (fake-runtime {:behaviors {"test:cleanup-timeout" {:status false}}
                       :remove-listener-error "test:cleanup-timeout"
                       :stop-error "test:cleanup-timeout"})
        result (deref (parsed-meta ["test:cleanup-timeout"]
                                   {:timeout-ms 20}
                                   runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :listener-removals 1
            :media-releases 1
            :result {:throwable {:class clojure.lang.ExceptionInfo
                                 :reason :timeout}}
            :stops 1}
           {:factory-releases (event-count events :release-factory factory)
            :listener-removals (event-count events
                                            :remove-listener
                                            "test:cleanup-timeout")
            :media-releases (event-count events
                                         :release-media
                                         "test:cleanup-timeout")
            :result (normalize-result result)
            :stops (event-count events
                                :stop-parse
                                "test:cleanup-timeout")}))))

(deftest media-list-release-error-releases-created-children-test
  (let [{:keys [events_ factory runtime]}
        (fake-runtime {:release-media-list-error "test:root"
                       :subitems {"test:root" ["test:child"]}})
        result (deref (parsed-meta ["test:root"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :list-releases 1
            :media-releases {"test:child" 1
                             "test:root" 1}
            :result [{:error {:class clojure.lang.ExceptionInfo
                              :reason nil}
                      :id "test:root"
                      :parse-status :media-parsed-status/failed}]}
           {:factory-releases (event-count events :release-factory factory)
            :list-releases (event-count events
                                        :release-media-list
                                        "test:root")
            :media-releases (into {}
                                  (map (fn [media]
                                         [media (event-count events
                                                             :release-media
                                                             media)]))
                                  ["test:child" "test:root"])
            :result (normalize-result result)}))))

(deftest factory-release-error-reclaims-media-before-returning-error-test
  (let [factory-error (ex-info "Factory release failed." {})
        {:keys [events_ factory runtime]}
        (fake-runtime {:release-factory-error factory-error})
        result (deref (parsed-medias ["test:factory-error"] {} runtime)
                      1000
                      ::timeout)
        events @events_]
    (is (= {:factory-releases 1
            :media-releases 1
            :result {:throwable {:class clojure.lang.ExceptionInfo
                                 :reason nil}}}
           {:factory-releases (event-count events :release-factory factory)
            :media-releases (event-count events
                                         :release-media
                                         "test:factory-error")
            :result (normalize-result result)}))))

(defn weak-runtime []
  (let [callback-gate (promise)
        callback-future_ (promise)
        media-ref_ (promise)
        listener-ref_ (promise)
        runtime {:new-factory! (fn [] (Object.))
                 :release-factory! (fn [_factory])
                 :parse-flags vec
                 :new-media! (fn [_factory _mrl]
                               (let [media (Object.)]
                                 (deliver media-ref_ (WeakReference. media))
                                 media))
                 :release-media! (fn [_media])
                 :new-listener (fn [callback]
                                 (deliver listener-ref_ (WeakReference. callback))
                                 callback)
                 :add-listener! (fn [_media _event-listener])
                 :remove-listener! (fn [_media _event-listener])
                 :start-parse! (fn [media _timeout-ms _parse-flags]
                                 (let [weak-listener @listener-ref_
                                       weak-media (WeakReference. media)
                                       callback-future
                                       (future
                                         @callback-gate
                                         (when-let [event-listener
                                                    (.get ^WeakReference
                                                     weak-listener)]
                                           (when-let [callback-media
                                                      (.get weak-media)]
                                             (event-listener
                                              callback-media
                                              MediaParsedStatus/DONE))))]
                                   (deliver callback-future_ callback-future)
                                   true))
                 :stop-parse! (fn [_media])
                 :new-media-list! (fn [_media])
                 :media-list-size (fn [_media-list] 0)
                 :new-list-media! (fn [_media-list _index])
                 :release-media-list! (fn [_media-list])
                 :media-info (fn [_media] {:alive true})}]
    {:callback-future_ callback-future_
     :callback-gate callback-gate
     :listener-ref_ listener-ref_
     :media-ref_ media-ref_
     :runtime runtime}))

(defn collected? [^WeakReference reference]
  (wait-until
   (fn []
     (System/gc)
     (let [_pressure (object-array 10000)]
       (nil? (.get reference))))
   3000))

(deftest worker-retains-native-graph-until-terminal-cleanup-test
  (let [{:keys [callback-future_ callback-gate listener-ref_ media-ref_ runtime]}
        (weak-runtime)
        result (parsed-meta ["test:reachable"] {:timeout-ms 10000} runtime)
        media-ref @media-ref_
        listener-ref @listener-ref_
        reachable-while-active? (and (not (collected? media-ref))
                                     (not (collected? listener-ref)))]
    (deliver callback-gate true)
    (let [parsed-result (deref result 1000 ::timeout)
          callback-settled? (not= ::timeout
                                  (deref @callback-future_ 1000 ::timeout))
          media-collected? (collected? media-ref)
          listener-collected? (collected? listener-ref)]
      (is (= {:callback-settled? true
              :listener-collected? true
              :media-collected? true
              :reachable-while-active? true
              :result [{:alive true
                        :parse-status :media-parsed-status/done}]}
             {:callback-settled? callback-settled?
              :listener-collected? listener-collected?
              :media-collected? media-collected?
              :reachable-while-active? reachable-while-active?
              :result (normalize-result parsed-result)})))))

(deftest release-medias-attempts-every-release-before-throwing-test
  (if-let [release-media-var
           (ns-resolve 'ol.vinyl.interop.parsing 'release-media!)]
    (let [events_ (atom [])
          first-error (ex-info "First release failed." {})
          last-error (ex-info "Last release failed." {})
          thrown
          (with-redefs-fn
            {release-media-var
             (fn [media]
               (swap! events_ conj media)
               (case media
                 :first (throw first-error)
                 :last (throw last-error)
                 nil))}
            (fn []
              (try
                (parsing/release-medias! [:first nil :middle :last])
                nil
                (catch Throwable t
                  t))))]
      (is (= {:attempted [:first :middle :last]
              :first-error? true
              :suppressed [last-error]}
             {:attempted @events_
              :first-error? (identical? first-error thrown)
              :suppressed (vec (.getSuppressed ^Throwable thrown))})))
    (is false "The releasable-media boundary is not implemented.")))
