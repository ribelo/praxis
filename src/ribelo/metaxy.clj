(ns ribelo.metaxy
  (:refer-clojure :exclude [reify]))

(defmacro reify
  ([id & impls]
   `(~'cljs.core/reify
      ~'ribelo.metaxy/Identifiable
      (~'-id [~'_] ~id)
     ~@impls)))

(defmacro with-increment! [node & body]
  `(do
     (-inc! ~node)
     (do ~@body)
     (-dec! ~node)))

(defmacro defevent [name v & body]
  `(defn ~name ~v
     (reify ~(keyword (str *ns*) (str name))
       ~@body)))
