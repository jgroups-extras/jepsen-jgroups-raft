(defproject jgroups.raft.server "0.1.0-SNAPSHOT"
  :description "JGroups RAFT server"
  :url "https://github.com/jgroups-extras/jepsen-jgroups-raft"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [jepsen "0.3.5"]
                 [org.jgroups/jgroups "5.3.1.Final"]
                 [org.jgroups/jgroups-raft "1.0.12.Final"]]
  :repl-options {:init-ns jgroups.raft.server}
  :aot [jgroups.raft.server]
  :main jgroups.raft.server
  :profiles {:uberjar {:uberjar-name "server.jar", :resource-paths ["resources"]}}
  :java-source-paths ["../java"]
  :jvm-opts ["-Djava.awt.headless=true" "-server"]
  :source-paths ["src"]
  :resource-paths ["resources"])
