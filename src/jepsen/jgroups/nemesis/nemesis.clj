(ns jepsen.jgroups.nemesis.nemesis
  "Package responsible for grouping everything related to the nemesis"
  [:require
    [clojure.string :as str]
    [jepsen.nemesis.combined :as nc]
    [jepsen.jgroups.nemesis.membership :as ms]])

(def nemeses
  "All available nemeses."
  #{:pause :kill :partition :member})

(def special-nemeses
  "A map of special nemesis names to a collection of faults."
  {:none []
   :all  [:pause :kill :partition]

   ; Hell might create scenarios a bit hard to cover in implementation, it is still safe, though!
   ; For example, membership changes goes through consensus and thus require a majority to be alive.
   ; We might kill a node while doing an operation and making it impossible to recover full membership.
   ; This is a valid scenario, but it just causes all operations to fail, which is not that interesting.
   ; With some luck, the cluster get back to its feet and we can continue operating.
   :hell [:pause :kill :partition :member]})

(defn parse-nemesis-spec
  "Takes the comma separated list of nemesis names and returns the keyword faults."
  [spec]
  (->> (str/split spec #",")
       (map keyword)
       (mapcat #(get special-nemeses % [%]))))

(defn override-nemesis-package
  "Overriding the Jepsen implementation but removing some nemesis."
  [opts]
  (let [faults   (set (:faults opts [:partition :kill :pause]))
        opts     (assoc opts :faults faults)]
    [(nc/partition-package opts)
     (nc/db-package opts)]))

(defn nemesis-package
  "Prepare the nemesis package, creating nemesis and generators."
  [opts]
  (let [opts (update opts :faults set)]
    (-> (override-nemesis-package opts)
        (concat [(ms/member-package opts)])
        (->> (remove nil?))
        nc/compose-packages)))

(defn setup-nemesis
  "Create all configured nemesis"
  [opts db]
  (nemesis-package
    {:db        db
     :nodes     (:nodes opts)
     :faults    (:nemesis opts)
     :partition {:targets [:primaries :majority :majorities-ring :one]}
     :pause     {:targets [:primaries :minority :one]}
     :kill      {:targets [:primaries :minority :one]}
     :interval  (:interval opts)}))
