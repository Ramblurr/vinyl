(ns com.outskirtslabs.vlc.util)

(defmacro thread
  "Starts a virtual thread. Conveys bindings."
  [& body]
  `(Thread/startVirtualThread
    (bound-fn* ;; binding conveyance
     (fn [] ~@body))))
