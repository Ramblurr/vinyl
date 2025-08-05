(ns ol.vinyl.interop.listener
  (:import
   [uk.co.caprica.vlcj.media MediaEventListener MediaEventAdapter]
   [uk.co.caprica.vlcj.player.base MediaPlayerEventListener MediaPlayerEventAdapter]))

#_(def libvlc4? (= 4 (-> (uk.co.caprica.vlcj.support.version.LibVlcVersion.) .getVersion .major)))

(defn create-media-player-listener [handler!]
  ;; for libvlc 3.x / vlcj 4.x
  (reify MediaPlayerEventListener
    ;; The first argument `_` is `this`, the second `_` is the `MediaPlayer` instance
    (audioDeviceChanged [_ _ audioDevice] (handler! {:event :vlc/audio-device-changed :audio-device audioDevice}))
    (backward [_ _] (handler! {:event :vlc/backward}))
    (buffering [_ _ newCache] (handler! {:event :vlc/buffering :new-cache newCache}))
    (chapterChanged [_ _ newChapter] (handler! {:event :vlc/chapter-changed :new-chapter newChapter}))
    (corked [_ _ corked?] (handler! {:event :vlc/corked :corked? corked?}))
    (elementaryStreamAdded [_ _ type id] (handler! {:event :vlc/elementary-stream-added :type type :id id}))
    (elementaryStreamDeleted [_ _ type id] (handler! {:event :vlc/elementary-stream-deleted :type type :id id}))
    (elementaryStreamSelected [_ _ type id] (handler! {:event :vlc/elementary-stream-selected :type type :id id}))
    (error [_ _] (handler! {:event :vlc/error}))
    (finished [_ _] (handler! {:event :vlc/finished}))
    (forward [_ _] (handler! {:event :vlc/forward}))
    (lengthChanged [_ _ newLength] (handler! {:event :vlc/length-changed :new-length newLength}))
    (mediaChanged [_ _ mediaRef] (handler! {:event :vlc/media-changed :media-ref mediaRef}))
    (mediaPlayerReady [_ _] (handler! {:event :vlc/media-player-ready}))
    (muted [_ _ muted?] (handler! {:event :vlc/muted :muted? muted?}))
    (opening [_ _] (handler! {:event :vlc/opening}))
    (pausableChanged [_ _ newPausable] (handler! {:event :vlc/pausable-changed :pausable newPausable}))
    (paused [_ _] (handler! {:event :vlc/paused}))
    (playing [_ _] (handler! {:event :vlc/playing}))
    (positionChanged [_ _ newPosition] (handler! {:event :vlc/position-changed :new-position newPosition}))
    (scrambledChanged [_ _ newScrambled] (handler! {:event :vlc/scrambled-changed :scrambled newScrambled}))
    (seekableChanged [_ _ newSeekable] (handler! {:event :vlc/seekable-changed :seekable newSeekable}))
    (snapshotTaken [_ _ filename] (handler! {:event :vlc/snapshot-taken :filename filename}))
    (stopped [_ _] (handler! {:event :vlc/stopped}))
    (timeChanged [_ _ newTime] (handler! {:event :vlc/time-changed :new-time newTime}))
    (titleChanged [_ _ newTitle] (handler! {:event :vlc/title-changed :new-title newTitle}))
    (videoOutput [_ _ newCount] (handler! {:event :vlc/video-output :new-count newCount}))
    (volumeChanged [_ _ volume] (handler! {:event :vlc/volume-changed :new-volume volume})))
  ;; for libvlc 4.x / vlcj 5.x
  #_(reify MediaPlayerEventListener
    ;; The first argument `_` is `this`, the second `_` is the `MediaPlayer` instance
      (timeChanged [_ _ newTime] (handler! {:event :vlc/time-changed :new-time newTime}))
      (finished [_ _] (handler! {:event :vlc/finished}))
      (error [_ _] (handler! {:event :vlc/error}))
      (backward [_ _] (handler! {:event :vlc/backward}))
      (forward [_ _] (handler! {:event :vlc/forward}))
      (buffering [_ _ newCache] (handler! {:event :vlc/buffering :new-cache newCache}))
      (lengthChanged [_ _ newLength] (handler! {:event :vlc/length-changed :new-length newLength}))
      (mediaChanged [_  mediaPlayer  mediaRef] (handler! {:event :vlc/media-changed :media-ref mediaRef :player mediaPlayer}))
      (mediaPlayerReady [_ _] (handler! {:event :vlc/media-player-ready}))
      (muted [_ _ muted?] (handler! {:event :vlc/muted :muted? muted?}))
      (opening [_ _] (handler! {:event :vlc/opening}))
      (paused [_ _] (handler! {:event :vlc/paused}))
      (playing [_ _] (handler! {:event :vlc/playing}))
      (positionChanged [_ _ newPosition] (handler! {:event :vlc/position-changed :new-position newPosition}))
      (stopped [_ _] (handler! {:event :vlc/stopped}))
      (stopping [_ _] (handler! {:event :vlc/stopping}))
      (volumeChanged [_ _ newVolume] (handler! {:event :vlc/volume-changed :new-volume newVolume}))
      (audioDeviceChanged [_ _ audioDevice] (handler! {:event :vlc/audio-device-changed :audio-device audioDevice}))
      (chapterChanged [_ _ newChapter] (handler! {:event :vlc/chapter-changed :new-chapter newChapter}))
      (corked [_ _ corked?] (handler! {:event :vlc/corked :corked? corked?}))
      (elementaryStreamAdded [_ _  type id streamId] (handler! {:event :vlc/elementary-stream-added :type type :id id :stream-id streamId}))
      (elementaryStreamDeleted [_ _  type id streamId] (handler! {:event :vlc/elementary-stream-deleted :type type :id id :stream-id streamId}))
      (elementaryStreamSelected [_ _ type unselectedStreamId selectedStreamId] (handler! {:event :vlc/elementary-stream-selected :type type :unselected-stream-id unselectedStreamId :selected-stream-id selectedStreamId}))
      (elementaryStreamUpdated [_ _ type id streamId] (handler! {:event :vlc/elementary-stream-updated :type type :id id :stream-id streamId}))
      (pausableChanged [_ _ pausable] (handler! {:event :vlc/pausable-changed :pausable pausable}))
      (programAdded [_ _ id] (handler! {:event :vlc/program-added :id id}))
      (programDeleted [_ _ id] (handler! {:event :vlc/program-deleted :id id}))
      (programSelected [_ _ unselectedId selectedId] (handler! {:event :vlc/program-selected :unselected-id unselectedId :selected-id selectedId}))
      (programUpdated [_ _ id] (handler! {:event :vlc/program-updated :id id}))
      (recordChanged [_ _ recording recordedFilePath] (handler! {:event :vlc/record-changed :recording recording :recorded-file-path recordedFilePath}))
      (seekableChanged [_ _ seekable] (handler! {:event :vlc/seekable-changed :seekable seekable}))
      (snapshotTaken [_ _ filename] (handler! {:event :vlc/snapshot-taken :filename filename}))
      (titleListChanged [_ _] (handler! {:event :vlc/title-list-changed}))
      (titleSelectionChanged [_ _  titleDescription index] (handler! {:event :vlc/title-selection-changed :title-description titleDescription :index index}))
      (videoOutput [_ _ newCount] (handler! {:event :vlc/video-output :new-count newCount}))))

(defn create-media-event-listener [handler!]
  ;; for libvlc 3.x / vlcj 4.x
  (reify MediaEventListener
    (mediaDurationChanged [_ media newDuration] (handler! {:event :vlc/media-duration-changed :new-duration newDuration :media media}))
    (mediaFreed [_ media mediaFreed] (handler! {:event :vlc/media-freed :media media :media-freed mediaFreed}))
    (mediaMetaChanged [_ media metaType] (handler! {:event :vlc/media-meta-changed :media media :meta-type metaType}))
    (mediaParsedChanged [_ media newStatus] (handler! {:event :vlc/media-parsed-changed :media media :new-status newStatus}))
    (mediaStateChanged [_ media newState] (handler! {:event :vlc/media-state-changed :media media :new-state newState}))
    (mediaSubItemAdded [_ media newChild] (handler! {:event :vlc/media-sub-item-added :media media :new-child newChild}))
    (mediaSubItemTreeAdded [_ media item] (handler! {:event :vlc/media-sub-item-tree-added :media media :item item}))
    (mediaThumbnailGenerated [_ media picture] (handler! {:event :vlc/media-thumbnail-generated :media media :picture picture})))
  ;; for libvlc 4.x / vlcj 5.x
  #_(reify MediaEventListener
      (mediaDurationChanged [_ media newDuration] (handler! {:event :vlc/media-duration-changed :new-duration newDuration :media media}))
      (mediaMetaChanged [_  media meta] (handler! {:event :vlc/media-meta-changed :media media :meta meta}))
      (mediaParsedChanged [_  media  newStatus] (handler! {:event :vlc/media-parsed-changed :media media :new-status newStatus}))
      (mediaThumbnailGenerated [_  media  picture] (handler! {:event :vlc/media-thumbnail-generate :media media :picture picture}))
      (mediaAttachedThumbnailsFound [_  media attachedThumbnails] (handler! {:event :vlc/media-attached-thumbnails-found :media media :attached-thumbnails attachedThumbnails}))
      (mediaSubItemAdded [_  media subItem] (handler! {:event :vlc/media-sub-item-added :media media :sub-item subItem}))
      (mediaSubItemTreeAdded [_  media subItem] (handler! {:event :vlc/media-sub-item-tree-added :media media :sub-item subItem}))))

(defn parse-event-listener ^MediaEventAdapter [callback]
  (proxy [MediaEventAdapter] []
    (mediaParsedChanged [media newStatus]
      (try
        (callback media newStatus)
        (finally
          (-> media (.events) (.removeMediaEventListener this)))))))

(defn wait-for-stop-listener ^MediaPlayerEventAdapter [callback]
  (proxy [MediaPlayerEventAdapter] []
    (stopped [mp]
      (.submit mp (fn []
                    (-> mp (.events) (.removeMediaEventListener this))
                    (callback))))))
