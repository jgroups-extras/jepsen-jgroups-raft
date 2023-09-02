(defproject jgroups.raft.server "0.1.0-SNAPSHOT"
  :description "JGroups RAFT server"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [jepsen "0.3.3"]
                 [org.jgroups/jgroups "5.2.18.Final"]
                 [org.jgroups/jgroups-raft "1.0.12.Final-SNAPSHOT"]]
  :repl-options {:init-ns jgroups.raft.server}
  :aot [jgroups.raft.server]
  :main jgroups.raft.server
  :profiles {:uberjar {:uberjar-name "server.jar", :resource-paths ["resources"]}}
  :java-source-paths ["../java"]
  :source-paths ["src"]
  :resource-paths ["resources"])
