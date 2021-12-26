(ns ribelo.metaxy
  (:refer-clojure :exclude [update run! reify])
  #?(:cljs (:require-macros [ribelo.metaxy :refer [reify defnode defupdate defwatch defeffect]]))
  (:require
   #?(:clj [clojure.core :as clj] :cljs [cljs.core :as cljs])
   [taoensso.timbre :as timbre]
   [missionary.core :as mi]
   #?(:cljs ["react" :as react])
   #?(:clj [meander.epsilon :as me])))

;; macros

#?(:clj (defmacro if-clj  [then & [else]] (if (:ns &env) else then)))

#?(:clj
   (defmacro reify
     ([id & impls]
      `(~(if-clj 'clojure.core/reify 'cljs.core/reify)
        ~'ribelo.metaxy/Identifiable
        (~'id [~'_] ~id)
        ~@impls))))

#?(:clj
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
       (me/or [{}] {}) [])))

#?(:clj
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
                    (ribelo.metaxy/-memoize
                     (fn ~(vec more)
                       ~@body)))))))
       `(ribelo.metaxy/add-node!
          ~id ~vargs))))

#?(:clj
   (defmacro defevent [id [_e deps & more] & body]
     `(defmethod ribelo.metaxy/event ~id ~(into ['id] more)
        (reify ~id
          ~'ribelo.metaxy/INode
          (dependencies [~'_] ~(parse-input deps))
          ~@body))))

#?(:clj
   (defmacro defupdate [id [e deps & _more :as vargs] & body]
     `(defevent ~id ~vargs
        ~'ribelo.metaxy/UpdateEvent
        (ribelo.metaxy/update [~e ~deps]
          ~@body))))

#?(:clj
   (defmacro defwatch [id [e deps stream & _more :as vargs] & body]
     `(defevent ~id ~vargs
        ~'ribelo.metaxy/WatchEvent
        (ribelo.metaxy/watch [~e ~deps ~stream]
                             ~@body))))

#?(:clj
   (defmacro defeffect [name' [e deps stream & _more :as vargs] & body]
     `(defevent ~name' ~vargs
        ~'ribelo.metaxy/EffectEvent
        (ribelo.metaxy/effect [~e ~deps ~stream]
                              ~@body))))

;;

(declare get-node)

(def ^:private nodes_ (atom {}))
(def ^:private mbx (mi/mbx))
(def ^:private stream (mi/dfv))
(def ^:private reactor (mi/dfv))

(defprotocol Identifiable
  (id   [_]))

(defn identifiable? [e]
  (satisfies? Identifiable e))

(defprotocol INode
  (dependencies [_]))

#?(:clj
   (def dag
     (clj/reify
       clojure.lang.ILookup
       (valAt [this k]
         (when (.get @nodes_ k) (get-node k)))

       clojure.lang.IRef
       (deref [_] @nodes_)

       clojure.lang.IAtom
       (swap [_ f x]
         (swap! nodes_ f x))
       (swap [_ f x y]
         (swap! nodes_ f x y))
       (swap [_ f x y more]
         (swap! nodes_ f x y more))

       clojure.lang.IFn
       (invoke [_ k]
         (when (.valAt ^clojure.lang.ILookup @nodes_ k)
           (get-node k)))

       (invoke [_ k x]
         (when-let [n (dag k)]
           (n x)))
       (invoke [_ k x y]
         (when-let [n (dag k)]
           ((n x) y)))))

   :cljs
   (def dag
     (cljs/reify
       ILookup
       (-lookup [this k]
         (when (.get @nodes_ k)
           (get-node k)))

       IDeref
       (-deref [_] @nodes_)

       ISwap
       (-swap! [_ f x]
         (swap! nodes_ f x))
       (-swap! [_ f x y]
         (swap! nodes_ f x y))
       (-swap! [_ f x y more]
         (swap! nodes_ f x y more))

       IFn
       (-invoke [_ k]
         (when (.get @nodes_ k) (get-node k)))
       (-invoke [this k x]
         (when-let [n (dag k)]
           (n x)))
       (-invoke [this k x y]
         (when-let [n (dag k)]
           ((n x) y))))))

(defn- -memoize [f]
  (let [sentinel #?(:clj ::none :cljs (js/Symbol "none"))
        mem (atom {})]
    (fn [m]
      (let [v #?(:clj (.valAt ^clojure.lang.ILookup @mem m sentinel) :cljs (.get @mem m sentinel))]
        (if (identical? sentinel v)
          (let [r (f m)]
            (swap! mem assoc m r)
            r)
          v)))))

(defmulti event
  (fn
    ([e              ] e)
    ([e _            ] e)
    ([e _ _          ] e)
    ([e _ _ _        ] e)
    ([e _ _ _ _      ] e)
    ([e _ _ _ _ _    ] e)
    ([e _ _ _ _ _ _  ] e)
    ([e _ _ _ _ _ _ _] e)))

(defmethod event :default [e]
  (timbre/warnf "Using default event handler for %s, returning value as is, consider adding own method!"
                (if (identifiable? e) (id e) e))
  e)

(defn dispatch [& args]
  (mbx (apply event args)))

(defmulti handle-error (fn [e _err] (id ^Identifiable e)))

(defmethod handle-error :default
  [e err]
  (timbre/warn "Using default error handler, consider using your own!")
  (timbre/error "event failure id:" (id e) (ex-message err)))

(defprotocol UpdateEvent
  (update [_ dag]))

(defprotocol WatchEvent
  (watch [_ node stream]))

(defprotocol EffectEvent
  (effect [_ node stream]))

(defn update-event? [e]
  (satisfies? UpdateEvent e))

(defn watch-event? [e]
  (satisfies? WatchEvent e))

(defn effect-event? [e]
  (satisfies? EffectEvent e))

#?(:cljs
   (defn promise?
     "Return `true` if `v` is a promise instance or is a thenable
  object.
  https://github.com/funcool/potok/blob/master/src/potok/core.cljs#L74"
     [v]
     (or (instance? js/Promise v)
         (and (goog.isObject v)
              (fn? (unchecked-get v "then"))))))

(defn- -reset-graph-input! [dag m]
  (reduce-kv (fn [_ k v] (when-let [atm_ (-> @dag (.get k) .-input)] (reset! atm_ v))) nil m))

#?(:clj
   (defn get-node [id]
     (when (.get @nodes_ id)
       (reify id
         INode
         (dependencies [_]
           (-> (.valAt ^clojure.lang.ILookup @nodes_ id) .-deps))

         clojure.lang.IFn
         (invoke [_]
           ((-> (.valAt ^clojure.lang.ILookup @nodes_ id) .-flow)))

         (invoke [_ deps]
           ((-> (.valAt ^clojure.lang.ILookup @nodes_ id) .-f) id deps))

         (invoke [_ s f]
           ((-> (.valAt ^clojure.lang.ILookup @nodes_ id) .-flow) s f))

         clojure.lang.IRef
         (deref [_]
           (let [>f (-> (.valAt ^clojure.lang.ILookup @nodes_ id) .-flow)]
             (mi/reduce (comp reduced {}) nil >f)))
         )))

   :cljs
   (defn get-node [id]
     (when (.get @nodes_ id)
       (reify id
         INode
         (dependencies [_]
           (-> @nodes_ (.get id) .-deps))

         IFn
         (-invoke [_]
           (.-flow ^Vertex (.get @nodes_ id)))

         (-invoke [_ deps]
           ((.-f ^Vertex (.get @nodes_ id)) id deps))

         (-invoke [_ s f]
           ((.-flow ^Vertex (.get @nodes_ id)) s f))

         IDeref
         (-deref [_]
           (let [>f (.-flow ^Vertex (.get @nodes_ id))]
             (mi/reduce (comp reduced {}) nil >f)))
         ))))

(defrecord Vertex [id f input deps flow])

(defn- -vertex [{:keys [id f input deps flow]}]
  (->Vertex id f input deps flow))

(defn- -link! [id >fs]
  (if-let [vrtx (get-node id)]
    (mi/signal! (apply mi/latest (fn [& args] [id (vrtx (into {} args))]) >fs))
    (throw (ex-info (str "node " id " dosen't exists!") {:id id}))))

(defn- -build-graph []
  (loop [[[id vrtx] & more] @dag acc []]
    (if id
      (if-let [f (some-> vrtx .-flow mi/signal!)]
        (recur more (conj acc f))
        (let [ks (.-deps vrtx)
              in (mapv (fn [k]
                         (if-let [>f (some-> @dag (.get k))]
                           (.-flow ^Vertex >f)
                           (throw (ex-info (str "node " id " dosen't exists!") {:id k})))) ks)]
          (if (every? some? in)
            (let [>f (-link! id in)]
              (swap! dag assoc-in [id :flow] >f)
              (recur more (conj acc >f)))
            (recur (conj (into [] more) [id vrtx]) acc))))
      acc)))

(defn- -process-update-event [e]
  ((mi/sp
    (let [fs   (mapv (fn [id] (let [^Vertex vrtx (.get @dag id)] (.-flow vrtx))) (dependencies e))
           r    (mi/? (mi/reduce (comp reduced {}) nil (apply mi/latest (fn [& args] (update e (into {} args))) fs)))]
       (if (map? r)
         (-reset-graph-input! dag r)
         (timbre/errorf "result of UpdateEvent: %s should be a map or missionary ap!, but is %s" (id e) (type r)))))
   (constantly nil) #(handle-error e %)))

(defn- -process-watch-event [e]
  ((mi/sp
    (when-let [>f (watch e dag (mi/? stream))]
      (cond
        #?(:clj  (or (fn? >f) (instance? missionary.impl.Reactor$Publisher >f))
           :cljs (or (fn? >f) (instance? missionary.impl/Publisher >f)))
        (mi/? (mi/reduce (fn [_ e] (dispatch e)) >f))

        #?@(:cljs
            [(promise? >f)
             (-> >f (.then (fn [s] (dispatch s))) (.catch (fn [err] (handle-error e err))))])

        :else
        (timbre/errorf "result of WatchEvent: %s should be missionary ap! or js/promise, but is: %s" (id e) (type >f)))))
   (constantly nil) #(handle-error e %)))

(defn- -process-effect-event [e]
  ((mi/sp
    (when-let [>f (effect e dag (mi/? stream))]
      (cond
        #?(:clj  (or (fn? >f) (instance? missionary.impl.Reactor$Publisher >f))
           :cljs (or (fn? >f) (instance? missionary.impl/Publisher >f)))
        (mi/? (mi/reduce (constantly nil) >f))

        #?@(:cljs
            [(promise? >f)
             (-> >f (.then (constantly nil)) (.catch (fn [err] (handle-error e err))))])

        :else
        (timbre/errorf "result of EffectEvent: %s should be missionary ap! or js/promise, but is: %s" (id e) (type >f)))))
   (constantly nil) #(handle-error e %)))

(defn add-node!
  ([id x]
   (when-let [vrtx (.get @dag id)]
     (timbre/warn "overwriting node id:" id)
     (when-let [>flow (.-flow ^Vertex vrtx)]
       (try (>flow) (catch #?(:clj Exception :cljs :default) _ nil))))
   (cond
     (instance? #?(:clj clojure.lang.Atom :cljs Atom) x)
     (let [>flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch x))]
       (swap! dag assoc id (-vertex {:id id :flow >flow :input x})))

     (fn? x)
     (swap! dag assoc id (-vertex {:id id :flow x}))

     :else
     (add-node! id (atom x))))

  ([id deps f]
   (when-let [vrtx (.get @dag id)]
     (timbre/warn "overwriting node id:" id)
     (when-let [>flow (.-flow ^Vertex vrtx)]
       (try (>flow) (catch #?(:clj Exception :cljs :default) _ nil))))
   (swap! dag assoc id (-vertex {:id id :f f :deps deps})))

  ([id deps f >flow]
   (when-let [vrtx (.get @dag id)]
     (timbre/warn "overwriting node id:" id)
     (when-let [>flow (.-flow ^Vertex vrtx)]
       (>flow)))
   (swap! @dag assoc id (-vertex {:id id :f f :deps deps :flow >flow}))))

(defn add-watch! [e]
  ((mi/sp
     (when (mi/? stream)
       (dispatch e)))
   (constantly nil) (constantly nil)))

(defn listen! [deps f]
  (let [dfv (mi/dfv)]
    (dispatch
     (reify (str "listen-" #?(:clj (java.util.UUID/randomUUID) :cljs (random-uuid)))
       EffectEvent
       (effect [e m _]
         (let [deps (cond-> deps (not (seq? deps)) vector)
               fs   (mapv (fn [id] (some-> ^Vertex (.get @dag id) .-flow)) deps)]
           (if (reduce (fn [_ >f] (if >f true (reduced false))) false fs)
             (dfv (mi/stream! (apply mi/latest (fn [& args] (f e (into {} args))) fs)))
             (timbre/error "some of deps dosen't exists!"))))))
    (fn []
      ((mi/sp (when-let [>f (mi/? dfv)] (>f))) (constantly nil) (constantly nil)))))

#?(:clj
   (defn value
     ([id]
      (value id ::none))
     ([id x]
      (let [atm_ (atom nil)
            lstn (listen! id
                   (fn [_ m]
                     (if #?(:clj (identical? ::none x) :cljs (keyword-identical? ::none x))
                       (reset! atm_ (.get m id))
                       (reset! atm_ ((.get m id) x)))))]
        (clojure.core/reify
          clojure.lang.IRef
          (deref [_] @atm_)

          clojure.lang.IFn
          (invoke [_] (lstn))))))
   :cljs
   (defn value
     ([id]
      (value id ::none))
     ([id x]
      (let [atm_ (atom nil)
            lstn (listen! id
                   (fn [_ m]
                     (if #?(:clj (identical? ::none x) :cljs (keyword-identical? ::none x))
                       (reset! atm_ (.get m id))
                       (reset! atm_ ((.get m id) x)))))]
        (cljs.core/reify
          IDeref
          (-deref [_] @atm_)

          IFn
          (-invoke [_] (lstn)))))))

#?(:clj
   (defn subscribe
     ([id]
      (value id ::none))
     ([id x]
      (value id x)))

   :cljs
   (defn subscribe
     ([id]
      (subscribe id ::none))
     ([id m]
      (let [-m (react/useRef m)
            m' (if (= m (.-current -m)) (.-current -m) m)
            [state set-state!] (react/useState nil)]
        (react/useEffect
         (fn []
           (set! (.-current -m) m)

           (listen! id (fn [_ v] (if (keyword-identical? m ::none)
                                  (set-state! (.get v id))
                                  (set-state! ((.get v id) m))))))
         #js [(str id) m'])
        (cljs.core/reify
          IDeref
          (-deref [_] state)
          IReset
          (-reset! [_ x] (set-state! x)))))))

(defn run! []
  ((mi/sp
    (if-not (try (mi/? (mi/timeout 0 reactor)) (catch #?(:clj Exception :cljs :default) _ nil))
      (let [r (mi/reactor
               (let [xs (-build-graph)
                     >e (mi/stream! (mi/ap (loop [] (mi/amb> (mi/? mbx) (recur)))))]
                 (stream >e)
                 (reduce (fn [acc >f] (conj acc (mi/stream! >f))) [] xs)
                 (mi/stream! (->> >e (mi/eduction (filter update-event?) (map (fn [e] (-process-update-event e))))))
                 (mi/stream! (->> >e (mi/eduction (filter watch-event?)  (map (fn [e] (-process-watch-event  e))))))
                 (mi/stream! (->> >e (mi/eduction (filter effect-event?) (map (fn [e] (-process-effect-event e))))))))]
        (reactor (r (constantly nil) #(timbre/error %))))
      (timbre/error "graph already builded!")))
   (constantly nil) #(timbre/error %)))

(defn dispose! []
  ((mi/sp
    (when-let [cb (try (mi/? (mi/timeout 0 reactor)) (catch #?(:clj Exception :cljs :default) _ nil))]
      (cb)))
   (constantly nil) #(timbre/error %)))

;; -----------------------------------------

(comment
  (defnode ::store {:a 1 :b 1 :c 1})

  (defnode ::a
    [id {::keys [store]}]
    (println :store store)
    (println :eval-node :id id :val (:a store))
    (:a store))

  (defnode ::b
    [id {::keys [store]}]
    (println :eval-node :id id :val (:b store))
    (:b store))

  (defnode ::c
    [id {::keys [a b]} {:keys [x]}]
    (println :inside id (+ a b x))
    (+ a b x))

  (run!)
  (def lstn (listen! ::c (fn [e {::keys [c]}]
                           (println :e e)
                           (println :dag dag)
                           (println :lstn ::c (c {:x 10})))))

  (dispatch :a)

  (def sub (subscribe ::c {:x 100}))
  @sub
  (sub)

  (lstn)
  (dispose!)

  (defupdate ::update-test
    [e {::keys [store]}]
    (println :update-test store)
    {::store {:a (rand-int 10) :b (rand-int 10) :c (rand-int 10)}})

  (dispatch ::update-test)

  (defupdate ::update-test2
    [e {::keys [store]} {:keys [x]}]
    (println :update-test2 :x x)
    {::store {:a x :b x :c x}}))
