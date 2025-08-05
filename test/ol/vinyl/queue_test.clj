;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.queue-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [ol.vinyl.queue :as q]))

(defn track [id]
  {:id id :mrl (str "file:///" id ".mp3")})

(deftest create-queue-test
  (testing "creates empty queue with defaults"
    (let [queue (q/create-queue)]
      (is (= {:history []
              :current nil
              :priority []
              :normal []
              :shuffle? false
              :repeat :none
              :cleanup-fn identity}
             queue))))

  (testing "creates queue with custom cleanup function"
    (let [cleanup-fn #(str "cleaned-" %)
          queue (q/create-queue cleanup-fn)]
      (is (= cleanup-fn (:cleanup-fn queue))))))

(deftest get-current-test
  (testing "returns nil for empty queue"
    (is (nil? (q/get-current (q/create-queue)))))

  (testing "returns current track"
    (let [queue (assoc (q/create-queue) :current (track "a"))]
      (is (= (track "a") (q/get-current queue))))))

(deftest get-at-test
  (testing "unified indexing"
    (let [queue {:history [(track "a") (track "b")]
                 :current (track "c")
                 :priority [(track "d") (track "e")]
                 :normal [(track "f") (track "g")]}]
      (are [idx expected] (= expected (:id (q/get-at queue idx)))
        -2 "a"
        -1 "b"
        0 "c"
        1 "d"
        2 "e"
        3 "f"
        4 "g")
      (is (nil? (q/get-at queue -3)))
      (is (nil? (q/get-at queue 5)))))

  (testing "empty queue returns nil"
    (is (nil? (q/get-at (q/create-queue) 0)))))

(deftest get-slice-test
  (testing "slice across collections"
    (let [queue {:history [(track "a") (track "b")]
                 :current (track "c")
                 :priority [(track "d") (track "e")]
                 :normal [(track "f") (track "g")]}
          result (q/get-slice queue -2 3)]
      (is (= ["a" "b" "c" "d" "e"] (map :id result)))))

  (testing "empty slice"
    (is (= [] (q/get-slice (q/create-queue) 0 5)))))

(deftest list-all-test
  (testing "returns all tracks in order"
    (let [queue {:history [(track "a")]
                 :current (track "b")
                 :priority [(track "c")]
                 :normal [(track "d")]}]
      (is (= {:history [(track "a")]
              :current (track "b")
              :upcoming [(track "c") (track "d")]}
             (q/list-all queue))))))

(deftest append-test
  (testing "adds to normal queue"
    (let [queue (q/create-queue)
          tracks [(track "a") (track "b")]
          result (q/append queue tracks)]
      (is (= tracks (:normal result)))))

  (testing "appends to existing tracks"
    (let [queue (assoc (q/create-queue) :normal [(track "a")])
          result (q/append queue [(track "b")])]
      (is (= ["a" "b"] (map :id (:normal result)))))))

(deftest add-next-test
  (testing "adds to priority queue"
    (let [queue (q/create-queue)
          tracks [(track "a") (track "b")]
          result (q/add-next queue tracks)]
      (is (= tracks (:priority result)))))

  (testing "multiple add-next calls"
    (let [queue (assoc (q/create-queue) :current (track "current"))
          q1 (q/add-next queue [(track "a")])
          q2 (q/add-next q1 [(track "b")])
          q3 (q/add-next q2 [(track "c")])]
      (is (= ["a" "b" "c"] (map :id (:priority q3)))))))

(deftest insert-at-test
  (testing "insert into empty queue"
    (let [queue (q/create-queue)
          result (q/insert-at queue 0 [(track "a") (track "b")])]
      (is (= "a" (:id (:current result))))
      (is (= ["b"] (map :id (:normal result))))))

  (testing "insert at current position"
    (let [queue (assoc (q/create-queue) :current (track "a"))
          result (q/insert-at queue 0 [(track "b")])]
      (is (= "b" (:id (:current result))))
      (is (= ["a"] (map :id (:priority result))))))

  (testing "insert into history"
    (let [queue {:history [(track "a") (track "b")]
                 :current (track "c")
                 :priority []
                 :normal []}
          result (q/insert-at queue -1 [(track "x")])]
      (is (= ["a" "x" "b"] (map :id (:history result)))))))

(deftest advance-test
  (testing "advance with priority queue"
    (let [queue {:history []
                 :current (track "a")
                 :priority [(track "b")]
                 :normal [(track "c")]
                 :shuffle? false
                 :repeat :none}
          result (q/advance queue)]
      (is (= "b" (:id (:current result))))
      (is (= ["a"] (map :id (:history result))))
      (is (= [] (:priority result)))))

  (testing "advance with normal queue"
    (let [queue {:history []
                 :current (track "a")
                 :priority []
                 :normal [(track "b")]
                 :shuffle? false
                 :repeat :none}
          result (q/advance queue)]
      (is (= "b" (:id (:current result))))
      (is (= [] (:normal result)))))

  (testing "advance with repeat track"
    (let [queue (assoc (q/create-queue)
                       :current (track "a")
                       :repeat :track)
          result (q/advance queue)]
      (is (= "a" (:id (:current result))))))

  (testing "advance with repeat list"
    (let [queue {:history [(track "a")]
                 :current (track "b")
                 :priority []
                 :normal []
                 :shuffle? false
                 :repeat :list}
          result (q/advance queue)]
      (is (= "a" (:id (:current result))))
      (is (= [] (:history result)))
      (is (= ["b"] (map :id (:normal result)))))))

(deftest prev-track-test
  (testing "go to previous track"
    (let [queue {:history [(track "a") (track "b")]
                 :current (track "c")
                 :priority []
                 :normal []
                 :shuffle? false
                 :repeat :none}
          result (q/prev-track queue)]
      (is (= "b" (:id (:current result))))
      (is (= ["a"] (map :id (:history result))))
      (is (= ["c"] (map :id (:priority result))))))

  (testing "no previous track"
    (let [queue (assoc (q/create-queue) :current (track "a"))
          result (q/prev-track queue)]
      (is (= queue result)))))

(deftest play-from-test
  (testing "play from future index"
    (let [queue {:history []
                 :current (track "a")
                 :priority [(track "b") (track "c")]
                 :normal []
                 :shuffle? false
                 :repeat :none}
          result (q/play-from queue 2)]
      (is (= "c" (:id (:current result))))
      (is (= ["a" "b"] (map :id (:history result))))
      (is (= [] (:priority result)))))

  (testing "play from history"
    (let [queue {:history [(track "a") (track "b")]
                 :current (track "c")
                 :priority []
                 :normal []
                 :shuffle? false
                 :repeat :none}
          result (q/play-from queue -2)]
      (is (= "a" (:id (:current result))))
      (is (= [] (:history result)))
      (is (= ["b" "c"] (map :id (:priority result)))))))

(deftest query-operations-test
  (testing "can-advance?"
    (is (false? (q/can-advance? (q/create-queue))))
    (is (true? (q/can-advance? (assoc (q/create-queue)
                                      :current (track "a")
                                      :normal [(track "b")]))))
    (is (true? (q/can-advance? (assoc (q/create-queue)
                                      :current (track "a")
                                      :repeat :track)))))

  (testing "can-rewind?"
    (is (false? (q/can-rewind? (q/create-queue))))
    (is (true? (q/can-rewind? (assoc (q/create-queue)
                                     :history [(track "a")])))))

  (testing "empty?"
    (is (true? (q/queue-empty? (q/create-queue))))
    (is (false? (q/queue-empty? (assoc (q/create-queue) :current (track "a"))))))

  (testing "sizes"
    (let [queue {:history [(track "a") (track "b")]
                 :current (track "c")
                 :priority [(track "d")]
                 :normal [(track "e") (track "f")]
                 :shuffle? false
                 :repeat :none}]
      (is (= 6 (q/total-size queue)))
      (is (= 3 (q/future-size queue)))
      (is (= 2 (q/history-size queue)))))

  (testing "indices"
    (let [queue (assoc (q/create-queue) :current (track "a"))]
      (is (= 0 (q/current-index queue)))
      (is (nil? (q/current-index (q/create-queue)))))))

(deftest shuffle-test
  (testing "enable shuffle merges and shuffles"
    (let [queue {:history []
                 :current (track "a")
                 :priority [(track "b") (track "c")]
                 :normal [(track "d") (track "e")]
                 :shuffle? false
                 :repeat :none}
          result (q/set-shuffle queue true)]
      (is (true? (:shuffle? result)))
      (is (= [] (:priority result)))
      (is (= 4 (count (:normal result))))
      (is (= #{"b" "c" "d" "e"}
             (set (map :id (:normal result)))))))

  (testing "disable shuffle"
    (let [queue (assoc (q/create-queue) :shuffle? true)
          result (q/set-shuffle queue false)]
      (is (false? (:shuffle? result))))))

(deftest move-test
  (testing "move track forward"
    (let [queue {:history []
                 :current (track "a")
                 :priority [(track "b") (track "c") (track "d")]
                 :normal []
                 :shuffle? false
                 :repeat :none}
          result (q/move queue 1 3)]
      (is (= ["c" "d" "b"] (map :id (:priority result))))))

  (testing "move track backward"
    (let [queue {:history []
                 :current (track "a")
                 :priority [(track "b") (track "c") (track "d")]
                 :normal []
                 :shuffle? false
                 :repeat :none}
          result (q/move queue 3 1)]
      (is (= ["d" "b" "c"] (map :id (:priority result))))))

  (testing "move same position"
    (let [queue (assoc (q/create-queue) :current (track "a"))]
      (is (= queue (q/move queue 0 0)))))

  (testing "move with current"
    (let [queue (-> (q/create-queue)
                    (q/append [(track "current") (track "a") (track "b") (track "c")])
                    (q/advance)
                    (q/move 1 2))]
      (is (= ["b" "a" "c"] (mapv :id (:normal queue))))))

  (testing "move with no current"
    (let [queue (-> (q/create-queue)
                    (q/append [(track "a") (track "b") (track "c")])
                    (q/move 1 2))]
      (is (= ["b" "a" "c"] (mapv :id (:normal queue)))))))

(deftest remove-at-test
  (testing "remove current track"
    (let [queue {:history []
                 :current (track "a")
                 :priority [(track "b")]
                 :normal [(track "c")]
                 :shuffle? false
                 :repeat :none}
          result (q/remove-at queue [0])]
      (is (= "b" (:id (:current result))))
      (is (= [] (:priority result)))))

  (testing "remove from history"
    (let [queue {:history [(track "a") (track "b") (track "c")]
                 :current (track "d")
                 :priority []
                 :normal []
                 :shuffle? false
                 :repeat :none}
          result (q/remove-at queue [-2])]
      (is (= ["a" "c"] (map :id (:history result))))))

  (testing "remove multiple"
    (let [queue {:history []
                 :current (track "a")
                 :priority [(track "b") (track "c") (track "d")]
                 :normal []
                 :shuffle? false
                 :repeat :none}
          result (q/remove-at queue [1 3])]
      (is (= ["c"] (map :id (:priority result)))))))

(deftest clear-operations-test
  (testing "clear upcoming"
    (let [queue {:history [(track "a")]
                 :current (track "b")
                 :priority [(track "c")]
                 :normal [(track "d")]
                 :shuffle? false
                 :repeat :none
                 :cleanup-fn identity}
          result (q/clear-upcoming queue)]
      (is (= [] (:priority result)))
      (is (= [] (:normal result)))
      (is (= [(track "a")] (:history result)))
      (is (= (track "b") (:current result)))))

  (testing "clear all"
    (let [queue {:history [(track "a")]
                 :current (track "b")
                 :priority [(track "c")]
                 :normal [(track "d")]
                 :shuffle? false
                 :repeat :none
                 :cleanup-fn identity}
          result (q/clear-all queue)]
      ;; clear-all creates a new queue with the same cleanup function
      (is (= identity (:cleanup-fn result)))
      (is (nil? (:current result)))
      (is (= [] (:history result)))
      (is (= [] (:priority result)))
      (is (= [] (:normal result)))
      (is (= :none (:repeat result)))
      (is (false? (:shuffle? result))))))

(deftest replace-test
  (testing "replace single with multiple"
    (let [queue {:history []
                 :current (track "a")
                 :priority [(track "b")]
                 :normal [(track "c")]
                 :shuffle? false
                 :repeat :none}
          result (q/replace-at queue 1 [(track "x") (track "y")])]
      (is (= ["x" "y"] (map :id (:priority result))))))

  (testing "replace non-existent"
    (let [queue (assoc (q/create-queue) :current (track "a"))]
      (is (= queue (q/replace-at queue 5 [(track "x")]))))))

(deftest integration-test
  (testing "complex add-next scenario from requirements"
    (let [queue (-> (q/create-queue)
                    (q/append [(track "a") (track "b") (track "c") (track "d")])
                    (q/advance))
          q1 (q/add-next queue [(track "z")])
          q2 (q/add-next q1 [(track "y")])
          q3 (q/add-next q2 [(track "x")])
          q4 (q/append q3 [(track "w")])]
      (is (= "a" (:id (:current q4))))
      (is (= ["z" "y" "x"] (map :id (:priority q4))))
      (is (= ["b" "c" "d" "w"] (map :id (:normal q4)))))))

(deftest repeat-modes-test
  (testing "repeat track"
    (let [queue (-> (q/create-queue)
                    (q/append [(track "a") (track "b")])
                    (q/advance)
                    (q/set-repeat :track))
          advanced (q/advance queue)]
      (is (= "a" (:id (:current advanced))))))

  (testing "repeat list at end"
    (let [queue (-> (q/create-queue)
                    (q/append [(track "a") (track "b")])
                    (q/set-repeat :list)
                    (q/advance) ;; to a
                    (q/advance) ;; to b
                    (q/advance) ;; to a again
                    )]
      (is (= "a" (:id (:current queue))))
      (is (= [] (:history queue)))
      (is (= ["b"] (map :id (:normal queue)))))))

(deftest edge-cases-test
  (testing "operations on empty queue"
    (let [empty-q (q/create-queue)]
      (is (= empty-q (q/advance empty-q)))
      (is (= empty-q (q/prev-track empty-q)))
      (is (= empty-q (q/play-from empty-q 0)))
      (is (= empty-q (q/move empty-q 0 1)))
      (is (= empty-q (q/remove-at empty-q [0])))))

  (testing "out of bounds operations"
    (let [queue (assoc (q/create-queue)
                       :current (track "a")
                       :normal [(track "b")])]
      (is (= queue (q/play-from queue 10)))
      (is (= queue (q/move queue 10 0)))
      (is (= queue (q/remove-at queue [10])))
      (is (nil? (q/get-at queue 10))))))

(deftest next-track-test
  (testing "next-track ignores repeat mode"
    (let [queue (-> (q/create-queue)
                    (q/append [(track "a") (track "b")])
                    (q/advance)
                    (q/set-repeat :track))
          result (q/next-track queue)]
      (is (= "b" (:id (:current result))))
      (is (= :track (:repeat result))))))

(deftest cleanup-behavior-test
  (testing "cleanup function is called when tracks are removed"
    (let [cleaned-tracks (atom [])
          cleanup-fn (fn [track] (swap! cleaned-tracks conj track))
          queue (-> (q/create-queue cleanup-fn)
                    (q/append [(track "a") (track "b") (track "c")])
                    (q/advance))]

      (testing "remove-at calls cleanup"
        (reset! cleaned-tracks [])
        (q/remove-at queue [1])
        (is (= [(track "b")] @cleaned-tracks)))

      (testing "remove-at with multiple indices calls cleanup"
        (reset! cleaned-tracks [])
        (let [q2 (-> queue
                     (q/add-next [(track "d") (track "e")]))]
          (q/remove-at q2 [1 2])
          (is (= [(track "d") (track "e")] @cleaned-tracks))))

      (testing "replace-at calls cleanup on replaced track"
        (reset! cleaned-tracks [])
        (q/replace-at queue 0 [(track "x")])
        (is (= [(track "a")] @cleaned-tracks)))

      (testing "clear-upcoming calls cleanup"
        (reset! cleaned-tracks [])
        (let [q2 (-> queue
                     (q/add-next [(track "d")])
                     (q/append [(track "e")]))]
          (q/clear-upcoming q2)
          (is (= [(track "d") (track "b") (track "c") (track "e")] @cleaned-tracks))))

      (testing "clear-all calls cleanup on all tracks"
        (reset! cleaned-tracks [])
        (let [q2 (-> (q/create-queue cleanup-fn)
                     (q/append [(track "x") (track "y") (track "z")])
                     (q/advance)
                     (q/advance))]
          (q/clear-all q2)
          (is (= [(track "x") (track "y") (track "z")] @cleaned-tracks))))

      (testing "move operation uses remove-at which calls cleanup"
        (reset! cleaned-tracks [])
        (let [q2 (-> queue
                     (q/add-next [(track "d") (track "e") (track "f")]))]
          ;; Moving a track will trigger remove-at which should cleanup
          (q/move q2 1 3)
          (is (= [(track "d")] @cleaned-tracks)))))

    (testing "cleanup not called unnecessarily"
      (let [cleaned-tracks (atom [])
            cleanup-fn (fn [track] (swap! cleaned-tracks conj track))
            queue (-> (q/create-queue cleanup-fn)
                      (q/append [(track "a") (track "b")])
                      (q/advance))]

        (testing "advance doesn't cleanup when moving to next"
          (reset! cleaned-tracks [])
          (q/advance queue)
          (is (= [] @cleaned-tracks)))

        (testing "prev-track doesn't cleanup"
          (reset! cleaned-tracks [])
          (let [q2 (q/advance queue)]
            (q/prev-track q2)
            (is (= [] @cleaned-tracks))))))))

(deftest from-beginning
  (testing "simple start"
    (let [queue (-> (q/create-queue)
                    (q/append [(track "a")]))]

      (is (q/can-advance? queue))))
  (testing "no current but repeat advances properly"
    (let [queue (-> (q/create-queue)
                    (q/set-repeat :track)
                    (q/append [(track "a") (track "b")]))]
      (is (q/can-advance? queue))
      (is "a" (-> queue (q/advance) :current :id))
      (is "a" (-> queue (q/advance) (q/advance) :current :id))
      (is "b" (-> queue (q/advance) (q/next-track) :current :id)))))