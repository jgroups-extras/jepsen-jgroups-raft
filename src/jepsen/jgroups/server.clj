(ns jepsen.jgroups.server
  "Utilities for managing the server.
  This will transfer the necessary resources to the node, install JDK 17, and start a daemon running
  the application.

  The application is available in the folder `java` at the project root. Which is a replicated state
  machine writing values to a map. We build the project with lein and upload the jar to the remote
  nodes. More info:

  * [Running jgroups-raft as a service](http://belaban.blogspot.com/2020/12/running-jgroups-raft-as-service.html)"
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :refer [sh]]
    [clojure.string :as str]
    [clojure.tools.logging :refer :all]
    (jepsen
      [control :as c])
    [jepsen.control.util :as cu]
    [jepsen.core :as jepsen]
    [jepsen.db :as db]
    [jepsen.os.debian :as debian]))

(def dir "/opt/raft")
(def remote-jar (str dir "/server.jar"))
(def remote-props-file (str dir "/raft.xml"))
(def log-file (str dir "/server.log"))
(def pid-file (str dir "/server.pid"))
(def local-server "server")
(def local-props-file (str local-server "/resources/raft.xml"))
(def local-server-jar (str local-server "/target/server.jar"))

(defn install-jdk17!
  "Installs an openjdk jdk17."
  []
  (c/su
    (debian/install [:openjdk-17-jdk])))

(defn build-server!
  "Build the server jar."
  [test node]
  (when (= node (jepsen/primary test))
    (when-not (.exists (io/file local-server-jar))
      (info "Building server jar")
      (let [{:keys [exit out err]} (sh "lein" "uberjar" :dir local-server)]
        (info out)
        (info err)
        (info exit)
        (assert (zero? exit))))))

(defn install-server!
  "Install the server in the remote node."
  []
  (c/exec :mkdir :-p dir)
  (c/cd dir
        (c/upload (.getCanonicalPath (io/file local-server-jar)) remote-jar)))

(defn start!
  "Start the ReplicatedStateMachineDemo in listen mode."
  [test node]
  (c/cd dir
        (let [members (->> (:nodes test)
                           (str/join ","))]
          (info "Starting node" node "with members" members)
          (cu/start-daemon! {:chdir dir
                             :logfile log-file
                             :pidfile pid-file}
                            "/usr/bin/java"
                            :-jar remote-jar
                            :--members members
                            :-n node
                            :-p remote-props-file
                            :> log-file))))

(defn stop!
  "Stop the ReplicatedStateMachineDemo."
  [node]
  (info "Stopping node " node)
  (c/cd dir
        (c/su
          (cu/stop-daemon! pid-file))))

(defn db
  "Installs dependencies and run the StateMachineReplicationDemo"
  []
  (reify db/DB
    (setup! [_ test node]
      (build-server! test node)
      (jepsen/synchronize test)
      (install-jdk17!)
      (c/upload local-props-file remote-props-file)
      (install-server!)
      (jepsen/synchronize test)
      (start! test node)
      (Thread/sleep 15000))

    (teardown! [_ test node]
      (stop! node)
      (c/su
        (c/exec :rm :-rf log-file pid-file remote-jar (str "/tmp/" node ".log"))))

    db/LogFiles
    (log-files [_ test node]
      [log-file])))

