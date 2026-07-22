;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.factory-test
  (:require
   [clojure.test :refer [deftest is]]
   [ol.vinyl.factory :as factory])
  (:import
   [java.util.concurrent CountDownLatch TimeUnit]))

(deftest init-is-idempotent-under-concurrent-calls-test
  (if-let [new-factory-var
           (ns-resolve 'ol.vinyl.factory 'new-media-player-factory!)]
    (let [creations_ (atom 0)
          created-factory (Object.)]
      (with-redefs [factory/singleton nil]
        (with-redefs-fn
          {new-factory-var (fn []
                             (swap! creations_ inc)
                             created-factory)}
          (fn []
            (let [task-count 20
                  ready (CountDownLatch. task-count)
                  start (CountDownLatch. 1)
                  tasks (mapv (fn [_]
                                (future
                                  (.countDown ready)
                                  (.await start)
                                  (factory/init!)))
                              (range task-count))
                  ready? (.await ready 2 TimeUnit/SECONDS)]
              (.countDown start)
              (let [results (mapv deref tasks)]
                (is (= {:all-tasks-ready? true
                        :created-once? true
                        :creations 1
                        :same-results? true
                        :singleton? true}
                       {:all-tasks-ready? ready?
                        :created-once? (identical? created-factory
                                                   (first results))
                        :creations @creations_
                        :same-results? (every? #(identical? created-factory %)
                                               results)
                        :singleton? (identical? created-factory
                                                factory/singleton)}))))))))
    (is false "The media-player factory boundary is not implemented.")))
