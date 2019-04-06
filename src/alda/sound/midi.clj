(ns alda.sound.midi
  (:require [taoensso.timbre :as log])
  (:import (java.io File)
           (java.util.concurrent LinkedBlockingQueue)
           (javax.sound.midi MetaEventListener MidiChannel MidiDevice MidiEvent
                             MidiSystem ShortMessage Sequencer Sequence
                             Synthesizer)))

(comment
  "There are 16 channels per MIDI synth (1-16);
   channel 10 is reserved for percussion.")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn new-midi-synth
  []
  (doto ^Synthesizer (MidiSystem/getSynthesizer) .open))

(defn new-midi-sequencer
  []
  (doto (MidiSystem/getSequencer false) .open))

(comment
  "When using separate worker processes, each process can have a single MIDI
   synth instance and use it to play one score at a time.")

(def ^:dynamic *midi-synth* nil)
(def ^:dynamic *midi-sequencer* nil)

;; ref: https://www.csie.ntu.edu.tw/~r92092/ref/midi/
;; also various sources of Java MIDI example programs that use this value to
;; create an "end of track" message
(def ^:const MIDI-END-OF-TRACK 0x2F)

;; ref: https://www.midi.org/specifications-old/item/table-3-control-change-messages-data-bytes-2
(def ^:const MIDI-CHANNEL-VOLUME 7)
(def ^:const MIDI-PANNING        10)

(defn open-midi-synth!
  []
  (alter-var-root #'*midi-synth* (constantly (new-midi-synth))))

(defn open-midi-sequencer!
  []
  (alter-var-root #'*midi-sequencer* (constantly (new-midi-sequencer))))

(comment
  "It takes a second for a MIDI synth/sequencer instance to initialize. This is fine for
   worker processes because each worker only needs to do it once, when the
   process starts. Multiple scores can be played simultaneously by using
   multiple worker processes.

   When we only have a single process and we need multiple MIDI synth/sequencer
   instances and we need to start them on demand, to avoid hiccups and make
   playback more immediate, we can maintain a handful of pre-initialized MIDI
   synths, ready for immediate use.")

(def ^:dynamic *midi-synth-pool* (LinkedBlockingQueue.))
(def ^:const MIDI-SYNTH-POOL-SIZE 4)

(def ^:dynamic *midi-sequencer-pool* (LinkedBlockingQueue.))
(def ^:const MIDI-SEQUENCER-POOL-SIZE 4)

(defn fill-pool!
  [pool size init-fn]
  (dotimes [_ (- size (count pool))]
    (future (.add pool (init-fn)))))

(def fill-midi-synth-pool! #(fill-pool! *midi-synth-pool* MIDI-SYNTH-POOL-SIZE new-midi-synth))
(def fill-midi-sequencer-pool! #(fill-pool! *midi-sequencer-pool* MIDI-SEQUENCER-POOL-SIZE new-midi-sequencer))

(defn drain-pool-excess!
  [pool size]
  (dotimes [_ (- (count pool) size)]
    (future (.close (.take pool)))))

(def drain-excess-midi-synths! #(drain-pool-excess! *midi-synth-pool* MIDI-SYNTH-POOL-SIZE))
(def drain-excess-midi-sequencers! #(drain-pool-excess! *midi-sequencer-pool* MIDI-SEQUENCER-POOL-SIZE))

(defn midi-synth-available?
  []
  (pos? (count *midi-synth-pool*)))

(defn get-midi-synth
  "If the global *midi-synth* has been initialized, then that's the synth you
   get whenever you call this function.

   Otherwise, takes a MIDI synth instance from the pool and makes sure the pool
   is more-or-less topped off."
  []
  (if *midi-synth*
    (do
      (log/debug "Using the global *midi-synth*")
      *midi-synth*)
    (do
      (fill-midi-synth-pool!)
      (drain-excess-midi-synths!)
      (log/debugf "Taking a MIDI synth from the pool (available: %d)"
                  (count *midi-synth-pool*))
      (.take *midi-synth-pool*))))

(defn get-midi-sequencer
  []
  (if *midi-sequencer*
    (do
      (log/debug "Using the global *midi-sequencer*")
      *midi-sequencer*)
    (do
      (fill-midi-sequencer-pool!)
      (drain-excess-midi-sequencers!)
      (log/debugf "Taking a MIDI sequencer from the pool (available: %d)"
                  (count *midi-sequencer-pool*))
      (.take *midi-sequencer-pool*))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- next-available
  "Given a set of available MIDI channels, returns the next available one,
   bearing in mind that channel 10 can only be used for percussion.

   Returns nil if no channels available."
  [channels & {:keys [percussion?]}]
  (first (filter (partial (if percussion? = not=) 9) channels)))

(defn ids->channels
  "Inspects a score and generates a map of instrument IDs to MIDI channels.
   The channel values are maps with keys :channel (the channel number) and
   :patch (the General MIDI patch number)."
  [{:keys [instruments] :as score}]
  (let [channels (atom (apply sorted-set (range 16)))]
    (reduce (fn [result id]
              (let [{:keys [patch percussion?]} (-> id instruments :config)
                    channel (if-let [channel
                                     (next-available @channels
                                                     :percussion? percussion?)]
                              (do
                                (swap! channels disj channel)
                                channel)
                              (throw
                                (Exception. "Ran out of MIDI channels! :(")))]
                (assoc result id {:channel channel
                                  :patch patch
                                  :percussion? percussion?})))
            {}
            (for [[id {:keys [config]}] instruments
                  :when (= :midi (:type config))]
              id))))

(defn map-instruments-to-channels!
  "Sets aside one MIDI channel per instrument in the score.

   Stores the result (a map of instrument IDs to channel numbers) in the audio
   context as :midi-channels.

   Also returns the result."
  [audio-ctx score]
  (let [midi-channels (ids->channels score)]
    (swap! audio-ctx assoc :midi-channels midi-channels)
    midi-channels))

(defn get-midi-synth!
  "If there isn't already a :midi-synth in the audio context, finds an
   available MIDI synth and adds it."
  [audio-ctx]
  (when-not (:midi-synth @audio-ctx)
    (swap! audio-ctx assoc :midi-synth (get-midi-synth))))

(defn close-midi-synth!
  "Closes the MIDI synth in the audio context."
  [audio-ctx]
  (.close ^Synthesizer (:midi-synth @audio-ctx)))

(defn get-midi-sequencer!
  "If there isn't already a :midi-sequencer in the audio context, creates
   a MIDI sequencer and adds it.

   IMPORTANT: `get-midi-synth!` must be called on the context before
   `get-midi-sequencer!`, because `get-midi-sequencer!` also needs to hook up
   the sequencer's transmitter to the synthesizer's receiver."
  [audio-ctx]
  (let [{:keys [midi-synth midi-sequencer]} @audio-ctx]
    (when-not midi-synth
      (throw
        (ex-info
          (str "A MIDI synthesizer is required in the audio context before a "
               "MIDI sequencer can be added.")
          {})))
    (when-not midi-sequencer
      (let [sequencer (get-midi-sequencer)]
        ;; Kill any existing connections, e.g. when re-using the global
        ;; sequencer and synth.
        (doseq [^MidiDevice device [sequencer midi-synth]]
          (doseq [transmitter (.getTransmitters device)]
            (.close transmitter))
          (doseq [receiver (.getReceivers device)]
            (.close receiver)))
        ;; Set the sequencer up to transmit messages to the synthesizer.
        (-> sequencer
            .getTransmitter
            (.setReceiver (.getReceiver ^Synthesizer midi-synth)))
        (swap! audio-ctx assoc :midi-sequencer sequencer)))))

(defn close-midi-sequencer!
  "Closes the MIDI sequencer in the audio context."
  [audio-ctx]
  (.close ^Sequencer (:midi-sequencer @audio-ctx)))

(defn ms->ticks-fn
  "Returns a function that will convert an offset in ms into ticks, based on the
   history of tempo changes in the score and the desired javax.midi.Sequence
   division type and resolution.

   When the division type is SMPTE, the conversion is simple math, and we don't
   need to consider the score at all.

   When the division type is PPQ, however, the logic is more complicated because
   the physical duration of a tick varies depending on the tempo, and this has a
   cascading effect when it comes to scheduling an event. We must consider not
   only the current tempo, but the entire history of tempo changes in the
   score."
  [score division-type resolution]
  (fn [ms]
    (condp contains? division-type
      #{Sequence/SMPTE_24 Sequence/SMPTE_25 Sequence/SMPTE_30
        Sequence/SMPTE_30DROP}
      ;; Example: SMPTE_24 means 24 frames per second, and a resolution of 2
      ;; means 2 ticks per frame. So, if the division type is SMPTE_24 and the
      ;; resolution is 2, then there are 24 x 2 = 48 ticks per second.
      (let [ticks-per-second (* division-type resolution)]
        (-> ms (/ 1000.0) (* ticks-per-second)))

      #{Sequence/PPQ}
      (throw (ex-info "TODO: implement PPQ scheduling" {}))

      ; else
      (throw (ex-info "Unsupported division type."
                      {:division-type division-type
                       :resolution    resolution})))))

(defn load-sequencer!
  [events score]
  (let [{:keys [instruments audio-context]} score
        {:keys [midi-sequencer]} @audio-context
        ;; TODO: Implement PPQ scheduling and use that instead
        division-type Sequence/SMPTE_24
        resolution    2
        sqnc          (Sequence. division-type resolution)
        track         (.createTrack sqnc)
        ms->ticks     (ms->ticks-fn score division-type resolution)]
    ;; Load the sequence into the sequencer.
    (doto midi-sequencer
      (.setSequence sqnc)
      (.setTickPosition 0))

    ;; For each instrument in the score, add an initial event that sets the
    ;; channel to the right instrument patch.
    (let [midi-channels (map-instruments-to-channels! audio-context score)]
      (doseq [{:keys [channel patch]} (set (vals midi-channels))
              :when patch
              :let [message (doto (ShortMessage.)
                              (.setMessage ShortMessage/PROGRAM_CHANGE
                                           channel
                                           (dec patch)
                                           0))]]
        (.add track (MidiEvent. message 0))))

    ;; Add events to the sequence's track.
    (doseq [{:keys [offset instrument duration midi-note volume track-volume
                    panning]
             :as event}
            events]
      (let [{:keys [midi-channels]} @audio-context
            volume                  (* 127 volume)
            channel-number          (-> instrument midi-channels :channel)
            track-volume-message    (doto (ShortMessage.)
                                      (.setMessage ShortMessage/CONTROL_CHANGE
                                                   channel-number
                                                   MIDI-CHANNEL-VOLUME
                                                   (* 127 track-volume)))
            panning-message         (doto (ShortMessage.)
                                      (.setMessage ShortMessage/CONTROL_CHANGE
                                                   channel-number
                                                   MIDI-PANNING
                                                   (* 127 panning)))
            note-on-message         (doto (ShortMessage.)
                                      (.setMessage ShortMessage/NOTE_ON
                                                   channel-number
                                                   midi-note
                                                   volume))
            note-off-message        (doto (ShortMessage.)
                                      (.setMessage ShortMessage/NOTE_OFF
                                                   channel-number
                                                   midi-note
                                                   volume))]
        (doseq [message [track-volume-message panning-message note-on-message]]
          (.add track (MidiEvent. message (ms->ticks offset))))
        (.add track (MidiEvent. note-off-message
                                (ms->ticks (+ offset duration))))))))

(defn all-sound-off!
  [audio-ctx]
  (let [stop-channel! (fn [^MidiChannel channel]
                        (.allNotesOff channel)
                        (.allSoundOff channel))
        {:keys [midi-synth midi-sequencer]} @audio-ctx]
    (.stop ^Sequencer midi-sequencer)
    (->> midi-synth .getChannels (pmap stop-channel!) doall)))

(defn play-sequence!
  "Plays the sequence currently loaded into the MIDI sequencer.

   Calls `done-fn` when the sequence is done playing."
  [audio-ctx done-fn]
  (let [{:keys [midi-sequencer]} @audio-ctx]
    (doto midi-sequencer
      (.addMetaEventListener
        (proxy [MetaEventListener] []
          (meta [event]
            (when (= (.getType event) MIDI-END-OF-TRACK)
              (log/debug "Handling MIDI-END-OF-TRACK metamessage.")
              (done-fn)))))
      (.setTickPosition 0)
      .start)))

(defn export-midi-file!
  [sqnc filename]
  (MidiSystem/write sqnc 0 (File. filename)))

