(ns todomvc.core
  (:require
   [missionary.core :as mi]
   [rumext.alpha :as mf]
   [ribelo.praxis :as px]
   [todomvc.ui :as ui]
   [todomvc.api :as api]
   [todomvc.nodes :as nodes]))


(defn mount-components []
  (js/console.log "mount"))

(defn init [& _args]
  (px/run!)
  (mf/mount (mf/create-root (js/document.getElementById "app")) (mf/element ui/todo-app)))

