;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ^:clj-reload/no-reload ol.vinyl.factory
  "Creating a [[uk.co.caprica.vlcj.factory.MediaPlayerFactory]] is required for using vlcj,
  creating one initializes the native libraries and memory space. This can take a few seconds. "
  (:import
   [uk.co.caprica.vlcj.factory MediaPlayerFactory]))

(def singleton nil)

(defn init! []
  (alter-var-root #'singleton
                  (fn [_]
                    (MediaPlayerFactory.)))
  singleton)

(defn deinit! []
  (when singleton
    (.release singleton)
    (alter-var-root #'singleton (constantly nil))))
