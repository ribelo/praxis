(ns todomvc.ui
  (:require
   [clojure.string :as str]
   [rumext.alpha :as mf]
   [ribelo.extropy :as ex]
   [ribelo.praxis :as px]
   [ribelo.praxis.react :as pxr]
   [todomvc.api :as api]
   [todomvc.nodes :as nodes]))

(mf/defc todo-input [{:keys [title on-save on-stop] :or {title ""} :as props}]
  (let [v (mf/use-state title)
        stop #(do (reset! v "")
                  (when on-stop (on-stop)))
        save #(let [v (-> @v str str/trim)]
                (on-save v)
                (stop))]
    [:> :input (-> (merge (dissoc props :on-save :on-stop :title)
                          {:type "text"
                           :value @v
                           :auto-focus true
                           :on-blur save
                           :on-change #(reset! v (-> % .-target .-value))
                           :on-key-down #(case (.-which %)
                                           13 (save)
                                           27 (stop)
                                           nil)})
                   (ex/js-props))]))

(mf/defc todo-item
  [{:keys [id done title]}]
  (let [editing (mf/use-state false)]
    [:li {:class (str (when done "completed ")
                      (when @editing "editing"))}
     [:div.view {}
      [:input.toggle
       {:type "checkbox"
        :checked done
        :on-change #(px/emit! ::api/toggle-done id)}]
      [:label
       {:on-double-click #(reset! editing true)}
       title]
      [:button.destroy
       {:on-click #(px/emit! ::api/delete-todo id)}]]
     (when @editing
       [todo-input
        {:class "edit"
         :title title
         :on-save (fn [v]
                    (if (seq v)
                      (px/emit! ::api/save id v)
                      (px/emit! ::api/delete-todo id)))
         :on-stop (fn [_] (reset! editing false))}])]))

(mf/defc task-list
  [_props]
  (let [visible-todos @(pxr/subscribe ::nodes/visible-todos)
        all-complete? (pxr/subscribe ::nodes/all-complete?)]
    [:section#main {}
     [:input#toggle-all
      {:type "checkbox"
       :checked all-complete?
       :on-change #(px/emit! ::api/complete-all-toggle)}]
     [:label
      {:for "toggle-all"}
      "Mark all as complete"]
     [:ul#todo-list
      (for [todo visible-todos]
        [:& todo-item todo])]
     ]
    ))

(comment 
  (px/emit! ::px/tap-node ::nodes/visible-todos)
  )

(mf/defc footer-controls
  [_props]
  (let [[active done] @(pxr/subscribe ::nodes/footer-counts)
        showing @(pxr/subscribe ::nodes/showing)
        a-fn (mf/fnc [{:keys [filter-kw txt]}]
               [:a {:class (when (= filter-kw showing) "selected")
                    :on-click (fn [_] (px/emit! ::api/set-showing filter-kw)) } txt])]
    [:footer#footer
     [:span#todo-count
      [:strong active] " " (case active 1 "item" "items") " left"]
     [:ul#filters
      [:li {} (a-fn {:filter-kw :all :txt "All" })]
      [:li {} (a-fn {:filter-kw :active :txt "Active"})]
      [:li {} (a-fn {:filter-kw :done  :txt "Completed"})]]
     (when (pos? done)
       [:button#clear-completed {:on-click #(px/emit! ::api/clear-completed)}
        "Clear completed"])
     ]
    ))

(mf/defc task-entry
  [_props]
  [:header#header
   [:h1 "todos"]
   [:& todo-input
    {:id "new-todo"
     :placeholder "What needs to be done?"
     :on-save #(when (seq %)
                 (px/emit! ::api/add-todo %))}]])

(mf/defc todo-app
  [_props]
  (let [todos @(pxr/subscribe ::nodes/todos)]
    [:*
     [:section#todoapp {}
      [task-entry {}]
      (when (seq todos)
        [:> task-list {}])
    [footer-controls {}]
      ]
     [:footer#info {}
      [:p "Double-click to edit a todo"]]]
    ))


