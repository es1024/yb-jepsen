(ns yugabyte.multi-key-acid
  "Given a single table of two-column composite key and one value column,
  execute reads and transactional batches of writes.
  Verify that history remains linearizable."
  (:require [jepsen.checker :as checker]
            [jepsen.generator :as gen]
            [jepsen.util :as util]
            [jepsen.independent :as independent]
            [knossos.model :as model]
            [yugabyte.generator :as ygen]
            [jepsen.checker.timeline :as timeline])
  (:import (knossos.model Model)))

(defrecord MultiRegister []
  Model
  (step [this op]
    (reduce (fn [state [f k v]]
              ; Apply this particular op
              (case f
                :r (if (or (nil? v)
                           (= v (get state k)))
                     state
                     (reduced
                       (model/inconsistent
                         (str (pr-str (get state k)) "≠" (pr-str v)))))
                :w (assoc state k v)))
            this
            (:value op))))

(defn multi-register
  "A register supporting read and write transactions over registers identified
  by keys. Takes a map of initial keys to values. Supports a single :f for ops,
  :txn, whose value is a transaction: a sequence of [f k v] tuples, where :f is
  :read or :write, k is a key, and v is a value. Nil reads are always legal."
  [values]
  (map->MultiRegister values))

; Three keys, five possible values per key.
(def key-range (vec (range 3)))
(defn rand-val [] (rand-int 5))

(defn r
  "Read a random subset of keys."
  [_ _]
  (->> (util/random-nonempty-subset key-range)
       (mapv (fn [k] [:r k nil]))
       (array-map :type :invoke, :f :read, :value)))

(defn w [_ _]
  "Write a random subset of keys."
  (->> (util/random-nonempty-subset key-range)
       (mapv (fn [k] [:w k (rand-val)]))
       (array-map :type :invoke, :f :write, :value)))

(defn workload
  [opts]
  (let [n (count (:nodes opts))
        threads (:concurrency opts)]
    {:generator (ygen/with-op-index
                  (independent/concurrent-generator
                    (/ threads 2)
                    (range)
                    (fn [k]
                      (->> (gen/reserve (/ threads 4) r w)
                           (gen/stagger 1)
                           (gen/process-limit threads)))))
     :checker   (independent/checker
                  (checker/compose
                    {:timeline (timeline/html)
                     :linear   (checker/linearizable
                                 {:model (multi-register {})})}))}))
