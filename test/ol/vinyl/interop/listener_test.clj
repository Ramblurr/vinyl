;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.interop.listener-test
  (:require
   [clojure.test :refer [deftest is]]
   [ol.vinyl.interop.listener :as listener])
  (:import
   [uk.co.caprica.vlcj.player.base MediaPlayerEventAdapter]))

(deftest stop-listener-removes-itself-through-player-listener-api-test
  (let [submit-var (ns-resolve 'ol.vinyl.interop.listener
                               'submit-to-media-player!)
        remove-var (ns-resolve 'ol.vinyl.interop.listener
                               'remove-media-player-listener!)]
    (if (and submit-var remove-var)
      (let [events_ (atom [])]
        (with-redefs-fn
          {submit-var (fn [_media-player task]
                        (swap! events_ conj :submitted)
                        (task))
           remove-var (fn [_media-player event-listener]
                        (swap! events_ conj [:removed event-listener]))}
          (fn []
            (let [^MediaPlayerEventAdapter event-listener
                  (listener/wait-for-stop-listener
                   #(swap! events_ conj :callback))]
              (.stopped event-listener nil)
              (is (= [:submitted [:removed event-listener] :callback]
                     @events_))))))
      (is false "The media-player listener boundary is not implemented."))))
