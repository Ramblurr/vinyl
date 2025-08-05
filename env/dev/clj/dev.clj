;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns dev
  (:require
   [clj-reload.core :as clj-reload]
   [ol.dev.portal :as my-portal]))

;; --------------------------------------------------------------------------------------------
;; REPL and Inspector

;; Configure the paths containing clojure sources we want clj-reload to reload
(clj-reload/init {:dirs      ["src" "env/dev" "test"]
                  :no-reload #{'user 'dev 'ol.dev.portal}})
;; --------------------------------------------------------------------------------------------
;; System Control

(defn start []
  (set! *warn-on-reflection* true)
  (set! *print-namespace-maps* false)
  :started)

(defn stop []
  :stopped)

(defn reset []
  (stop)
  (clj-reload/reload)
  (start))

(defn reset-all []
  (stop)
  (clj-reload/reload {:only :all})
  (start))

(comment
  (reset! my-portal/portal-state nil)

  (clojure.repl.deps/sync-deps)
  (reset-all)
  ;;
  )

(defonce ps (my-portal/open-portals))

(comment
  (do
    (require '[ol.vinyl :as mp])
    (def _fac (mp/init!))
    (def mp (mp/create-player {:media-player-factory _fac})))
  (mp/release-player! mp)

  (mp/dispatch mp {:event :playback/append
                   :paths [#_"dir://home/ramblurr/src/sparklestories/media/audiobooks/A.A. Milne/Disc 1 - Introducing Pooh and Piglet/"
                           #_"/home/ramblurr/src/sparklestories/media/audiobooks/Dr. Seuss"
                           #_"/home/ramblurr/src/sparklestories/media/audiobooks/Else Holmelund Minarik/Little Bear/02 What Will Little Bear Wear.mp3"
                           #_"/home/ramblurr/src/sparklestories/media/playlists/SoManyFairies-Fall1.m3u"
                           "/home/ramblurr/src/sparklestories/media/playlists/SoManyFairies-Fall2.m3u"
                           #_"/home/ramblurr/src/sparklestories/media/audiobooks/A.A. Milne/Disc 1 - Introducing Pooh and Piglet/Story 1 - In which we are introduced.mp3"]})

  (mp/dispatch mp {:event :playback/add-next
                   :paths ["/home/ramblurr/src/sparklestories/media/audiobooks/Else Holmelund Minarik/Little Bear/02 What Will Little Bear Wear.mp3"]})

  (mp/dispatch mp {:event :playback/clear-all})
  (mp/dispatch mp {:event :playback/play})
  (mp/dispatch mp {:event :playback/advance})
  (mp/dispatch mp {:event :playback/stop})
  (mp/dispatch mp {:event :playback/pause})
  (mp/dispatch mp {:event :playback/next-track})
  (mp/dispatch mp {:event :playback/previous-track})
  (mp/dispatch mp {:event :playback/set-repeat :mode :none})
  (mp/dispatch mp {:event :playback/move :from 2 :to 1})
  (mp/dispatch mp {:event :mixer/set-volume :level 100})
  (mp/dispatch mp {:event :vlcj.controls-api/pause})
  (mp/dispatch mp {:event :vlcj.controls-api/play})
  (mp/dispatch mp {:event :vlcj.controls-api/stop})
  (mp/dispatch mp {:event :vlcj.media-api/reset})
  (->>
   (mp/list-queue mp)
   :upcoming
   (map :meta)
   (map :meta/title))

  (mp/get-state mp)
  (mp/list-queue mp)
  (mp/get-current mp)
  (mp/muted? mp)
  (mp/get-volume mp)

  (->
   (get-in mp [:ol.vinyl.impl/player :vlc/media-player])
   .media
   .subitems
   .newMediaList
   .media
   .count)

;; rcf

  ;;
  )