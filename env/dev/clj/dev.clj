(ns dev
  (:require
   [clj-reload.core :as clj-reload]
   [ol.dev.portal :as my-portal]))

;; --------------------------------------------------------------------------------------------
;; REPL and Inspector

;; Configure the paths containing clojure sources we want clj-reload to reload
(clj-reload/init {:dirs      ["src" "env/dev" "test"]
                  :no-reload #{'user 'dev 'ol.dev.portal}})

(comment
  (reset! my-portal/portal-state nil)
  (my-portal/open-portals)
  ;;
  )
