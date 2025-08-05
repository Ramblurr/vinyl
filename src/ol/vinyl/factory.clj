;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.factory
  "Creating a MediaPlayerFactory is required for using vlcj. The first time it is created, it initializes the native libraries.
  This can take a few seconds.

  The init! function in this namespace gives you control over when the factory is intialized. You can then pass ol.vinyl.factory/singleton to create-player and co which
  accept a :media-player-factory option.
  "
  (:import
   [uk.co.caprica.vlcj.factory MediaPlayerFactory]))

(def singleton nil)

(defn init!
  []
  (alter-var-root #'singleton
                  (fn [_]
                    (MediaPlayerFactory.)))
  singleton)