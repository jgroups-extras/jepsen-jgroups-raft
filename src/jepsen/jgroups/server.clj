(ns jepsen.jgroups.server
  "Utilities for managing the server.
  This will transfer the necessary resources to the node, install JDK 11, and start a daemon running
  the application.

  The required dependencies are the JGroups and JGroups-RAFT jars, and the XML configuration. The dependencies
  are all located in the `resources` folder. The application we run is the `ReplicatedStateMachineDemo`,
  available in the JGroups-RAFT jar. More info:

  * [Running jgroups-raft as a service](http://belaban.blogspot.com/2020/12/running-jgroups-raft-as-service.html)"
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :refer :all]
    (jepsen
      [control :as c])
    [jepsen.control.util :as cu]
    [jepsen.core :as jepsen]
    [jepsen.db :as db]
    [jepsen.os.debian :as debian]))

(def dir "/opt/raft")
(def server-libs (str dir "/libs"))
(def local-libs "resources/libs")
(def props-file (str dir "/raft.xml"))
(def log-file (str dir "/server.log"))
(def pid-file (str dir "/server.pid"))

(defn download-libs!
  "Download the necessary jars to run the demo, which are JGroups and JGroups-RAFT"
  []
  (info "Installing required jars.")
  (c/exec :mkdir :-p server-libs)
  (c/cd dir
        (c/upload (.getCanonicalPath (io/file (str local-libs "/jgroups.jar"))) (str server-libs "/jgroups.jar"))
        (c/upload (.getCanonicalPath (io/file (str local-libs "/raft.jar"))) (str server-libs "/raft.jar"))))

(defn start!
  "Start the ReplicatedStateMachineDemo in listen mode."
  [test node]
  (c/cd dir
        (let [members (->> (:nodes test)
                           (str/join ","))]
          (info "Starting node " node " with members " members)
          (cu/start-daemon! {:chdir dir
                             :logfile log-file
                             :pidfile pid-file}
                            "/usr/bin/java"
                            (str "-Draft_members=" members)
                            :-cp (str server-libs "/*")
                            "org.jgroups.raft.demos.ReplicatedStateMachineDemo"
                            :-name node
                            :-port 9000
                            :-props props-file
                            :-nohup :-listen
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
      (download-libs!)
      (c/upload-resource! "raft.xml" props-file)
      (debian/install-jdk11!)
      (jepsen/synchronize test)
      (start! test node)
      (Thread/sleep 15000))

    (teardown! [_ test node]
      (stop! node)
      (c/su
        (c/exec :rm :-rf log-file pid-file server-libs)))

    db/LogFiles
    (log-files [_ test node]
      [log-file])))

