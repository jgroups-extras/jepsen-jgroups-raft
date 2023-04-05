(defproject jepsen.jgroups.raft "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :main jepsen.jgroups.raft
  :jvm-opts ["-Xmx26g"]
  :java-source-paths ["java"]
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [dom-top "1.0.8"]
                 [jepsen "0.3.1"]
                 [org.jgroups/jgroups "5.2.12.Final"]
                 [org.jgroups/jgroups-raft "1.0.10.Final"]]
  :repl-options {:init-ns jepsen.jgroups.raft})
