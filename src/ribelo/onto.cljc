(ns ribelo.onto
  (:refer-clojure :exclude [memoize update run! reify])
  #?(:cljs (:require-macros [ribelo.onto :refer [reify with-increment!]]))
  (:require
   #?(:clj [clojure.core :as clj] :cljs [cljs.core :as cljs])
   [taoensso.timbre :as timbre]
   [missionary.core :as mi]))

(comment (require '[taoensso.encore :as enc]))

(defprotocol Identifiable
  (-id   [_])
  (-type [_]))

(defmacro reify
  ([id & impls]
   `(~'cljs/reify
      ~'Identifiable
      (~'-id [~'_] ~id)
     ~@impls)))

(defprotocol Subscribable
  (subscribe!    [_     ] [_ id  ] [_ id m])
  (unsubscribe!  [_     ] [_ id  ])
  (subscribed    [_     ])
  (-inc!         [_     ] [_ id  ])
  (-dec!         [_     ] [_ id  ]))

(defprotocol Publisher
  (listen!       [_    f] [_ id    f] [_ id m f]))

(defprotocol INode
  (-deps         [_     ])
  (-listeners    [_     ])
  (-dfv          [_     ] [_    x])
  (-state        [_     ])
  (-fn           [_    x]))

(defprotocol IGraph
  (add-node!     [_ id    ] [_ id out] [_ id deps f] [_ id deps out f])
  (emit!         [_      e])

  (build!        [_       ])
  (run!          [_       ])
  (stop!         [_       ])

  (-stream       [_       ] [_      x]))

(defprotocol UpdateEvent
  (update [_ graph]))

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

(defn- -count-incrase? []
  (fn [rf]
    (let [pc (volatile! nil)]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result input]
         (let [prior @pc]
           (vreset! pc (count input))
           (if (or (not prior) (< prior (count input)))
             (rf result input)
             result)))))))

(defn- -recursive-increment [nodes id]
  (if-not id
    nodes
    (let [m      (.get nodes id)
          nodes' (update-in nodes [id :n] inc)]
      (if (zero? (.get m :n))
        (reduce -recursive-increment nodes' (.get m :deps))
        nodes'))))

(defn- -recursive-decrement [nodes id]
  (if-not id
    nodes
    (let [m      (.get nodes id)
          nodes' (update-in nodes [id :n] dec)]
      (if (= 1 (.get m :n))
        (reduce -recursive-decrement nodes' (.get m :deps))
        nodes'))))

(defn- -reset-graph-state [nodes m]
  (loop [[[k v] & more] m]
    (when (some? k)
      (if-let [m' (.get nodes k)]
        (if-not (seq (.get m' :deps))
          (let [state_ (.get m' :state)]
            (reset! state_ v)
            (recur more))
          (timbre/error "can't reset node" k "in graph" (.-id nodes)  ", node has dependencies!"))
        (timbre/error "can't reset node" k "in graph" (.-id nodes)  ", node dosen't exists!"))))
  nodes)

(defn- -swap-node [dag node f & args]
  (case (-type node)
    :root
    (let [v (apply f @node args)
          state_ ((@dag ::store) :state)]
      (reset! state_ v))
    (throw (ex-info "can't swap node with deps" {:id (-id node) :deps (-deps node)}))))

(defn- -reset-node [dag node x]
  (case (-type node)
    :root
    (swap! dag assoc-in [(-id node) :state] x)
    (throw (ex-info "can't reset node with deps" {:id (-id node) :deps (-deps node)}))))

(defmacro with-increment! [node & body]
  `(do
     (-inc! ~node)
     (do ~@body)
     (-dec! ~node)))

(deftype Node [id dag]
  Identifiable
  (-id [_]
    id)

  (-type [this]
    (if (-deps this) :node :root))

  INode
  (-deps [_]
    (-> @dag (.get id) (.get :deps)))

  (-listeners [_]
    (-> @dag (.get id) (.get :listeners)))

  (-dfv [_]
    (-> @dag (.get id) (.get :dfv)))

  (-dfv [_ x]
    (swap! dag assoc-in [id :dfv] x))

  (-state [_]
    (-> @dag (.get id) (.get :state)))

  (-fn [_ deps]
    ((-> @dag (.get id) (.get :f)) id deps))

  Subscribable
  (-inc! [_]
    (swap! dag #(-recursive-increment % id)))

  (-dec! [_]
    (swap! dag #(-recursive-decrement % id)))

  Publisher
  (listen! [this f]
    (listen! this nil f))

  (listen! [_ m f]
    (swap! dag
      (fn [nodes]
        (-> nodes
            (#(-recursive-increment % id))
            (update-in [id :listeners] conj [id f m]))))
    (fn []
      (swap! dag
        (fn [nodes]
          (-> nodes
              (#(-recursive-decrement % id))
              (update-in [id :listeners] disj [id f m]))))))

  cljs.core/IFn
  (-invoke [this x y]
    (if (keyword-identical? :node (-type this))
      (with-increment! this
        ((-dfv this) x y))
      ((mi/sp (deref (-state this))) x y)))

  cljs.core/IDeref
  (-deref [this]
    (case (-type this)
      :root
      (deref (-state this))
      (throw (ex-info "can only derefer root node" {:id (-id this) :deps (-deps this)}))))

  cljs.core/ISwap
  (-swap! [this f]
    (-swap-node dag this f))
  (-swap! [this f x]
    (-swap-node dag this f x))
  (-swap! [this f x y]
    (-swap-node dag this f x y))
  (-swap! [this f x y more]
    (-swap-node dag this f x y more))

  cljs.core/IReset
  (-reset! [this x] (-reset-node dag this x)))

(defn- -node [dag id]
  (Node. id dag))

(defn- -update-dataflow! [node [id v]]
  (let [e (-dfv node)]
    ((mi/timeout 0 e)
     (fn [_]
       (let [d (mi/dfv)]
         (d v)
         (-dfv node d)))
     (fn [_] (e v)))
    [id v]))

(defn- -notify-listeners! [node v]
  ((mi/reduce (constantly nil)
              (mi/ap (let [[_ f m] (mi/?> (mi/seed (-listeners node)))]
                       (if-not (fn? v) (f (-id node) v) (f (-id node) (v m))))))
   (constantly nil) #(prn "listeners failure for id:" (-id node))))

(defn- -link! [dag >graph id fs]
  (if-let [node (get dag id)]
    (let [>nodes (mi/signal! (apply mi/latest (fn [& args] (into {} args)) (mapv second fs)))
          >count (mi/signal! (mi/eduction (comp (map (fn [nodes] (-> nodes (.get id) (.get :n)))) (filter pos?) (dedupe)) >graph))
          >lstn  (mi/signal! (mi/relieve {} (mi/eduction (comp (map (fn [nodes] (-> nodes (.get id) (.get :listeners)))) (-count-incrase?)) >graph)))
          >f1    (mi/signal! (->> (mi/latest (fn [_ deps] (let [v (-fn node deps)] [id v])) >count >nodes)
                                  (mi/eduction (comp (dedupe) (map (partial -update-dataflow! node))))))
          >f2    (mi/signal! (mi/latest (fn [_ [id v]] (-notify-listeners! node v) [id v]) >lstn >f1))]
      >f2)
    (throw (ex-info "node dosen't exists!" {:id id}))))

(defn- -build-graph [dag >graph]
  (let [snapshot @dag]
    (loop [[[id node] & more] snapshot acc [] m {}]
      (if id
        (if-let [f (some-> node (.get :ap) mi/signal!)]
          (recur more (conj acc f) (assoc m id f))
          (let [ks (.get node :deps)]
            (let [in (mapv (fn [k]
                             (if (snapshot k)
                               (when-let [ap (.get m k)]
                                 [k ap])
                               (throw (ex-info "node dosen't exists!" {:id k})))) ks)]
              (if (every? some? in)
                (let [f (-link! dag >graph id in)]
                  (recur more (conj acc f) (assoc m id f)))
                (recur (conj (into [] more) [id node]) acc m)))))
        acc))))

(defmulti handle-error (fn [e err] (-id e)))

(defmethod handle-error :default
  [id err]
  (timbre/warn "Using default error handler, consider using your own!")
  (timbre/error "event failure id:" id "msg:" (ex-message err)))

(defn- -process-update-event [e dag]
  (let [m (try (update e dag)
               (catch js/Error err
                 (handle-error e err)))]
    (cond
      (map? m)
      (swap! dag -reset-graph-state m)
      :else
      (timbre/errorf "result of UpdateEvent: %s should be a map!" (-id e)))))

(defn- -process-watch-event [e dag stream]
  (let [>f (try (watch e dag stream)
                (catch js/Error err
                  (handle-error e err)))]
    (cond
      (fn? >f)
      (mi/stream! (->> >f (mi/eduction (map (fn [e] (emit! dag e))))))

      (some? >f)
      (timbre/errorf "result of WatchEvent: %s should be missionary ap!" (-id e)))))

(defn- -process-effect-event [e dag stream]
  (let [>f (try (effect e dag stream)
                (catch #?(:clj Exception :cljs js/Error) err
                  (handle-error e err)))]
    (fn? >f)
    (mi/stream! >f)

    (some? >f)
    (timbre/errorf "result of EffectEvent %s should be missionary ap!" (-id e))))

(deftype Graph [graph-id nodes_ ^:unsynchronized-mutable mbx ^:unsynchronized-mutable graph-reactor ^:unsynchronized-mutable running?]
  Identifiable
  (-id [_] graph-id)

  IGraph
  (add-node! [_ id atm_]
      (if-not graph-reactor
        (let [ap' (mi/ap [id (mi/?< (mi/eduction (dedupe) (mi/watch atm_)))])]
          (when (-> @nodes_ (.get id))
            (timbre/warn "overwriting node id:" id "for graph id:" graph-id))
          (swap! nodes_ assoc id {:id id :state atm_ :f (constantly nil) :deps nil :ap ap' :n 0 :listeners #{}}))
        (timbre/error "can't add node id:" id " to builded graph id:" graph-id)))

  (add-node! [_ id deps f]
      (if-not graph-reactor
        (do
          (when (.get @nodes_ id)
            (timbre/warn "overwriting node id:" id "for graph:" graph-id))
          (swap! nodes_ assoc id {:id id :dfv (mi/dfv) :f f :deps deps :ap nil :n 0 :listeners #{}}))
        (timbre/error "can't add node id:" id " to builded graph id:" graph-id)))

  (build! [this]
    (if-not graph-reactor
      (do
        (set! graph-reactor
          (mi/reactor
            (let [>g (mi/signal! (mi/eduction (dedupe) (mi/watch nodes_)))
                  xs (-build-graph this >g)
                  >e (mi/stream! (mi/ap (loop [] (mi/amb> (let [x (mi/? mbx)] (println :x x) x) (recur)))))]
              (reduce (fn [acc >f] (conj acc (mi/stream! >f))) [] xs)
              (mi/stream! (->> >e (mi/eduction (filter update-event?)  (map (fn [e] (tap> [:update (-id e)]) (-process-update-event e this))))))
              (mi/stream! (->> >e (mi/eduction (filter watch-event?)  (map (fn [e] (tap> [:watch (-id e)]) (-process-watch-event e this >e))))))
              (mi/stream! (->> >e (mi/eduction (filter effect-event?) (map (fn [e] (tap> [:effect (-id e)]) (-process-effect-event e this >e)))))))))
        this)
      (timbre/error "graph" graph-id "already builded!")))

  (emit! [_ e]
    (println :emit (-id e))
    (mbx e))

  (run! [_]
    (set! graph-reactor (graph-reactor (constantly nil) #(timbre/error %))))

  Publisher
  (listen! [this id f]
    (listen! this id nil f))

  (listen! [this id m f]
    (if-let [node (get this id)]
      (listen! node m f)))

  cljs.core/ILookup
  (-lookup [this k]
    (-node this k))

  cljs.core/IDeref
  (-deref [_]
    (deref nodes_))

  cljs.core/ISwap
  (-swap! [_ f]
    (swap! nodes_ f))
  (-swap! [_ f x]
    (tap> [:-swap2! graph-id f])
    (swap! nodes_ f x))
  (-swap! [_ f x y]
    (swap! nodes_ f x y))
  (-swap! [_ f x y more]
    (swap! nodes_ f x y more))

  cljs.core/IFn
  (-invoke [this k] (-node this k)))

(defn graph [id]
  (Graph. id (atom {}) (mi/mbx) nil false))

;; -----------------------------------------

(comment
  (def store_ (atom {:a 1 :b 1 :c 1}))

  (def dag (graph :onto))

  (add-node! dag ::store store_)

  (add-node! dag ::a
    [::store]
    (fn [id {::keys [store]}]
      (println :eval-node :id id)
      (:a store)))

  (add-node! dag ::b
    [::store]
    (fn [id {::keys [store]}]
      (println :eval-node :id id)
      (:b store)))

  (add-node! dag ::c
    [::a ::b]
    (fn [id {::keys [a b]}]
      (fn [{:keys [x]}]
        (println :eval-node  :id id :a a :b b :x x)
        (+ a b x))))

  (def dispose (-> dag build! run!))
  #_(dispose)

  ((mi/sp
     (println :c ((mi/? (::c dag)) {:x 10}))
     (println :b (mi/? (::a dag)))
     (println :a (mi/? (::a dag))))
   prn
   prn)

  (emit! dag
    (reify ::update-test
      UpdateEvent
      (update [id {::keys [a b]}]
          (println :event id (+ a b))
        {::store {:a 2 :b 3 :c 4}})))
  @(::store (tap> @dag))

  (emit! dag
    (reify ::watch-test
      WatchEvent
      (watch [_ _ _]
        (mi/seed
          [(reify :update-1
             UpdateEvent
             (update [_ _]
                 {::store {:a 2 :b 2 :c 3}}))
           (reify :update-2
             UpdateEvent
             (update [_ _]
                 {::store {:a 10 :b 2 :c 43}}))]))))

  (tap> @dag)
  (-inc! (get dag ::a))
  (mi/? (get dag ::a))
  (-deps (get dag ::b))

  (-listeners (get dag ::a))

  (swap! store_ clj/update :a (fnil inc 0))
  (swap! (::store dag) clj/update :a (fnil inc 0))
  (swap! dag update-in [::store :state :a] inc)

  (println "------------------------------")

  (defn add-user-event
    [user]
    (reify ::add-user-event
      UpdateEvent
      (update [_ {::keys [store]}])
      (reset! store)))

  (def a_ (listen! dag
            ::a
            (fn [_id r]
              (println :listen _id :start)
              (println :listen _id r))))

  (def b_ (listen! dag
            ::b
            (fn [_id r]
              (println :listen _id :start)
              (println :listen _id r))))

  (def c_ (listen! dag
            ::c
            {:x 100}
            (fn [_id r]
              (println :listen _id :start)
              (println :listen _id r))))

  (println "------------------------------")
  (def dispose (run-reactor graph))
  (dispose)

  (c_)

  ()

  (def box (mi/mbx))
  (def >f
    (let [e (mi/dfv)]
      ((mi/sp
         (let [! (mi/? e)]
           (loop []
             (let [v (mi/? box)]
               (! v)
               (recur))))) prn prn)
      (mi/observe (fn [!] (e !) prn))))

  (def r1 (mi/reactor (mi/stream! (mi/ap (let [v (mi/?< (mi/signal! >f))] (println :r1 v) v)))))
  (def r2 (mi/reactor (mi/stream! (mi/ap (let [v (mi/?< (mi/signal! >f))] (println :r2 v) v)))))
  (def dispatch1 (r1 prn prn))
  (def dispatch2 (r2 prn prn))
  (box 1)

  (def box (mi/mbx))
  (box 1)
  (def >f (mi/ap (loop [] (mi/amb> (mi/? box) (recur)))))
  (def c1
    ((mi/reactor
       (let [>f (mi/stream! >f)]
         (mi/stream! (mi/ap (println :r1 (mi/?> >f))))))
     prn prn))
  (def c2
    ((mi/reactor
       (let [>f (mi/stream! >f)]
         (mi/stream! (mi/ap (println :r2 (mi/?> >f))))))
     prn prn))
  ;; :r1 1
  ;; :r2 1
  (box 2)
  ;; :r1 2
  ;; :r2 2

  (def nodes_ (atom {:a {:n 0}
                     :b {:n 0}
                     :c {:n 0}}))
  (def store_ (atom {:a 0 :b 0 :c 0}))

  (def r
    (mi/reactor
      (let [>n  (mi/signal! (mi/watch nodes_))
            >s  (mi/signal! (mi/watch store_))

            >as (mi/signal! (mi/eduction (map :a) >s))
            >ac (mi/signal! (mi/eduction (comp (map (fn [m] (get-in m [:a :n]))) (filter pos?) (dedupe)) >n))
            >af (mi/signal! (mi/latest vector >as >ac))

            >bs (mi/signal! (mi/eduction (map :b) >s))
            >bc (mi/signal! (mi/eduction (comp (map (fn [m] (get-in m [:b :n]))) (filter pos?) (dedupe)) >n))
            >bf (mi/signal! (mi/latest vector >bs >bc))

            >cs (mi/signal! (mi/eduction (map :c) >s))
            >cc (mi/signal! (mi/eduction (comp (map (fn [m] (get-in m [:c :n]))) (filter pos?) (dedupe)) >n))
            >cf (mi/signal! (mi/latest vector >cs >cc))

            >v  (mi/signal! (mi/latest (fn [[a _] [b _] [c _]] (+ a b c)) >af >bf >cf))]
        [(mi/stream! (mi/ap (let [v (mi/?< >af)] (println :af v))))
         (mi/stream! (mi/ap (let [v (mi/?< >bf)] (println :bf v))))
         (mi/stream! (mi/ap (let [v (mi/?< >cf)] (println :cf v))))
         (mi/stream! (mi/ap (let [v (mi/?<  >v)] (println :v v))))])))

  (dispose)
  (def dispose (r prn prn))
  (swap! nodes_ update-in [:a :n] inc)
  (swap! nodes_ update-in [:b :n] inc)
  (swap! nodes_ update-in [:c :n] inc)

  "http://ix.io/3Gw0")
;; => nil
;; => nil
