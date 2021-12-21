(ns ribelo.metaxy
  (:refer-clojure :exclude [reify])
  (:require
   [meander.epsilon :as me]))

(defn- parse-input
  "parses the vector of function arguments to find all deps"
  [v]
  (me/rewrite v
    ;; {:keys [a/b b/c]}
    {:keys [(me/symbol _ _ :as !ss) ...] & ?more}
    [(me/app keyword !ss) ... & (me/cata [?more])]
    ;; {:a/keys [b c]}
    {(me/keyword ?ns "keys") [(me/symbol _ _ :as (me/app name !ss)) ...] & ?more}
    [(me/app (partial keyword ?ns) !ss) ... & (me/cata [?more])]
    ;; {:a ?a :b ?b}
    {(me/symbol _ _ :as ?s) (me/keyword _ _ :as ?k) & ?more}
    [?k & (me/cata ?more)]
    ;; empty case
    (me/or [{}] {}) []))

(defmacro defnode [id vargs & body]
  (if (seq body)
    (let [[nid m & more] vargs
          deps (parse-input m)]
      `(do
         (ribelo.metaxy/add-node!
           ~id ~deps
           ~(if-not (seq more)
              `(fn [~nid ~m]
                 ~@body)
              `(fn [~nid ~m]
                 (fn ~(vec more)
                   ~@body))))))
    `(ribelo.metaxy/add-node!
      ~id ~vargs)))

(defmacro reify
  ([id & impls]
   `(~'cljs.core/reify
     ~'ribelo.metaxy/Identifiable
     (~'id [~'_] ~id)
     ~@impls)))

(defmacro defevent [id [_e deps & more] & body]
  `(defmethod ~'ribelo.metaxy/dispatch ~id ~(into ['id] more)
     (~'ribelo.metaxy/mbx
      (reify ~id
        ~'INode
        (dependencies [~'_] ~(parse-input deps))
        ~@body))))

(defmacro defupdate [id [e deps & _more :as vargs] & body]
  `(defevent ~id ~vargs
     ~'ribelo.metaxy/UpdateEvent
     (~'ribelo.metaxy/update [~e ~deps]
      ~@body)))

(defmacro defwatch [id [e deps stream & _more :as vargs] & body]
  `(defevent ~id ~vargs
     ~'ribelo.metaxy/WatchEvent
     (~'ribelo.metaxy/watch [~e ~deps ~stream]
      ~@body)))

(defevent
  :ribelo.metaxy/update-test
  [e #:ribelo.metaxy{:keys [store]}]
  UpdateEvent
  (update
    [e #:ribelo.metaxy{:keys [store]}]
    (println :update-test store)
    #:ribelo.metaxy{:store
                    {:a (rand-int 10),
                     :b (rand-int 10),
                     :c (rand-int 10)}}))

(defmacro defeffect [name' [e deps stream & _more :as vargs] & body]
  `(defevent ~name' ~vargs
     ~'ribelo.metaxy/EffectEvent
     (~'ribelo.metaxy/effect [~e ~deps ~stream]
      ~@body)))
