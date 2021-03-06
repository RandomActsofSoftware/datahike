(ns ^:no-doc datahike.index.hitchhiker-tree.upsert
  (:require [hitchhiker.tree :as tree]
            [hitchhiker.tree.op :as op]))

(defn old-key
  "Returns the old version of the given 'new' key if it exists in 'old-keys'.
  If there are multiple old versions, the one with the biggest transaction time is returned."
  [old-keys new]
  (let [[a b _ _] new]
    (when (seq old-keys)
      (when-let [candidates (subseq old-keys >= [a b nil nil])]
        (->> candidates
             (map first)
             (take-while #(and (= a (first %)) (= b (second %))))
             reverse
             first)))))

(defn remove-old
  "Removes old key from the 'kvs' map using 'remove-fn' function if 'new' and 'old' keys' first two entries match."
  [kvs new remove-fn]
  (when-let [old (old-key kvs new)]
    (remove-fn old)))

(defrecord UpsertOp [key]
  op/IOperation
  (-affects-key [_] key)
  (-apply-op-to-coll [_ kvs]
    (-> (or (remove-old kvs key (partial dissoc kvs)) kvs)
        (assoc key nil)))
  (-apply-op-to-tree [_ tree]
    (let [children  (cond
                      (tree/data-node? tree) (:children tree)
                      :else (:children (peek (tree/lookup-path tree key))))]
      (-> (or (remove-old children key (partial tree/delete tree)) tree)
          (tree/insert key nil)))))

(defn old-retracted
  "Returns a new datom to insert in the tree to signal the retraction of the old datom."
  [kvs key]
  (when-let [old (old-key kvs key)]
    (let [[a b c _] old
          [_ _ _ nt] key]
      ;; '-' means it is retracted and 'nt' is the current transaction time.
      [a b c (- nt)])))

(defrecord temporal-UpsertOp [key]
  op/IOperation
  (-affects-key [_] key)
  (-apply-op-to-coll [_ kvs]
    (let [old-retracted  (old-retracted kvs key)]
      (-> (if old-retracted
            (assoc kvs old-retracted nil)
            kvs)
          (assoc key nil))))
  (-apply-op-to-tree [_ tree]
    (let [children  (cond
                      (tree/data-node? tree) (:children tree)
                      :else (:children (peek (tree/lookup-path tree key))))
          old-retracted  (old-retracted children key)]
      (-> (if old-retracted
            (tree/insert tree old-retracted nil)
            tree)
          (tree/insert key nil)))))

(defn new-UpsertOp [key]
  (UpsertOp. key))

(defn new-temporal-UpsertOp [key]
  (temporal-UpsertOp. key))

(defn add-upsert-handler
  "Tells the store how to deserialize upsert related operations"
  [store]
  (swap! (:read-handlers store)
         merge
         {'datahike.index.hitchhiker_tree.upsert.UpsertOp
          (fn [{:keys [key value]}]
            (map->UpsertOp {:key key :value value}))

          'datahike.index.hitchhiker_tree.upsert.temporal-UpsertOp
          (fn [{:keys [key value]}]
            (map->temporal-UpsertOp {:key key :value value}))})
  store)
