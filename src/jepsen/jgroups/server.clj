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
      [control :as c]
      [util :as util])
    [jepsen.control.util :as cu]
    [jepsen.core :as jepsen]
    [jepsen.db :as db]
    [jepsen.os.debian :as debian]
    [slingshot.slingshot :refer [throw+ try+]]))

(def binary "/home/admin/.sdkman/candidates/java/current/bin/java")
(def dir "/home/admin/raft")
(def remote-jar (str dir "/server.jar"))
(def remote-props-file (str dir "/raft.xml"))
(def remote-hosts-file (str dir "/hosts.txt"))
(def log-file (str dir "/server.log"))
(def pid-file (str dir "/server.pid"))
(def local-server "server")
(def local-props-file (str local-server "/resources/raft-aws.xml"))
(def local-server-jar (str local-server "/target/server.jar"))

(def get-leader-name
  [binary "-cp" remote-jar "-Dcom.sun.management.jmxremote" "org.jgroups.tests.Probe" "jmx=RAFT.leader"
   "|"
   "grep" "'leader='"
   "|"
   "sed" "-e" "'s/RAFT={leader=\\([a-z0-9A-Z\\.]\\+\\)}/\\1/g'"])

(defn install-jdk21!
  "Installs an openjdk jdk21."
  []
  (c/su
    (when-not (debian/installed? [:openjdk-21-jdk])
      (debian/install [:openjdk-21-jdk]))))

(defn initial-cluster-file
  "Creates the hosts.txt file for initial membership discovery in JGroups.

  This file is utilized for cluster discovery during start up by the protocol. We write the contents of the
  :nodes vector to the file, separated by newlines. The file is written to the user library folder and is loaded in
  the classpath.
  "
  [test]
  (let [hosts (str (str/join "\n" (:nodes test)) "\n")]
    (info (str "Joining nodes to hosts file" (:nodes test)))
    (cu/write-file! hosts remote-hosts-file)))

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

(defn is-alive?
  "Checks if the node is available at the port."
  [host port]
  (c/exec :nc :-z host port)
  true)

(defn get-pid
  "Gets the pid of the server."
  []
  (Integer/parseInt (c/exec :cat pid-file)))

(defn is-pid-running?
  "Checks if the pid is running."
  []
  (try
    (c/exec :ps :-p (get-pid))
    true
    (catch Exception e
      false)))

(defn is-alive?*
  "Check if the node is available at the port without throwing."
  [host port]
  (try (is-alive? host port) (catch Exception e# false)))

(defn await-available
  "Blocks until the server port is bound."
  [host port]
  (util/await-fn
    (fn check-port []
      (when (is-pid-running?)
        (is-alive? host port))
      nil)
    {:log-message (str "Waiting for server " host ":" port)
     :timeout 20000}))

(defn identify-state-machine
  "Identify the state machine from the workload."
  [opts]
  (case (:workload opts)
    :counter #"counter"
    :election #"election"
    #"register"))

(defn stop!
  "Stop the ReplicatedStateMachineDemo."
  [node]
  (info "Stopping node" node)
  (c/cd dir
        (c/su
          (cu/stop-daemon! binary pid-file))))

(defn definitely-stop!
  "Keep trying to stop the server until nothing is bound to the port."
  [node]
  (util/timeout 20000 (throw+ {:type ::stop-timeout
                               :message (str "Couldn't stop server " node " after 20 seconds")})
                (while (is-alive?* node 9000)
                  (stop! node)
                  (info "Waiting for server" node " to stop")
                  (Thread/sleep 1000))))

(defn start!
  "Start the server in listen mode."
  [test node]
  ; We are initializing with the dynamic membership value.
  ; We add the current node to the list of members, otherwise,
  ; the node will not be able to start.
  (let [members (->> (concat @(:members test) [node])
                     ; If @(:member test) already contains the current node, we must remove
                     ; the duplication.
                     distinct
                     (str/join ","))]
    (info "Starting node" node "with members" members)
    ; If for some reason the PID is still running and the port is bound, we can skip.
    (if (or (is-pid-running?) (is-alive?* node 9000))
      (do
        (info "Process" node "is already running!")
        :already-running)
      (do
        ; We utilize sudo to copy the start-stop-daemon executable into another folder.
        ; This is needed to run the server without sudo.
        (c/su
          (c/exec :ln :-sf (c/lit "/usr/sbin/start-stop-daemon") (c/lit "/usr/bin/start-stop-daemon")))

        (let [daemon (cu/start-daemon! {:chdir   dir
                                        :logfile log-file
                                        :pidfile pid-file}
                                       binary
                                       :-cp remote-hosts-file
                                       :-jar remote-jar
                                       :--members members
                                       :-n node
                                       :-p remote-props-file
                                       :-s (identify-state-machine test)
                                       :>> log-file)]
          (when (= daemon :started)
            ; We wait for the server to be available before returning.
            ; This can cause a timeout during startup.
            (await-available node 9000)
            (info "Started node" node))
          daemon)))))

(defrecord Server []
  db/DB
  (setup! [_ test node]
    (build-server! test node)
    (jepsen/synchronize test)
    ;;(install-jdk21!)
    (install-server!)
    (c/upload local-props-file remote-props-file)
    (initial-cluster-file test)
    (jepsen/synchronize test)
    (start! test node))

  (teardown! [_ test node]
    (info :teardown node)
    (stop! node)
    (c/su
      (c/exec :rm :-rf log-file remote-jar (str "/tmp/" node ".log"))))

  db/LogFiles
  (log-files [_ test node]
    [log-file])

  db/Primary
  (setup-primary! [_ test node])

  (primaries [_ test]
    (->> (c/on-many @(:members test)
                    (-> (apply c/exec* get-leader-name)
                      (str/split #"\r\n")))
         (map (fn [[_ v]] v))
         flatten
         (filter #(not (str/blank? %)))
         (filter #(not (= % "null")))
         distinct))

  db/Kill
  (start! [_ test node]
    (info "Starting node" node)
    (try+
      (let [daemon-result (start! test node)]
        (info "Starting node" node "with daemon result" daemon-result)
        {:initial-cluster-state (if (= :started daemon-result)
                                  :new
                                  :existing)
         :nodes @(:members test)})

      ; This is unexpected, but we can't do much about it.
      ; For some reason the node didn't start.
      (catch [:type :jepsen.util/timeout] e#
        (error "Timeout starting node" node e#)
        {:initial-cluster-state :failed
         :nodes @(:members test)})))

  (kill! [_ test node]
    (definitely-stop! node)
    node)

  db/Pause
  (pause! [_ test node] (c/su (cu/grepkill! :stop "jgroups")))
  (resume! [_ test node] (c/su (cu/grepkill! :cont "jgroups"))))

(defn db
  "Installs dependencies and run the StateMachineReplicationDemo"
  [opts]
  (Server.))

