(defproject jepsen.jgroups.raft "0.1.0-SNAPSHOT"
  :description "Jepsen tests for JGroups Raft"
  :url "https://github.com/jgroups-extras/jepsen-jgroups-raft"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :main jepsen.jgroups.raft
  :jvm-opts ["-Xmx26g" "-Djava.awt.headless=true" "-server"]
  :java-source-paths ["java"]
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [dom-top "1.0.8"]
                 [jepsen "0.3.8"]
                 [org.jgroups/jgroups "5.4.5.Final"]
                 [org.jgroups/jgroups-raft "1.1.0.Final-SNAPSHOT"]]
  :repl-options {:init-ns jepsen.jgroups.raft})
