(ns com.outskirtslabs.vlc.protocols
  (:import
   [java.net URI]
   [java.nio.file Paths]))

(defprotocol Playable
  "Protocol for playable media items."
  (mrl [this] "Returns the media resource locator (MRL) of the playable item."))

(defn playable? [item]
  (satisfies? Playable item))

(defrecord Track [mrl meta duration audio-tracks media-type]
  Playable
  (mrl [_] mrl))

(defn track? [track]
  (instance? Track track))

(defn coerce-to-mrl
  "Attempt to coerce a string to a valid MRL (Media Resource Locator)."
  [^String s]
  (cond
    ;; Check if it's already a proper URI with scheme
    (and (try (URI. s) (catch Exception _ nil))
         (.getScheme (URI. s))
         (not (.startsWith s "file:/")))
    s

    ;; Fix invalid file:/ URIs (single slash)
    (.startsWith s "file:/")
    (if (.startsWith s "file://")
      s  ; Already valid file://
      (.replaceFirst s "file:/" "file:///"))

    ;; Otherwise, treat as local path
    :else
    (let [path (-> s
                   (.replaceAll "^dir://" "")
                   (Paths/get (into-array String []))
                   .toAbsolutePath)
          uri (-> path .toUri str)
          dir?  (-> path .toFile .isDirectory)]
      (if dir?
        (.replaceFirst uri "file://" "dir://")
        uri))))

(extend-protocol Playable
  String
  (mrl [this] (coerce-to-mrl this))
  nil
  (mrl [_] nil))
