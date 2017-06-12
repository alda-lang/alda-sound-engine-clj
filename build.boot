(set-env!
  :source-paths   #{"src"}
  :dependencies   '[; dev
                    [adzerk/bootlaces            "0.1.13" :scope "test"]
                    [alda/core                   "0.2.2"  :scope "test"]
                    [org.clojure/tools.namespace "0.2.11" :scope "test"]

                    ; dependencies
                    [com.taoensso/timbre "4.10.0"]
                    [com.jsyn/jsyn       "20170328"]])

(require '[adzerk.bootlaces :refer :all])

(def ^:const +version+ "0.2.0")

(bootlaces! +version+)

(task-options!
  pom     {:project 'alda/sound-engine-clj
           :version +version+
           :description "A Clojure implementation of an Alda sound engine"
           :url "https://github.com/alda-lang/alda-sound-engine-clj"
           :scm {:url "https://github.com/alda-lang/alda-sound-engine-clj"}
           :license {"name" "Eclipse Public License"
                     "url" "http://www.eclipse.org/legal/epl-v10.html"}}

  jar     {:file "alda-sound-engine-clj.jar"}

  install {:pom "alda/sound-engine-clj"}

  target  {:dir #{"target"}})

(deftask package
  "Builds jar file."
  []
  (comp (pom)
        (jar)))

(deftask deploy
  "Builds jar file, installs it to local Maven repo, and deploys it to Clojars."
  []
  (comp (package) (install) (push-release)))

