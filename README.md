# vinyl

>  A small headless audio player for clojure powered by vlc

Vinyl is nice bit of clojure suger on top of the [vlcj][vlcj], the java native
language bindings for [libvlc][libvlc] (version 3.x) with a focus on being the
audio engine for an audio player. It was developed to support the
[fairybox][fairybox] project.

The feature list includes:

- playback of any audio format vlc supports
- playback of media files, or folders, or playlist files with recursive scanning
- modern playback queue management features ("append", "add next with priority", move x to y, etc)
- expected playback and mixer controls: play, pause, volume↑↓, repeat, shuffle etc

From a software architecture standpoint the goals are:

- headless, bring your own controls and user interface (see [fairybox][fairybox])
- strict command query responsibility segregation (cqrs)
- an event-first architecture
- proper management of native resources to avoid jvm crashes

Explicitly out of scope are:

- becoming a generic "wrapper" around vlcj
- video

Limitations:

- libvlc 3.x does not support gapless playback (might change with 4.x)
- libvlc 4.x has nice support for replaygain and EBU R128, but it's not exposed yet via vlcj

## Player lifecycle

Players are opaque values that own native resources. Keep a strong reference to
each player while it is active, and release it when it is no longer needed.
Applications may structure ownership differently. For example, a lexically
scoped player can be released in a `finally` block:

```clojure
(require '[ol.vinyl :as vinyl])

(let [player (vinyl/create-player)]
  (try
    @(vinyl/parse-meta player ["/srv/media"])
    (finally
      (vinyl/release-player! player))))
```

`release-player!` runs synchronously and exactly once, returns `nil` on success,
and makes the player terminal as soon as release begins. Calls admitted before
release may finish; later calls fail.

A factory supplied through `:media-player-factory` remains caller-owned and must
outlive every player that uses it. Recreate players after loading a new Vinyl
version into a running JVM.

[fairybox]: https://github.com/Ramblurr/fairybox/
[vlcj]: https://github.com/caprica/vlcj
[libvlc]: https://www.videolan.org/vlc/libvlc.html

## License: European Union Public License 1.2

Copyright © 2025 Casey Link <casey@outskirtslabs.com>
Distributed under the [EUPL-1.2](https://spdx.org/licenses/EUPL-1.2.html).
