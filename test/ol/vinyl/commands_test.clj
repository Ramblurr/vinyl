(ns ol.vinyl.commands-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [malli.core :as m]
   [ol.vinyl.commands :as sut]))

(deftest named-command-schema-test
  (testing "returns schema for known commands"
    (are [command] (some? (sut/named-command-schema command))
      :vlcj.controls-api/play
      :vlcj.audio-api/set-volume
      :playback/append
      :playback/advance))

  (testing "returns nil for unknown commands"
    (is (nil? (sut/named-command-schema :unknown/command))))

  (testing "schema validates correct command name"
    (let [schema (sut/named-command-schema :vlcj.controls-api/play)]
      (is (m/validate schema {:ol.vinyl/command :vlcj.controls-api/play}))
      (is (not (m/validate schema {:ol.vinyl/command :vlcj.controls-api/pause}))))))

(deftest validate-command-test
  (testing "validates commands with empty payloads"
    (are [command] (sut/validate-command {:ol.vinyl/command command})
      :vlcj.controls-api/play
      :vlcj.controls-api/stop
      :playback/advance
      :vlcj.audio-api/mute))

  (testing "validates commands with required payloads"
    (is (sut/validate-command {:ol.vinyl/command :vlcj.audio-api/set-volume
                               :level 50}))
    (is (sut/validate-command {:ol.vinyl/command :playback/append
                               :paths ["file1.mp3" "file2.mp3"]}))
    (is (sut/validate-command {:ol.vinyl/command :playback/set-repeat
                               :mode :track})))

  (testing "throws for unknown command"
    (is (thrown-with-msg? Exception #"Unknown command type"
                          (sut/validate-command {:ol.vinyl/command :fake/command}))))

  (testing "rejects invalid payloads"
    (is (not (sut/validate-command {:ol.vinyl/command :vlcj.audio-api/set-volume})))
    (is (not (sut/validate-command {:ol.vinyl/command :vlcj.audio-api/set-volume
                                    :level "fifty"})))
    (is (not (sut/validate-command {:ol.vinyl/command :playback/set-repeat
                                    :mode :invalid-mode})))
    (is (not (sut/validate-command {:ol.vinyl/command :vlcj.controls-api/play
                                    :extra-field "not allowed"})))))

(deftest command-payload-validation-test
  (testing "validates different payload types"
    (is (sut/validate-command {:ol.vinyl/command :vlcj.controls-api/set-repeat
                               :repeat? true}))
    (is (sut/validate-command {:ol.vinyl/command :vlcj.controls-api/set-time
                               :time-ms 5000}))
    (is (sut/validate-command {:ol.vinyl/command :vlcj.controls-api/set-position
                               :position-ms 0.5}))
    (is (sut/validate-command {:ol.vinyl/command :vlcj.audio-api/set-channel
                               :channel :audio-channel/left}))
    (is (sut/validate-command {:ol.vinyl/command :playback/move
                               :from 0 :to 3}))))

(deftest resolve-alias-test
  (is (= {:ol.vinyl/command :vlcj.audio-api/mute}
         (sut/resolve-alias {:ol.vinyl/command :mixer/mute})))

  (is (= {:ol.vinyl/command :vlcj.controls-api/skip-time :delta-ms 0}
         (sut/resolve-alias {:ol.vinyl/command :playback/skip-time :delta-ms 0})))

  (is (= {:ol.vinyl/command :playback/stop}
         (sut/resolve-alias {:ol.vinyl/command :playback/stop}))))
