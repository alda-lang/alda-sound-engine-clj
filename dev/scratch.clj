(ns scratch
  (:require [alda.parser    :as parser]
            [alda.sound     :as sound]
            [alda.util      :as util]
            [clojure.string :as str])
  (:import [java.nio.file Paths Files]))

(util/set-log-level! :debug)

(defn example-path
  [& [example-name]]
  (Paths/get
    (System/getProperty "user.home")
    (into-array
      String
      (concat
        ["code" "alda-core" "examples"]
        (when example-name [(str example-name ".alda")])))))

(defn read-file
  [path]
  (String. (Files/readAllBytes path)))

(defn example-score
  [example-name]
  (-> example-name example-path read-file parser/parse-input))

(defn play-example!
  [example-name]
  (sound/play! (example-score example-name)))

(defn export-example!
  [example-name]
  (sound/export! (example-score example-name)
                 (str "/tmp/" example-name ".mid")))

(defn export-examples!
  []
  (doseq [path (Files/newDirectoryStream (example-path))
          :let [example-name (-> path
                                 .getFileName
                                 str
                                 (str/split #"\.")
                                 first)]]
    (prn :exporting example-name)
    (export-example! example-name)))


(comment
  (do
    (require 'scratch :reload)
    (in-ns 'scratch))

  (def organ-score (parser/parse-input "organ: [ c16 d e f ]*3 g2"))
  (sound/play! organ-score)
  (sound/export! organ-score "/tmp/foo.mid")

  (export-examples!)

  (play-example! "hello_world")
  (export-example! "hello_world")

  (play-example! "phase")
  (export-example! "phase")

  (-> (slurp "/tmp/charge.alda")
      parser/parse-input
      (sound/export! "/tmp/charge.mid")))
