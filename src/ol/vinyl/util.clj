;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.util)

(defmacro thread
  "Starts a virtual thread. Conveys bindings."
  [& body]
  `(Thread/startVirtualThread
    (bound-fn* ;; binding conveyance
     (fn [] ~@body))))