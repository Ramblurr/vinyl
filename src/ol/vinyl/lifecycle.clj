;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl.lifecycle
  (:import
   [java.util.concurrent TimeUnit]
   [java.util.concurrent.atomic AtomicBoolean]
   [java.util.concurrent.locks Condition ReentrantLock ReentrantReadWriteLock]))

(def ^:private default-drain-timeout-ms 30000)

(defn create
  "Creates an internal player lifecycle controller."
  ([]
   (create {}))
  ([{:keys [drain-timeout-ms]
     :or {drain-timeout-ms default-drain-timeout-ms}}]
   (let [lease-lock (ReentrantLock. true)]
     {:drain-timeout-ms drain-timeout-ms
      :lease-drained (.newCondition lease-lock)
      :lease-lock lease-lock
      :leases_ (atom 0)
      :lock (ReentrantReadWriteLock. true)
      :state_ (atom :active)})))

(defn state
  "Returns the internal lifecycle state of `controller`."
  [{:keys [state_]}]
  @state_)

(defn drain-timeout-ms
  "Returns the private native-use drain deadline for `controller`."
  [controller]
  (:drain-timeout-ms controller))

(defn ^:no-doc deadline-after-ms
  "Returns a monotonic deadline `timeout-ms` from now."
  [timeout-ms]
  (+ (System/nanoTime)
     (.toNanos TimeUnit/MILLISECONDS (long timeout-ms))))

(defn ^:no-doc remaining-nanos
  "Returns the non-negative nanoseconds remaining before `deadline-nanos`."
  [deadline-nanos]
  (max 0 (- (long deadline-nanos) (System/nanoTime))))

(defn- not-active-error [lifecycle-state]
  (ex-info "Player has been released and cannot be used anymore."
           {:lifecycle-state lifecycle-state
            :type :ol.vinyl.lifecycle/not-active}))

(defn ensure-active!
  "Throws unless `controller` still admits player operations."
  [controller]
  (let [lifecycle-state (state controller)]
    (when-not (= :active lifecycle-state)
      (throw (not-active-error lifecycle-state)))))

(defn acquire-use!
  "Acquires a transferable lease that excludes release until its returned
  zero-argument function is called."
  [{:keys [^ReentrantLock lease-lock leases_] :as controller}]
  (.lock lease-lock)
  (try
    (ensure-active! controller)
    (swap! leases_ inc)
    (finally
      (.unlock lease-lock)))
  (let [released? (AtomicBoolean.)]
    (fn []
      (when (.compareAndSet released? false true)
        (.lock lease-lock)
        (try
          (when (zero? (swap! leases_ dec))
            (.signalAll ^Condition (:lease-drained controller)))
          (finally
            (.unlock lease-lock)))))))

(defn with-use
  "Runs `f` while release is excluded from the native resource graph."
  [{:keys [^ReentrantReadWriteLock lock] :as controller} f]
  (ensure-active! controller)
  (let [read-lock (.readLock lock)]
    (.lock read-lock)
    (try
      (ensure-active! controller)
      (f)
      (finally
        (.unlock read-lock)))))

(defn begin-release!
  "Atomically changes `controller` from `:active` to `:releasing`."
  [{:keys [^ReentrantLock lease-lock state_]}]
  (.lock lease-lock)
  (try
    (compare-and-set! state_ :active :releasing)
    (finally
      (.unlock lease-lock))))

(defn complete-release!
  "Publishes a terminal release `status`."
  [{:keys [state_]} status]
  {:pre [(contains? #{:released :release-failed} status)]}
  (reset! state_ status))

(defn- drain-interrupted-error [error]
  (ex-info "Interrupted while draining player use."
           {:type :ol.vinyl.lifecycle/drain-interrupted}
           error))

(defn- drain-timeout-error [timeout-ms]
  (ex-info "Timed out while draining active player use."
           {:timeout-ms timeout-ms
            :type :ol.vinyl.lifecycle/drain-timeout}))

(defn- await-leases!
  [{:keys [^ReentrantLock lease-lock leases_] :as controller} deadline-nanos]
  (let [^Condition lease-drained (:lease-drained controller)]
    (try
      (.lockInterruptibly lease-lock)
      (try
        (loop []
          (if (zero? @leases_)
            true
            (let [remaining (remaining-nanos deadline-nanos)]
              (if (zero? remaining)
                false
                (do
                  (.awaitNanos lease-drained remaining)
                  (recur))))))
        (finally
          (.unlock lease-lock)))
      (catch InterruptedException error
        (.interrupt (Thread/currentThread))
        (throw (drain-interrupted-error error))))))

(defn with-exclusive
  "Runs `f` after active native use drains before a monotonic deadline.

  The two-argument form creates a fresh private deadline.
  The three-argument form shares `deadline-nanos` with earlier drain phases."
  ([controller f]
   (with-exclusive controller
     (deadline-after-ms (drain-timeout-ms controller))
     f))
  ([{:keys [drain-timeout-ms ^ReentrantReadWriteLock lock] :as controller}
    deadline-nanos
    f]
   (when-not (await-leases! controller deadline-nanos)
     (throw (drain-timeout-error drain-timeout-ms)))
   (let [write-lock (.writeLock lock)
         acquired? (try
                     (.tryLock write-lock
                               (remaining-nanos deadline-nanos)
                               TimeUnit/NANOSECONDS)
                     (catch InterruptedException error
                       (.interrupt (Thread/currentThread))
                       (throw (drain-interrupted-error error))))]
     (when-not acquired?
       (throw (drain-timeout-error drain-timeout-ms)))
     (try
       (f)
       (finally
         (.unlock write-lock))))))
