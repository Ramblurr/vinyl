;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.lifecycle-test
  (:require
   [clojure.test :refer [deftest is]])
  (:import
   [java.util.concurrent.locks ReentrantLock]))

(defn- lifecycle-api []
  (try
    {:acquire-use! (requiring-resolve 'ol.vinyl.lifecycle/acquire-use!)
     :begin-release! (requiring-resolve 'ol.vinyl.lifecycle/begin-release!)
     :complete-release! (requiring-resolve 'ol.vinyl.lifecycle/complete-release!)
     :create (requiring-resolve 'ol.vinyl.lifecycle/create)
     :state (requiring-resolve 'ol.vinyl.lifecycle/state)
     :with-exclusive (requiring-resolve 'ol.vinyl.lifecycle/with-exclusive)
     :with-use (requiring-resolve 'ol.vinyl.lifecycle/with-use)}
    (catch Throwable _
      nil)))

(deftest release-waits-for-admitted-use-test
  (if-let [{:keys [begin-release! complete-release! create state
                   with-exclusive with-use]}
           (lifecycle-api)]
    (let [controller (create {:drain-timeout-ms 1000})
          entered_ (promise)
          finish_ (promise)
          use-result_ (future
                        (with-use controller
                          (fn []
                            (deliver entered_ true)
                            @finish_
                            :used)))]
      @entered_
      (is (true? (begin-release! controller)))
      (let [release-result_ (future
                              (with-exclusive controller
                                (fn []
                                  (complete-release! controller :released)
                                  :released)))]
        (is (= ::still-using (deref release-result_ 50 ::still-using)))
        (deliver finish_ true)
        (let [release-result (deref release-result_ 1000 ::timeout)
              use-result (deref use-result_ 1000 ::timeout)]
          (is (= {:lifecycle-state :released
                  :release-result :released
                  :use-result :used}
                 {:lifecycle-state (state controller)
                  :release-result release-result
                  :use-result use-result})))))
    (is false "The player lifecycle controller is not implemented.")))

(deftest release-rejects-new-use-test
  (if-let [{:keys [begin-release! create with-use]} (lifecycle-api)]
    (let [controller (create {:drain-timeout-ms 100})
          called_ (atom 0)]
      (begin-release! controller)
      (let [error (try
                    (with-use controller #(swap! called_ inc))
                    nil
                    (catch Throwable t
                      t))]
        (is (= {:called 0
                :error-type :ol.vinyl.lifecycle/not-active}
               {:called @called_
                :error-type (:type (ex-data error))}))))
    (is false "The player lifecycle controller is not implemented.")))

(deftest exclusive-use-times-out-without-running-release-body-test
  (if-let [{:keys [begin-release! complete-release! create state
                   with-exclusive with-use]}
           (lifecycle-api)]
    (let [controller (create {:drain-timeout-ms 50})
          entered_ (promise)
          finish_ (promise)
          ran-release_ (atom 0)
          use-result_ (future
                        (with-use controller
                          (fn []
                            (deliver entered_ true)
                            @finish_)))]
      @entered_
      (begin-release! controller)
      (let [error (try
                    (with-exclusive controller #(swap! ran-release_ inc))
                    nil
                    (catch Throwable t
                      t))]
        (complete-release! controller :release-failed)
        (deliver finish_ true)
        @use-result_
        (is (= {:error-type :ol.vinyl.lifecycle/drain-timeout
                :lifecycle-state :release-failed
                :release-ran 0}
               {:error-type (:type (ex-data error))
                :lifecycle-state (state controller)
                :release-ran @ran-release_}))))
    (is false "The player lifecycle controller is not implemented.")))

(deftest transferable-use-lease-blocks-release-test
  (if-let [{:keys [acquire-use! begin-release! create with-exclusive]}
           (lifecycle-api)]
    (let [controller (create {:drain-timeout-ms 1000})
          release-use! (acquire-use! controller)]
      (begin-release! controller)
      (let [release-result_ (future
                              (with-exclusive controller
                                (fn [] :released)))]
        (is (= ::still-using
               (deref release-result_ 50 ::still-using)))
        (release-use!)
        (release-use!)
        (is (= :released (deref release-result_ 1000 ::timeout)))))
    (is false "The player lifecycle controller is not implemented.")))

(deftest lease-admission-is-atomic-with-release-begin-test
  (if-let [{:keys [acquire-use! begin-release! create with-exclusive]}
           (lifecycle-api)]
    (let [controller (create {:drain-timeout-ms 1000})
          ^ReentrantLock lease-lock (:lease-lock controller)
          acquire-ready_ (promise)
          begin-ready_ (promise)]
      (.lock lease-lock)
      (let [acquisition_
            (future
              (deliver acquire-ready_ true)
              (try
                [:lease (acquire-use! controller)]
                (catch Throwable error
                  [:error (:type (ex-data error))])))
            begin-result_
            (future
              (deliver begin-ready_ true)
              (begin-release! controller))]
        @acquire-ready_
        @begin-ready_
        (.unlock lease-lock)
        (let [[status value] (deref acquisition_ 1000 ::timeout)
              begin-result (deref begin-result_ 1000 ::timeout)]
          (when (= :lease status)
            (value))
          (is (= {:begin-result true
                  :exclusive-result :released
                  :valid-acquisition? true}
                 {:begin-result begin-result
                  :exclusive-result
                  (with-exclusive controller (fn [] :released))
                  :valid-acquisition?
                  (or (= :lease status)
                      (= [:error :ol.vinyl.lifecycle/not-active]
                         [status value]))})))))
    (is false "The player lifecycle controller is not implemented.")))