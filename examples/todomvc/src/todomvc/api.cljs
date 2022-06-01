(ns todomvc.api
  (:require
   [cljs.reader]
   [missionary.core :as mi]
   [ribelo.praxis :as px]
   [todomvc.nodes :as nodes]))

(px/defeffect ::->local-storage
  [_ {::nodes/keys [todos]}]
  (mi/sp
   (.setItem js/localStorage "todos-praxis" (str todos))))

(px/defeffect ::set-showing
  [_ _ showing]
  (mi/sp
   (mi/? (px/emit ::px/reset! ::nodes/showing showing))))

(defn allocate-next-id
  "Returns the next todo id.
  Assumes todos are sorted.
  Returns one more than the current largest id."
  [todos]
  ((fnil inc 0) (last (keys todos))))

(px/defeffect ::add-todo
  [_ {::nodes/keys [todos]} text]
  (mi/sp
   (tap> [:add-todo text todos])
   (let [id (allocate-next-id todos)]
     (mi/? (px/emit [::px/reset! ::nodes/todos
                     (assoc todos id {:id id :title text :done false})]
                    [::->local-storage])))))

(px/defeffect ::toggle-done
  [_ _ id]
  (mi/sp
   (mi/? (px/emit [::px/swap! ::nodes/todos
                   update-in [id :done] not]
                  [::->local-storage]))))

(px/defeffect ::save
  [_ _ id text]
  (mi/sp
   (mi/? (px/emit [::px/swap! ::nodes/todos
                   update-in [id :text] text]
                  [::->local-storage]))))

(px/defeffect ::delete-todo
  [_ _ id]
  (mi/sp
   (mi/? (px/emit [::px/swap! ::nodes/todos dissoc id]
                  [::->local-storage]))))

(px/defeffect ::clear-completed
  [_ _]
  (mi/sp
   (mi/?
    (px/emit ::px/swap! ::nodes/todos
             (fn [todos]
               (reduce
                (fn [acc {:keys [id done] :as todo}]
                  (if-not done
                    (assoc acc id todo)
                    acc))
                (sorted-map)
                (vals todos))))
    )))

(px/defeffect ::complete-all-toggle
  [_ _]
  (mi/sp
   (mi/?
    (px/emit ::px/swap! ::nodes/todos
             (fn [todos]
               (tap> [:laksjdlaj todos])
               (let [new-done (not-every? :done (vals todos))]
                 (reduce
                  (fn [acc id]
                    (assoc-in acc [id :done] new-done))
                  todos
                  (keys todos))))))))

