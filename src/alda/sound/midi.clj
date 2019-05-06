(ns alda.sound.midi
  (:require [taoensso.timbre :as log])
  (:import (java.io File)
           (java.nio ByteBuffer)
           (java.util Arrays)
           (java.util.concurrent LinkedBlockingQueue)
           (javax.sound.midi MetaEventListener MetaMessage MidiChannel
                             MidiDevice MidiEvent MidiSystem Receiver
                             ShortMessage Sequencer Sequence Synthesizer
                             Transmitter)))

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
;;
;; There are also various sources of Java MIDI example programs that use the
;; value 0x2F to create an "end of track" message.
(def ^:const MIDI-SET-TEMPO 0x51)
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
    (future (.close ^MidiDevice (.take pool)))))

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
          (doseq [^Transmitter transmitter (.getTransmitters device)]
            (.close transmitter))
          (doseq [^Receiver receiver (.getReceivers device)]
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

(defn- most-recent-entry
  [tempo-itinerary offset-ms]
  (assert (seq tempo-itinerary) "Tempo itinerary is empty.")
  (->> tempo-itinerary
       (take-while #(<= (:ms %) offset-ms))
       last))

(defn ppq-ms->ticks
  "Converts an offset in ms into ticks.

   NB: the length of a tick varies depending on the current tempo. Therefore, an
   offset expressed in ticks is influenced by the history of tempos up to that
   moment in time."
  [tempo-itinerary offset-ms resolution]
  (if (zero? offset-ms)
    0
    (let [{:keys [ms tempo ticks]}
          (most-recent-entry tempo-itinerary offset-ms)

          ;; source: https://stackoverflow.com/a/2038364/2338327
          ms-per-tick
          (/ 60000.0 (* tempo resolution))

          ms-delta
          (- offset-ms ms)

          ticks-delta
          (/ ms-delta ms-per-tick)]
      (+ ticks ticks-delta))))

(defn tempo-itinerary
  "Returns a sequence of maps, each of which represents a tempo value at a point
   in time. The tempo is expressed in BPM, and the point in time is expressed
   both in ms and in ticks."
  [score resolution]
  (let [tempo-values (sort (:tempo/values score))]
    (assert (zero? (ffirst tempo-values))
            "There must be an initial tempo value at 0 ms.")
    (loop [itinerary [], tempo-values tempo-values]
      (let [[[offset-ms tempo] & more] tempo-values]
        (if offset-ms
          (recur (conj itinerary
                       {:ms    offset-ms
                        :tempo tempo
                        :ticks (ppq-ms->ticks itinerary offset-ms resolution)})
                 more)
          itinerary)))))

(defn ms->ticks-fn
  "Returns a function that will convert an offset in ms into ticks, based on the
   history of tempo changes in the score and the desired javax.midi.Sequence
   division type and resolution.

   When the division type is SMPTE, the conversion is simple math, and we don't
   need to consider the tempo itinerary at all.

   When the division type is PPQ, however, the logic is more complicated because
   the physical duration of a tick varies depending on the tempo, and this has a
   cascading effect when it comes to scheduling an event. We must consider not
   only the current tempo, but the entire history of tempo changes in the
   score."
  [tempo-itinerary division-type resolution]
  (condp contains? division-type
    #{Sequence/SMPTE_24 Sequence/SMPTE_25 Sequence/SMPTE_30
      Sequence/SMPTE_30DROP}
    ;; Example: SMPTE_24 means 24 frames per second, and a resolution of 2
    ;; means 2 ticks per frame. So, if the division type is SMPTE_24 and the
    ;; resolution is 2, then there are 24 x 2 = 48 ticks per second.
    (let [ticks-per-second (* division-type resolution)]
      (fn [ms]
        (-> ms (/ 1000.0) (* ticks-per-second))))

    #{Sequence/PPQ}
    (fn [ms]
      (ppq-ms->ticks tempo-itinerary ms resolution))

    ; else
    (throw (ex-info "Unsupported division type."
                    {:division-type division-type
                     :resolution    resolution}))))

(defn- max-byte-array-value
  "Returns the maximum value that can be represented in `num-bytes` bytes.

   (max-byte-array-value 1) ;;=> 255
   (max-byte-array-value 2) ;;=> 65535
   (max-byte-array-value 3) ;;=> 16777215
   etc."
  [num-bytes]
  (-> 2 (Math/pow (* 8 num-bytes)) Math/round dec))

(defn set-tempo-message
  "In a \"set tempo\" metamessage, the desired tempo is expressed not in beats
   per minute (BPM), but in microseconds per quarter note (I'll abbreviate this
   as \"uspq\").

   There are 60 million microseconds in a minute, therefore the formula to
   convert BPM => uspq is 60,000,000 / BPM.

   Example conversion: 120 BPM / 60,000,000 = 500,000 uspq.

   The slower the tempo, the lower the BPM and the higher the uspq.

   For some reason, the MIDI spec limits the number of bytes available to
   express this number to a maximum of 3 bytes, even though there are extremely
   slow tempos (<4 BPM) that, when expressed in uspq, are numbers too large to
   fit into 3 bytes. Effectively, this means that the slowest supported tempo is
   about 3.58 BPM. That's extremely slow, so it probably won't cause any
   problems in practice, but this function will throw an assertion error below
   that tempo, so it's worth mentioning.

   ref:
   https://www.recordingblogs.com/wiki/midi-set-tempo-meta-message
   https://www.programcreek.com/java-api-examples/?api=javax.sound.midi.MetaMessage
   https://docs.oracle.com/javase/7/docs/api/javax/sound/midi/MetaMessage.html
   https://stackoverflow.com/a/22798636/2338327"
  [tempo]
  (let [uspq (quot 60000000 tempo)
        ;; Technically, a tempo less than ~3.58 BPM translates into a number of
        ;; microseconds per quarter note larger than 3 bytes can hold.
        ;;
        ;; Throwing an assertion error here because it's better than overflowing
        ;; and secretly setting the tempo to an unexpected value.
        _    (assert (<= uspq (max-byte-array-value 3))
                     "MIDI does not support tempos slower than about 3.58 BPM.")
        data (-> (ByteBuffer/allocate 4)
                 (.putInt uspq)
                 .array
                 ;; Truncate the 4-byte array to 3 bytes, which is the upper
                 ;; limit for the data payload of a "set tempo" message
                 ;; according to the MIDI spec.
                 (Arrays/copyOfRange 1 4))]
    (MetaMessage. MIDI-SET-TEMPO data 3)))

(defn load-sequencer!
  [events score]
  (let [{:keys [audio-context]} score
        {:keys [midi-sequencer]} @audio-context
        division-type   Sequence/PPQ
        ;; This ought to allow for notes as fast as 512th notes at a tempo of
        ;; 120 bpm, way faster than anyone should reasonably need.
        ;;
        ;; (4 PPQ = 4 ticks per quarter note, i.e. 16th note resolution; so
        ;;  128 PPQ = 512th note resolution)
        resolution      128
        sqnc            (Sequence. division-type resolution)
        track           (.createTrack sqnc)
        tempo-itinerary (tempo-itinerary score resolution)
        ms->ticks       (ms->ticks-fn tempo-itinerary division-type resolution)]
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

    (doseq [{:keys [tempo ticks]} tempo-itinerary]
      (.add track (MidiEvent. (set-tempo-message tempo) ticks)))

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
        {:keys [^Synthesizer midi-synth ^Sequencer midi-sequencer]} @audio-ctx]
    (.stop midi-sequencer)
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

