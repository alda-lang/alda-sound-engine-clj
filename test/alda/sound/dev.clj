(ns alda.sound.dev
  (:require [alda.now  :refer :all]
            [alda.lisp :refer :all]))

(comment
  (set-up! (atom (score)) :midi)

  (play!
    (part "accordion"
      (note (pitch :c) (duration (note-length 8)))
      (note (pitch :d))
      (note (pitch :e :flat))
      (note (pitch :f))
      (note (pitch :g))
      (note (pitch :a :flat))
      (note (pitch :b))
      (octave :up)
      (note (pitch :c)))))
