;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns ol.vinyl
  "A small headless audio player for clojure powered by vlc.

  This namespace provides the public API for creating and controlling a media
  player. The core abstraction is the 'player', a stateful resource that requires
  lifecycle management. You should treat the player as an opaque object, as the
  internals are not part of the public API and may change without notice.

  Interaction is divided into two categories:
  1. Command and Control: Features like `play`, `pause`, `set-volume` for commanding the player.
  2. Observation: A subscription model (`subscribe!`, `unsubscribe!`) for reacting
     to a stream of events from the player."
  (:require
   [ol.vinyl.factory :as factory]
   [ol.vinyl.bus :as bus]
   [ol.vinyl.playback :as playback]
   [ol.vinyl.impl :as impl])
  (:import [uk.co.caprica.vlcj.player.base Equalizer]))

(defn init!
  "Creating a [[uk.co.caprica.vlcj.factory.MediaPlayerFactory]] is required for using vlcj,
  creating one initializes the native libraries and memory space. This can take a few seconds.

  This init! function gives you control over when the factory is intialized. It
  will create a fresh factory and return it.  It caches the factory in a var so
  it can be re-used. You can use [[factory]] to get the factory or [[deinit]] to
  release it (not recommended).

  Some other functions in this namespace accept a `:media-player-factory`
  option, you should pass the factory created here to those functions, otherwise
  they will create their own during which they will block for a few seconds."
  []
  (factory/init!))

(defn deinit!
  "Releases the [[uk.co.caprica.vlcj.factory.MediaPlayerFactory]].

  In general it is not necessary to call this function, as the factory is little
  more than an initialization mechanism for libvlc, and libvlc is notorious for
  not handling deinitialization of these library-wide components gracefully.

  See [[init!]]."
  []
  (factory/deinit!))

(defn factory
  "Returns the singleton [[uk.co.caprica.vlcj.factory.MediaPlayerFactory]] instance.

  This is the factory that was created by `init!` and is used to create player
  instances. If you have not called `init!`, this will return nil."
  []
  factory/singleton)

(defn create-player
  "Creates and initializes a new media player instance.

  This is the entry point for using the library. It sets up the underlying
  vlcj resources, the internal state machine, and the event bus. The returned
  player is an atom containing the entire state of the player, which can be
  safely dereferenced at any time for inspection.

  Opts a map of:
  - `:media-player-factory`: An optional pre-initialized [[uk.co.caprica.vlcj.factory/MediaPlayerFactory]] instance.
    If not provided, a new factory will be created with default settings. If provided, you are responsible for releasing it

  Returns an opaque player instance, which you must keep a hard reference to to
  prevent it from being garbage collected. If a player is GCed you will see
  unpredictable behavior, including fatal JVM crashes.

  When you are finished with the player, you must call `release-player!`

  It is always a better strategy to reuse player instances, rather than
  repeatedly creating and destroying them."
  ([] (create-player {}))
  ([opts]
   (impl/create-player-impl opts)))

(defn release-player!
  "Stops playback and releases all underlying native VLC resources.

  This function MUST be called when you are finished with a player instance
  to prevent memory and resource leaks. It is idempotent and safe to call
  multiple times. After release, the player is in a terminal state and
  cannot be used again."
  [player]
  (impl/release-player-impl! player))

;; --- Control API ---

(defn dispatch
  "Dispatches a command to the player."
  [player command]
  (bus/dispatch-event! player command))

;; --- Async Observation API ---

(defn subscribe!
  "Subscribes a callback function to the player's event stream.

  The `callback-fn` will be invoked asynchronously when an event matching
  `event-pred` occurs.

  - `player`: The player instance to subscribe to.
  - `event-pred`: A predicate used to filter events. Can be:
      - A keyword (e.g., `:vlc/playing`) to match a single event type.
      - A set of keywords (e.g., `#{:vlc/playing :vlc/paused}`) to match any.
      - A function that takes an event map and returns a truthy value.
  - `callback-fn`: A single-argument function that will receive the event map.

  Returns a unique subscription ID which can be used with `unsubscribe!`."
  ([player event-pred]
   (bus/subscribe-impl! player event-pred (constantly true)))
  ([player event-pred callback-fn]
   (bus/subscribe-impl! player event-pred callback-fn)))

(defn unsubscribe!
  "Removes a subscription from the player's event bus.

  `subscription-id` is the value returned from a previous call to `subscribe!`."
  [player subscription-id]
  (bus/unsubscribe-impl! player subscription-id))

;; --- Synchronous Observation API ---

(defn get-repeat
  "Get whether or not the media player will automatically repeat playing the media when it has finished playing."
  ^Boolean [player]
  (impl/get-repeat-impl player))

(defn get-channel
  "Get the current audio channel"
  [player]
  (impl/get-channel-impl player))

(defn get-delay "Get the audio delay in microseconds"
  [player]
  (impl/get-delay-impl player))

(defn get-equalizer
  "Get the current audio equalizer or nil if no equalizer is set."
  ^Equalizer [player]
  (impl/get-equalizer-impl player))

(defn muted?
  "Test whether or not the volume is currently muted."
  [player]
  (impl/muted?-impl player))

(defn get-output-device "Get the identifier of the current audio output device, if available.
To return a useful value, an audio output must be active (i.e. the media must be playing)."
  ^String [player]
  (impl/get-output-device-impl player))

(defn get-output-devices
  "Get the available audio devices for the media player audio output."
  [player]
  (impl/get-output-devices-impl player))

(defn get-volume "Get the current volume." [player]
  (impl/get-volume-impl player))

;; --- Status Observation API ---

(defn playable?
  "Is the current media playable?"
  [player]
  (impl/playable?-impl player))

(defn playing?
  "Is the media player playing?"
  [player]
  (impl/playing?-impl player))

(defn seekable?
  "Is the current media seekable?"
  [player]
  (impl/seekable?-impl player))

(defn can-pause?
  "Can the current media be paused?"
  [player]
  (impl/can-pause?-impl player))

(defn get-length
  "Get the length of the current media item.
  
  Returns the length in milliseconds."
  [player]
  (impl/get-length-impl player))

(defn get-time
  "Get the current play-back time.
  
  Returns the current time, expressed as a number of milliseconds."
  [player]
  (impl/get-time-impl player))

(defn get-position
  "Get the current play-back position.
  
  Returns the current position, expressed as a percentage (e.g. 0.15 is returned for 15% complete)."
  [player]
  (impl/get-position-impl player))

(defn get-rate
  "Get the current video play rate.
  
  Returns the rate, where 1.0 is normal speed, 0.5 is half speed, 2.0 is double speed and so on."
  [player]
  (impl/get-rate-impl player))

(defn get-video-outputs
  "Get the number of video outputs for the media player.
  
  Returns the number of video outputs, may be zero."
  [player]
  (impl/get-video-outputs-impl player))

(defn get-state
  "Get the media player current state.
  
  It is recommended to listen to events instead of using this."
  [player]
  (impl/get-state-impl player))

;; --- Media Info Observation API ---

(defn get-media-mrl
  "Get the media resource locator for the current media."
  [player]
  (impl/get-media-mrl-impl player))

(defn get-media-type
  "Get the current media type."
  [player]
  (impl/get-media-type-impl player))

(defn get-media-duration
  "Get the duration of the current media.
  
  Returns the duration in milliseconds."
  [player]
  (impl/get-media-duration-impl player))

(defn get-media-tracks
  "Get the list of all media tracks, or only those that match specified types.
  
  Track types can be :audio, :video, :text, or :unknown."
  [player & track-types]
  (apply impl/get-media-tracks-impl player track-types))

(defn get-audio-tracks
  "Get the list of audio tracks on the current media.
  
  Returns a vector of audio track info maps, empty vector if none."
  [player]
  (impl/get-audio-tracks-impl player))

(defn get-video-tracks
  "Get the list of video tracks on the current media.
  
  Returns a vector of video track info maps, empty vector if none."
  [player]
  (impl/get-video-tracks-impl player))

(defn get-text-tracks
  "Get the list of text (subtitle) tracks on the current media.
  
  Returns a vector of text track info maps, empty vector if none."
  [player]
  (impl/get-text-tracks-impl player))

(defn get-media-statistics
  "Get playback statistics for the current media.
  
  Returns a map of statistics, or nil on error."
  [player]
  (impl/get-media-statistics-impl player))

;; --- Sugar Query API --

(defn get-current
  "Get comprehensive information about the currently playing media.
  
  Returns a map containing:
  - :duration - the total duration in milliseconds
  - :time - the current playback time in milliseconds
  - :position - the current playback position as a percentage (0.0 to 1.0)
  - :mrl - the media resource locator
  - :state - the current media state
  - :meta - metadata map with the parsed tags (e.g., title, artist, album, etc.)

  and if the queue has a current track will also include:

  - :audio-tracks
  - :media-type
  
  Returns nil if no media is currently loaded."
  [player]
  (impl/get-current-impl player))

;; --- Playback Audio Queue Query API --
;; ...even more sugar

(defn list-queue [player]
  (playback/list-all player))

;; --- Media Parsing API --

(defn parse-meta
  "Parse paths recursively and extract their metadata as Clojure data structures.

   Parameters:
   - player: A player instance, see [[create-player]]
   - paths: A sequence of file paths, directory paths, or URLs to parse

   Returns a promise that will resolve to either:
   - A vector of metadata maps
   - A Throwable on error "

  [player paths]
  (impl/parse-meta player paths))
