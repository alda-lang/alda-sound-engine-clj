(ns scratch
  (:require [alda.parser :as parser]
            [alda.sound  :as sound]
            [alda.util   :as util]))

(util/set-log-level! :debug)

(comment
  (def organ-score (parser/parse-input "organ: [ c16 d e f ]*3 g2"))
  (sound/export! organ-score "/tmp/foo.mid"))
