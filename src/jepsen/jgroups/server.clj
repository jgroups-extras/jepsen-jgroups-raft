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
    [slingshot.slingshot :refer [throw+]]))

(def dir "/opt/raft")
(def remote-jar (str dir "/server.jar"))
(def remote-props-file (str dir "/raft.xml"))
(def log-file (str dir "/server.log"))
(def pid-file (str dir "/server.pid"))
(def local-server "server")
(def local-props-file (str local-server "/resources/raft.xml"))
(def local-server-jar (str local-server "/target/server.jar"))

(def get-leader-name
  ["/usr/bin/java" "-cp" remote-jar "-Dcom.sun.management.jmxremote" "org.jgroups.tests.Probe" "jmx=RAFT.leader"
   "|"
   "grep" "'leader='"
   "|"
   "sed" "-e" "'s/RAFT={leader=\\([a-z0-9A-Z\\.]\\+\\)}/\\1/g'"])

(def get-current-members
  ["java" "-cp" remote-jar "-Dcom.sun.management.jmxremote" "org.jgroups.tests.Probe" "jmx=RAFT.members"
   "|"
   "grep" "'members='"
   "|"
   "sed" "-e" "'s/RAFT={members=\\[\\(.*\\)\\]}/\\1/g'"
   "|"
   "sed" "-e" "'s/,//g'"])

(def base-client-command
  ["java" "-cp" remote-jar "org.jgroups.raft.client.Client" "-p" "9000"])

(defn add-node-command
  "Command to add a node."
  [node]
  (concat base-client-command ["-add" node]))

(defn remove-node-command
  "Command to remove a node."
  [node]
  (concat base-client-command ["-remove" node]))

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

(defn await-available
  "Blocks until the server port is bound."
  [host port]
  (util/await-fn
    (fn check-port []
      (c/exec :nc :-z host port)
      nil)
    {:log-message (str "Waiting for server " host ":" port)
     :timeout 20000}))

(defn start!
  "Start the server in listen mode."
  [test node]
  (c/cd dir
        ; We are initializing with the dynamic membership value.
        ; We add the current node to the list of members, otherwise,
        ; the node will not be able to start.
        (let [members (->> (concat @(:members test) [node])
                           ; If @(:member test) already contains the current node, we must remove
                           ; the duplication.
                           distinct
                           (str/join ","))]
          (info "Starting node" node "with members" members)
          (let [daemon (cu/start-daemon! {:chdir   dir
                                          :logfile log-file
                                          :pidfile pid-file}
                                         "/usr/bin/java"
                                         :-jar remote-jar
                                         :--members members
                                         :-n node
                                         :-p remote-props-file
                                         :>> log-file)]
            ; We wait for the server to be available before returning.
            ; This can cause a timeout during startup.
            (await-available node 9000)
            (info "Started node" node)
            daemon))))

(defn stop!
  "Stop the ReplicatedStateMachineDemo."
  [node]
  (info "Stopping node" node)
  (c/cd dir
        (c/su
          (cu/stop-daemon! pid-file))))

(defn members
  "Use the probe to retrieve the membership from all nodes."
  [test]
  (info "Selecting first of " @(:members test))
  (->> (c/on-many @(:members test)
                  (-> (apply c/exec* get-current-members)
                      (str/split #"\r\n")))
       (map (fn [[_ v]] (str/join " " v)))
       (map #(str/split % #" "))
       flatten
       (filter #(not (str/blank? %)))
       distinct))

(defn refresh-members!
  "Takes a test and updates the current cluster membership, based on querying
  nodes presently in the test's cluster."
  [test]
  (let [raw-members (members test)
        members (->> raw-members set)]
    (info "Membership retrieved from" (first @(:members test)) "is" (pr-str members))
    (if (some str/blank? members)
      (throw+ {:type ::blank-member-name
               :members raw-members}))
    (do
        ; Observe that a node could have been removed from the cluster,
        ; so it could return an empty list when queried. To avoid confusion,
        ; we join the existing value with the one returned from the node.
        (swap! (:members test) #(set (concat %1 %2)) members))))

(defn addable-nodes
  "What nodes could be added to the cluster"
  [test]
  (remove @(:members test) (:nodes test)))

(defn grow!
  "Adds a random node from the test to the cluster, if possible."
  [test]
  (refresh-members! test)

  (if-let [addable-nodes (seq (addable-nodes test))]
    (let [new-node (rand-nth addable-nodes)]
      (info :adding new-node)

      ; First might not be running?
      (c/on (first @(:members test))
            (->> (add-node-command new-node)
                 (apply c/exec*)))

      ; Update the test map to include the new node.
      (swap! (:members test) conj new-node)

      (c/on-nodes test [new-node]
                  (fn [test node]
                    (info "Growing!" :start! (class (:db test)) (pr-str (:db test)))
                    (db/start! (:db test) test node))))))

(defn shrink!
  "Removes a random node from the cluster, if possible."
  [test]
  (refresh-members! test)

  (if (< (count @(:members test)) 2)
    (do
      (info "Can't shrink, only one node left")
      :too-few-members-to-shrink)

    (let [node (rand-nth (vec @(:members test)))]
      (info :removing node)

      ; Might not be running on the first?
      (c/on (first @(:members test))
            (->> (remove-node-command node)
                 (apply c/exec*)))

      (c/on-nodes test [node]
                  (fn [test node]
                    (db/kill! (:db test) test node)))

      ; Finally, remove the node from the list.
      (swap! (:members test) disj node)
      (info "After removal, membership is" @(:members test))
      node)))

(defrecord Server []
  db/DB
  (setup! [_ test node]
    (build-server! test node)
    (jepsen/synchronize test)
    (install-jdk17!)
    (install-server!)
    (c/upload local-props-file remote-props-file)
    (jepsen/synchronize test)
    (start! test node))

  (teardown! [_ test node]
    (stop! node)
    (c/su
      (c/exec :rm :-rf log-file pid-file remote-jar (str "/tmp/" node ".log"))))

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

  db/Process
  (start! [_ test node]
    (info "Starting node" node)
    (let [daemon-result (start! test node)]
      (info "Starting node" node " with daemon result" daemon-result)
      {:initial-cluster-state (if (= :started daemon-result)
                                :new
                                :existing)
       :nodes @(:members test)}))

  (kill! [_ test node]
    (stop! node))

  db/Pause
  (pause! [_ test node] (c/su (cu/grepkill! :stop "jgroups")))
  (resume! [_ test node] (c/su (cu/grepkill! :cont "jgroups"))))

(defn db
  "Installs dependencies and run the StateMachineReplicationDemo"
  [opts]
  (Server.))

