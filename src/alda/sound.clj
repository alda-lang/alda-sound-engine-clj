(ns alda.sound
  (:require [alda.sound.midi :as    midi]
            [alda.util       :refer (parse-time
                                     pdoseq-block
                                     parse-position)]
            [taoensso.timbre :as    log]))

(defn new-audio-context
  []
  (atom
    {:audio-types #{}}))

(defn set-up?
  [{:keys [audio-context] :as score} audio-type]
  (contains? (:audio-types @audio-context) audio-type))

(defmulti set-up-audio-type!
  (fn [score audio-type] audio-type))

(defmethod set-up-audio-type! :default
  [score audio-type]
  (log/errorf "No implementation of set-up-audio-type! defined for type %s"
              audio-type))

(defmethod set-up-audio-type! :midi
  [{:keys [audio-context] :as score} _]
  (log/debug "Setting up MIDI...")
  (midi/get-midi-synth! audio-context)
  (midi/get-midi-sequencer! audio-context))

(declare determine-audio-types)

(defn set-up!
  "Does any necessary setup for one or more audio types.
   e.g. for MIDI, create and open a MIDI synth."
  ([score]
   (set-up! score (determine-audio-types score)))
  ([{:keys [audio-context] :as score} audio-type]
   (if (coll? audio-type)
     (pdoseq-block [a-t audio-type]
       (set-up! score a-t))
     (when-not (set-up? score audio-type)
       (swap! audio-context update :audio-types conj audio-type)
       (set-up-audio-type! score audio-type)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti tear-down-audio-type!
  (fn [score audio-type] audio-type))

(defmethod tear-down-audio-type! :default
  [score audio-type]
  (log/errorf "No implementation of tear-down! defined for type %s" audio-type))

(defmethod tear-down-audio-type! :midi
  [{:keys [audio-context] :as score} _]
  (log/debug "Closing MIDI...")
  (midi/close-midi-sequencer! audio-context)
  (midi/close-midi-synth! audio-context))

(defn tear-down!
  "Completely clean up after a score.

   Playback may not necessarily be resumed after doing this."
  ([{:keys [audio-context] :as score}]
   ;; Do any necessary clean-up for each audio type.
   ;; e.g. for MIDI, close the MidiSynthesizer.
   (tear-down! score (determine-audio-types score)))
  ([{:keys [audio-context] :as score} audio-type]
   (if (coll? audio-type)
     (pdoseq-block [a-t audio-type]
       (tear-down! score a-t))
     (when (set-up? score audio-type)
       (swap! audio-context update :audio-types disj audio-type)
       (tear-down-audio-type! score audio-type)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti stop-playback-for-audio-type!
  (fn [score audio-type] audio-type))

(defmethod stop-playback-for-audio-type! :default
  [score audio-type]
  (log/errorf
    "No implementation of stop-playback-for-audiotype! defined for type %s"
    audio-type))

(defmethod stop-playback-for-audio-type! :midi
  [{:keys [audio-context] :as score} _]
  (log/debug "Stopping MIDI playback...")
  (midi/all-sound-off! audio-context))

(defn stop-playback!
  "Stop playback, but leave the score in a state where playback can be resumed."
  ([{:keys [audio-context] :as score}]
   (stop-playback! score (determine-audio-types score)))
  ([{:keys [audio-context] :as score} audio-type]
   (if (coll? audio-type)
     (pdoseq-block [a-t audio-type]
       (stop-playback! score a-t))
     (when (set-up? score audio-type)
       (stop-playback-for-audio-type! score audio-type)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- score-length
  "Given an event set from a score, calculates the length of the score in ms."
  [event-set]
  (let [events   (filter :duration event-set)
        note-end (fn [{:keys [offset duration] :as note}]
                   (+ offset duration))]
    (if (and events (not (empty? events)))
      (apply max (map note-end events))
      0)))

(defn determine-audio-types
  [{:keys [instruments] :as score}]
  (set (for [[id {:keys [config]}] instruments]
         (:type config))))

(def ^:dynamic *play-opts* {})

(defmacro with-play-opts
  "Apply `opts` as overrides to *play-opts* when executing `body`"
  [opts & body]
  `(binding [*play-opts* (merge *play-opts* ~opts)]
     ~@body))

(defn- lookup-time [markers pos]
  (let [pos (if (string? pos)
              (parse-position pos)
              pos)]
    (cond (keyword? pos)
          (or (markers (name pos))
              (throw (Exception. (str "Marker " pos " not found."))))

          (or (number? pos) (nil? pos))
          pos

          :else
          (throw (Exception.
                   (str "Do not support " (type pos) " as a play time."))))))

(defn start-finish-times [{:keys [from to]} markers]
  (let [[start end] (map (partial lookup-time markers) [from to])]
    (log/debugf "from: %s => %s     to: %s => %s" from start to end)
    [start end]))

(defn earliest-offset
  [event-set]
  (->> (map :offset event-set)
       (apply min Long/MAX_VALUE)
       (max 0)))

(defn shift-events
  [events offset cut-off]
  (log/debugf "Keeping events between %s and %s." offset cut-off)
  (let [offset  (or offset 0)
        cut-off (when cut-off (- cut-off offset))
        keep?   (if cut-off
                  #(and (<= 0 %) (> cut-off %))
                  #(<= 0 %))]
    (->> (sequence (comp (map #(update-in % [:offset] - offset))
                         (filter (comp keep? :offset)))
                   events)
         (sort-by :offset))))

(defn create-sequence!
  [score & [event-set]]
  (let [score       (update score :audio-context #(or % (new-audio-context)))
        _           (log/debug "Setting up audio types...")
        _           (set-up! score)
        _           (log/debug "Determining events to schedule...")
        _           (log/debug (str "*play-opts*: " *play-opts*))
        [start end] (start-finish-times *play-opts* (:markers score))
        start'      (cond
                      ;; If a "from" offset is explicitly provided, use the
                      ;; calculated start offset derived from it.
                      (:from *play-opts*)
                      start

                      ;; If an event-set is provided, use the earliest event's
                      ;; offset.
                      event-set
                      (earliest-offset event-set)

                      :else
                      start)
        events      (-> (or event-set (:events score))
                        (shift-events start' end))]
    (midi/load-sequencer! events score)))

(defn play!
  "Plays an Alda score, optionally from given start/end marks determined by
   *play-opts*.

   Optionally takes as a second argument a set of events to play (which could
   be pre-filtered, e.g. for playing only a portion of the score).

   In either case, the offsets of the events to be played are shifted back such
   that the earliest event's offset is 0 -- this is so that playback will start
   immediately.

   Returns a result map containing the following values:

     :score    The full score being played.

     :stop!    A function that, when called mid-playback, will stop any further
               events from playing.

     :wait     A function that will sleep for the duration of the score. This is
               useful if you want to playback asynchronously, perform some
               actions, then wait until playback is complete before proceeding."
  [score & args]
  (let [{:keys [one-off? async?]} *play-opts*
        score (update score :audio-context #(or % (new-audio-context)))
        wait  (promise)]
    (log/debug "Creating sequence...")
    (apply create-sequence! score args)
    (log/debug "Playing sequence...")
    (midi/play-sequence! (:audio-context score) #(deliver wait :done))
    (cond
      (and one-off? async?)       (future @wait (tear-down! score))
      (and one-off? (not async?)) (do @wait (tear-down! score))
      (not async?)                @wait)
    {:score score
     :stop! #(if one-off?
               (tear-down! score)
               (stop-playback! score))
     :wait  #(deref wait)}))

(defn export!
  "Exports an Alda score to a MIDI file."
  [score output-filename]
  (let [{:keys [audio-context] :as score}
        (update score :audio-context #(or % (new-audio-context)))]
    (log/debug "Creating sequence...")
    (create-sequence! score)
    (let [sqnc (.getSequence (:midi-sequencer @audio-context))]
      (log/debugf "Exporting score to MIDI file: %s" output-filename)
      (midi/export-midi-file! sqnc output-filename)
      (log/debug "Score MIDI export done."))))
