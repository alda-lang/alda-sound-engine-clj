# CHANGELOG

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

