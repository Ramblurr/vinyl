;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.bus-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [ol.vinyl.bus :as bus]
   [ol.vinyl.commands :as commands]
   [ol.vinyl.interop.player :as player]
   [ol.vinyl.lifecycle :as lifecycle]))

(deftest command-submission-is-rejected-after-release-begins-test
  (let [controller (lifecycle/create {:drain-timeout-ms 100})
        <control (async/chan 1)
        instance {:ol.vinyl.impl/<control <control
                  :ol.vinyl.impl/lifecycle controller
                  :ol.vinyl.impl/state_ (atom {:subscriptions {}})}]
    (lifecycle/begin-release! controller)
    (let [error (try
                  (bus/dispatch-command!
                   instance
                   {:ol.vinyl/command :vlcj.controls-api/play})
                  nil
                  (catch Throwable t
                    t))]
      (is (= {:command-enqueued? false
              :error-type :ol.vinyl.lifecycle/not-active}
             {:command-enqueued? (some? (async/poll! <control))
              :error-type (:type (ex-data error))})))))

(deftest queued-native-command-rechecks-lifecycle-before-native-use-test
  (let [controller (lifecycle/create {:drain-timeout-ms 100})
        dispatched_ (atom 0)
        task_ (atom nil)
        instance {:ol.vinyl.impl/lifecycle controller
                  :ol.vinyl.impl/player {:fake :player}
                  :ol.vinyl.impl/state_ (atom {:subscriptions {}})}]
    (with-redefs [commands/dispatch (fn [_instance _command]
                                      (swap! dispatched_ inc))
                  player/run-on-player-thread (fn [_player task]
                                                (reset! task_ task))]
      (bus/dispatch-control-command
       instance
       {:ol.vinyl/command :vlcj.controls-api/play})
      (lifecycle/begin-release! controller)
      (@task_)
      (is (= 0 @dispatched_)))))

(deftest subscriptions-are-rejected-after-release-begins-test
  (let [controller (lifecycle/create {:drain-timeout-ms 100})
        state_ (atom {:subscriptions {}})
        instance {:ol.vinyl.impl/lifecycle controller
                  :ol.vinyl.impl/state_ state_}]
    (lifecycle/begin-release! controller)
    (let [error (try
                  (bus/subscribe-impl! instance (constantly true) identity)
                  nil
                  (catch Throwable t
                    t))]
      (is (= {:error-type :ol.vinyl.lifecycle/not-active
              :subscriptions {}}
             {:error-type (:type (ex-data error))
              :subscriptions (:subscriptions @state_)})))))