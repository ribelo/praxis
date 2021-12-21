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

(defmacro defnode [dag id vargs & body]
  (if (seq body)
    (let [[nid m & more] vargs
          deps (parse-input m)]
      `(ribelo.metaxy/add-node!
         ~dag ~id ~deps
         ~(if-not (seq more)
            `(fn [~nid ~m]
               ~@body)
            `(fn [~nid ~m]
               (fn ~(vec more)
                 ~@body)))))
    `(ribelo.metaxy/add-node!
       ~dag ~id ~vargs)))

(defmacro reify
  ([id & impls]
   `(~'cljs.core/reify
     ~'ribelo.metaxy/Identifiable
     (~'-id [~'_] ~id)
     ~@impls)))

(defmacro defevent [name' [_e deps & more] & body]
  `(defn ~name' ~(vec more)
     (reify ~(keyword (str *ns*) (name name'))
       ~'INode
       (-deps [~'_] ~(parse-input deps))
       ~@body)))

(defmacro defupdate [name' [e deps & _more :as vargs] & body]
  `(defevent ~name' ~vargs
     ~'ribelo.metaxy/UpdateEvent
     (~'ribelo.metaxy/update [~e ~deps]
      ~@body)))

(defmacro defwatch [name' [e deps stream & _more :as vargs] & body]
  `(defevent ~name' ~vargs
     ~'ribelo.metaxy/WatchEvent
     (~'ribelo.metaxy/watch [~e ~deps ~stream]
       ~@body)))

(defmacro defeffect [name' [e deps stream & _more :as vargs] & body]
  `(defevent ~name' ~vargs
     ~'ribelo.metaxy/EffectEvent
     (~'ribelo.metaxy/effect [~e ~deps ~stream]
      ~@body)))
