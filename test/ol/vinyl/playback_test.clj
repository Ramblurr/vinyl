;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.playback-test
  (:require
   [clojure.test :refer [deftest is]]
   [ol.vinyl.bus :as bus]
   [ol.vinyl.playback :as playback]
   [ol.vinyl.protocols :as protocols]
   [ol.vinyl.queue :as queue]))

(deftest release-clears-playback-without-dispatching-native-work-test
  (let [commands_ (atom [])
        playback-queue (-> (queue/create-queue)
                           (queue/append [(protocols/map->Track
                                           {:mrl "test:track"})])
                           queue/advance)
        state_ (atom {:subscriptions {:playback-subscription {}}
                      :playback {:queue playback-queue
                                 :sub-id :playback-subscription}})
        instance {:ol.vinyl.impl/state_ state_}]
    (with-redefs [bus/dispatch-command! (fn [_instance command]
                                          (swap! commands_ conj command))]
      (playback/release! instance)
      (is (= {:commands []
              :queue {:current nil
                      :history []
                      :normal []
                      :priority []
                      :tracks []}
              :subscriptions {}}
             {:commands @commands_
              :queue (playback/list-all instance)
              :subscriptions (:subscriptions @state_)})))))
