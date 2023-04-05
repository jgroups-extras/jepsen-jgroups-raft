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
   :all  [:pause :kill :partition :member]})

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
