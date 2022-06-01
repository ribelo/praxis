(ns todomvc.nodes
  (:require
   [cljs.reader]
   [ribelo.praxis :as px]))

(px/defnode ::todos
  (or (some->> (.getItem js/localStorage "todos-praxis")
               (cljs.reader/read-string)
               (into (sorted-map)))
      (sorted-map)))

(comment
  (px/emit! ::px/prn-node ::todos))

(px/defnode ::showing :all)

(px/defnode ::visible-todos
  [id {::keys [todos showing]}]
  (let [filter-fn (case showing
                    :active (complement :done)
                    :done :done
                    :all identity)]
    (filterv filter-fn (vals todos))))

(px/defnode ::all-complete?
  [id {::keys [todos]}]
  (every? :done (vals todos)))

(px/defnode ::completed-count
  [id {::keys [todos]}]
  (count (filterv :done (vals todos))))

(px/defnode ::footer-counts
  [id {::keys [todos completed-count]}]
  [(- (count todos) completed-count) completed-count])
