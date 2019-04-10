# CHANGELOG

## 1.2.0 (2019-04-10)

* `alda.sound` now has an `export!` function, which takes a score and an output
  filename and exports the score as a MIDI file.

* When a score is played or exported, the MIDI sequence division type and
  resolution are now PPQ and 128, whereas they were SMPTE and 2 before. Using
  PPQ allows us to export MIDI files that are much more useful when imported
  into other music software such as sheet music editors.

## 1.1.0 (2019-01-28)

* (BREAKING CHANGE) Removed the `alda.now` namespace. Alda no longer depends on
  it internally, and it is no longer the recommended way to do live-coding in
  Alda.

  The new recommended way for Clojure programmers to live code music with Alda
  is [alda-clj].

[alda-clj]: https://github.com/daveyarwood/alda-clj

## 1.0.0 (2018-10-28)

* Major overhaul of the event scheduling system, which used to leverage JSyn as
  a scheduler. We now play scores by creating Java MIDI Sequences and playing
  them via a Sequencer and Synthesizer.

* BREAKING CHANGES:
  * Removed public vars from `alda.sound`: `*synthesis-engine*`,
    `new-synthesis-engine`, `start-synthesis-engine!`, `refresh!`,
    `refresh-audio-type!`, `start-event!`, `stop-event!`, `schedule-event!`,
    `schedule-events!`

  * Removed `:synthesis-engine` from the audio context.

  * Removed the `start-event!` and `stop-event!` lifecycle events, as this
    functionality is now handled by the sequencer.

  * Removed the `refresh!` lifecycle event, in the interest of trying to keep
    things idempotent. The idea is that we don't want to tolerate having to
    "reset" an audio context between playing scores. Prior to this release, we
    were using `refresh!` to re-load the MIDI instruments of the score into the
    synthesizer. Now, we do this by making the program change events part of the
    MIDI sequences we create.

  * Removed public vars from `alda.sound.midi`: `load-instruments!`,
    `protection-key-for`, `protect-note!`, `unprotect-note!`, `note-reserved?`,
    `play-note!`, `stop-note!`

  * `alda.sound.midi/load-instruments!` replaced by a more granular function
    called `map-instruments-to-channels!`

  * Real-time playback functionality in `alda.sound.midi` (`play-note!`,
    `stop-note!`) has been removed; the sequencer handles all of that now.

* Non-breaking changes:
  * Added `:midi-sequencer` to the audio context.

  * Usage of `alda.sound/schedule-events!` in `alda.sound.play!` has been
    replaced by usage of new functions, `alda.sound/create-sequence!` and
    `alda.sound.midi/play-sequence!`.

## 0.4.0 (2018-06-22)

* Removed stale references to scheduled functions, [for compatibility with
  alda/core 0.4.0](https://github.com/alda-lang/alda-core/pull/65).

## 0.3.2 (2018-02-03)

* Fixed a bug where `:from` playback option was being ignored when an
  `event-set` argument was included to `play!`.

* Added some debug logging around `*play-opts*` and playback start/end offsets.

## 0.3.1 (2017-06-22)

* Fixed a bug in the MIDI `all-sound-off!` function where not every channel was
  necessarily being stopped because of the laziness of `pmap`. Adding in a
  `doall` forces evaluation of the "stop sound" function on every channel.

  Also, added in a call to `MidiChannel.allNotesOff()` in addition to the
  `MidiChannel.allSoundOff()` that we already had, to be extra sure that all
  sound will stop.

## 0.3.0 (2017-06-11)

* There is now a distinction between `tear-down!` and `stop-playback!`.

  `stop-playback!` can be used in contexts where you want to stop playback of a
  score and you might want to start playing the score again after that. It stops
  playback, but leaves the audio context in a state where it can continue to
  play.

* (BREAKING CHANGES) Cleaned up a bunch of code. In particular, a lot of
  functions in `alda.sound` that took an `audio-ctx` and optionally a score now
  just take a score that contains an `:audio-context`. This is a simpler API.

* (BREAKING CHANGE) The score returned by `alda.sound/play!` is now guaranteed
  to contain an `:audio-context`. If there isn't one on the score that's passed
  in, a new audio context is generated and `assoc`'d onto the score.

## 0.2.0 (2017-05-17)

* (BREAKING CHANGE) `alda.sound/play!` now returns a map containing multiple
  values:

     :score    The full score being played.

     :stop!    A function that, when called mid-playback, will stop any further
               events from playing.

     :wait     A function that will sleep for the duration of the score. This is
               useful if you want to playback asynchronously, perform some
               actions, then wait until playback is complete before proceeding.

  ...whereas before, it returned just the `stop!` function.

* Added a `with-score*` macro to `alda.now`. It behaves just like `with-score`, but returns the last form of its `body` instead of the score.

* (BREAKING CHANGE) `alda.now/play-with-opts!` now returns the `alda.sound/play!` result.

* Fixed a bug where a score didn't technically "end" when it was stopped. By
  that, I mean that the Clojure promise was never being delivered the `:done`
  value, and in some cases, playing a score synchronously could result in
  blocking forever. This is fixed now.

## 0.1.2 (2017-02-19)

* Fixed a minor bug re: interaction between using `play!` with an `event-set` and adjusting start/end bounds via the `from` and `to` options.

## 0.1.1 (2017-02-09)

* Added a `play-with-opts!` function that is like `play!`, but allows the caller to override the playback options used when playing the score.

