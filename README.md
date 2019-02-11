# alda-sound-engine-clj

A Clojure implementation of an [Alda](https://github.com/alda-lang/alda) sound
engine.

The `alda.sound` namespace functions as a library focused on playing a fully
realized Alda score map.

## Development

### alda.sound

The `alda.sound` namespace handles the implementation details of playing the
score.

There is an "audio type" abstraction which refers to different ways to generate
audio, e.g. MIDI, waveform synthesis, samples, etc. Adding a new audio type is
as simple as providing an implementation for each of the multimethods in this
namespace, i.e. `set-up-audio-type!`, `refresh-audio-type!`,
`tear-down-audio-type!`, `start-event!` and `stop-event!`.

The `play!` function handles playing an entire Alda score. It does this by using
a [Sequencer][jsm-sequencer] to schedule all of the note events to be played,
and a [Synthesizer][jsm-synth] to play them. The time that each event starts and
stops is determined by its `:offset` and `:duration`.

What happens, exactly, at the beginning and end of an event, is determined by
the `start-event!`/`stop-event!` implementations for each instrument type. For
example, for MIDI instruments, `start-event!` sets parameters such as volume and
panning and sends a MIDI note-on message at the beginning of the score plus
`:offset` milliseconds, and `stop-event!` sends a note-off message `:duration`
milliseconds later.

[jsm-sequencer]: https://docs.oracle.com/javase/7/docs/api/javax/sound/midi/Sequencer.html
[jsm-synth]: https://docs.oracle.com/javase/7/docs/api/javax/sound/midi/Synthesizer.html

## License

Copyright © 2012-2019 Dave Yarwood et al

Distributed under the Eclipse Public License version 1.0.
