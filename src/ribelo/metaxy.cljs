(ns ribelo.metaxy
  (:refer-clojure :exclude [memoize update run! reify defevent])
  (:require-macros [ribelo.metaxy :refer [reify with-increment!]])
  (:require
   [taoensso.timbre :as timbre]
   [missionary.core :as mi]
   ["react" :as react]))

(defprotocol Identifiable
  (-id   [_])
  (-type [_]))

(defprotocol Subscribable
  (subscribe!    [_     ] [_ id  ] [_ id m])
  (-inc!         [_     ] [_ id  ])
  (-dec!         [_     ] [_ id  ]))

(defprotocol Publisher
  (listen!       [_    f] [_ id    f] [_ id m f]))

(defprotocol INode
  (-deps         [_     ])
  (-listeners    [_     ])
  (-value        [_     ] [_    x])
  (-fn           [_    x]))

(defprotocol IGraph
  (add-node!     [_ id    ] [_ id out] [_ id deps f] [_ id deps out f])
  (add-watch!    [_      x])
  (emit!         [_      e])

  (build!        [_       ])
  (run!          [_       ])
  (running?      [_       ]))

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
    (if-let [^Vertex r (.get nodes id)]
      (let [nodes' (update-in nodes [id :subscribers] inc)]
        (if (zero? (.-subscribers r))
          (reduce -recursive-increment nodes' (.-deps r))
          nodes'))
      nodes)))

(defn- -recursive-decrement [nodes id]
  (if-not id
    nodes
    (if-let [^Vertex r (.get nodes id)]
      (let [nodes' (update-in nodes [id :subscribers] dec)]
        (if (= 1 (.-subscribers r))
          (reduce -recursive-decrement nodes' (.-deps r))
          nodes'))
      nodes)))

(defn- -reset-graph-state [nodes m]
  (reduce-kv
    (fn [acc k v]
      (when (some? k)
        (if-let [vrtx (.get acc k)]
          (let [state (.-state vrtx)]
            (case (.-type vrtx)
              :atom
              (do (reset! state v) acc)

              :value
              (assoc-in nodes [k :state] v)

              (do (timbre/error "can't reset dataflow node" k "in graph" (.-id nodes)) acc)))
          (timbre/error "can't reset node" k "in graph" (.-id nodes)  ", node dosen't exists!"))))
    nodes
    m))

(defmacro with-increment! [node & body]
  `(do
     (-inc! ~node)
     (do ~@body)
     (-dec! ~node)))

(deftype Node [id dag]
  Identifiable
  (-id [_]
    id)

  (-type [_]
    (-> @dag (.get id) .-type))

  INode
  (-deps [_]
    (-> @dag (.get id) .-deps))

  (-listeners [_]
    (-> @dag (.get id) .-listeners))

  (-value [_]
    (-> @dag (.get id) .-state))

  (-fn [_ deps]
    ((-> @dag (.get id) .-f) id deps))

  Subscribable
  (-inc! [_]
    (swap! dag #(-recursive-increment % id)))

  (-dec! [_]
    (swap! dag #(-recursive-decrement % id)))

  Publisher
  (listen! [this f]
    (listen! this nil f))

  (listen! [_ m f]
    (listen! dag id m f))

  IDeref
  (-deref [this]
    (-value this))

  IFn
  (-invoke [this x]
    (let [v (-value this)]
      (case (-type this)
        :atom
        (reset! v x)

        :value
        (swap! dag assoc-in [id :state] x)

        :dataflow
        ((mi/timeout 0 v)
         (fn [_]
           (let [d (mi/dfv)]
             (d x)
             (swap! dag assoc-in [id :state] d)))
         (fn [_] (v x))))))

  (-invoke [this x y]
    (when-let [v (-value this)]
      (with-increment! this
        (case (-type this)
          :atom
          ((mi/sp (deref v)) x y)

          :value
          ((mi/sp v) x y)

          :dataflow
          (v x y))))))

(defn- -node [dag id]
  (Node. id dag))

(defn- -notify-listeners! [node v]
  ((mi/reduce (constantly nil)
     (mi/ap (let [[_ f m] (mi/?> (mi/seed (-listeners node)))]
              (if-not (fn? v) (f (-id node) v) (f (-id node) (v m))))))
   #(timbre/debug "listener update for id:" (-id node))
   #(timbre/error "listeners failure for id:" (-id node) %)))

(defn- -link! [dag >graph id fs]
  (if-let [node (get dag id)]
    (let [>nodes (mi/signal! (apply mi/latest (fn [& args] (into {} args)) (mapv second fs)))
          >count (mi/signal! (mi/eduction (comp (map (fn [nodes] (.-subscribers ^Vertex (.get nodes id)))) (filter pos?) (dedupe)) >graph))
          >lstn  (mi/signal! (mi/relieve {} (mi/eduction (comp (map (fn [nodes] (.-listeners ^Vertex (.get nodes id)))) (-count-incrase?)) >graph)))
          >f1    (mi/signal! (->> (mi/latest (fn [_ deps] (let [v (-fn node deps)] [id v])) >count >nodes)
                                  (mi/eduction (comp (dedupe) (map (fn [[id v]] (node v) [id v]))))))
          >f2    (mi/signal! (mi/latest (fn [_ [id v]] (-notify-listeners! node v) [id v]) >lstn >f1))]
      >f2)
    (throw (ex-info "node dosen't exists!" {:id id}))))

(defn- -build-graph [dag >graph]
  (let [snapshot @dag]
    (loop [[[id vrtx] & more] snapshot acc [] m {}]
      (if id
        (if-let [f (some-> vrtx .-ap mi/signal!)]
          (recur more (conj acc f) (assoc m id f))
          (let [ks (.-deps vrtx)]
            (println :-build-graph :id id :ks ks)
            (let [in (mapv (fn [k]
                             (if (snapshot k)
                               (when-let [ap (.get m k)]
                                 [k ap])
                               (throw (ex-info "node dosen't exists!" {:id k})))) ks)]
              (println :-build-graph :id id :in in)
              (if (every? some? in)
                (let [f (-link! dag >graph id in)]
                  (recur more (conj acc f) (assoc m id f)))
                (recur (conj (into [] more) [id vrtx]) acc m)))))
        acc))))

(defmulti handle-error (fn [e err] (-id e)))

(defmethod handle-error :default
  [e err]
  (timbre/warn "Using default error handler, consider using your own!")
  (timbre/error "event failure id:" (-id e) (ex-message err)))

(defn- -process-update-event [e dag]
  (let [r (try (update e dag)
               (catch js/Error err
                 (handle-error e err)))]
    (cond
      (map? r)
      (swap! dag -reset-graph-state r)
      (fn? r)
      (r #(swap! dag -reset-graph-state %) #(handle-error e %))
      :else
      (timbre/errorf "result of UpdateEvent: %s should be a map or missionary ap!" (-id e)))))

(defn- -process-watch-event [e dag stream]
  (let [>f (try (watch e dag stream)
                (catch js/Error err
                  (handle-error e err)))]
    (cond
      (fn? >f)
      ((mi/reduce (fn [_ e] (emit! dag e)) >f) #() #(handle-error e %))

      (some? >f)
      (timbre/errorf "result of WatchEvent: %s should be missionary ap!" (-id e)))))

(defn- -process-effect-event [e dag stream]
  (let [>f (try (effect e dag stream)
                (catch js/Error err
                  (handle-error e err)))]
    (cond
      (fn? >f)
      ((mi/reduce (constantly nil) >f) #() #(handle-error e %))

      (some? >f)
      (timbre/errorf "result of EffectEvent %s should be missionary ap!" (-id e)))))

(defrecord Vertex [id type state f deps ap subscribers listeners])

(defn- -vertex [{:keys [id type state f deps ap subscribers listeners]}]
  (->Vertex id type state f deps ap subscribers listeners))

(deftype Graph [graph-id nodes_ ^:unsynchronized-mutable mbx stream ^:unsynchronized-mutable graph-reactor ^:unsynchronized-mutable running?]
  Identifiable
  (-id [_] graph-id)

  IGraph
  (add-node! [_ id x]
      (if-not graph-reactor
        (do
          (when (-> @nodes_ (.get id))
            (timbre/warn "overwriting node id:" id "for graph id:" graph-id))
          (cond
            (instance? Atom x)
            (let [ap (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch x))]
              (swap! nodes_ assoc id (-vertex {:id id :type :atom :state x :f (constantly nil) :deps nil :ap ap :subscribers 0 :listeners #{}})))

            (fn? x)
            (swap! nodes_ assoc id (-vertex {:id id :type :flow :f (constantly nil) :deps nil :ap x :subscribers 0 :listeners #{}}))

            :else
            (let [ap (mi/eduction (comp (map (fn [nodes] [id (.-state ^Vertex (.get nodes id))])) (dedupe)) (mi/watch nodes_))]
              (swap! nodes_ assoc id (-vertex {:id id :type :value :state x :f (constantly nil) :deps nil :ap ap :subscribers 0 :listeners #{}})))))
        (timbre/error "can't add node id:" id " to builded graph id:" graph-id)))

  (add-node! [_ id deps f]
      (if-not graph-reactor
        (do
          (when (.get @nodes_ id)
            (timbre/warn "overwriting node id:" id "for graph:" graph-id))
          (swap! nodes_ assoc id (-vertex {:id id :type :dataflow :state (mi/dfv) :f f :deps deps :ap nil :subscribers 0 :listeners #{}})))
        (timbre/error "can't add node id:" id " to builded graph id:" graph-id)))

  (add-watch! [this x]
    ((mi/sp
       (when (mi/? stream)
         (emit! this x)))
     #() #()))

  (build! [this]
    (if-not graph-reactor
      (do
        (set! graph-reactor
          (mi/reactor
            (let [>g (mi/signal! (mi/eduction (dedupe) (mi/watch nodes_)))
                  xs (-build-graph this >g)
                  >e (mi/stream! (mi/ap (loop [] (mi/amb> (mi/? mbx) (recur)))))]
              (stream >e)
              (reduce (fn [acc >f] (conj acc (mi/stream! >f))) [] xs)
              (mi/stream! (->> >e (mi/eduction (filter update-event?) (map (fn [e] (-process-update-event e this   ))))))
              (mi/stream! (->> >e (mi/eduction (filter watch-event?)  (map (fn [e] (-process-watch-event  e this >e))))))
              (mi/stream! (->> >e (mi/eduction (filter effect-event?) (map (fn [e] (-process-effect-event e this >e)))))))))
        this)
      (timbre/error "graph" graph-id "already builded!")))

  (emit! [_ e]
    (mbx e))

  (run! [_]
    (set! graph-reactor (graph-reactor #(prn :reactor %) #(timbre/error %)))
    (set! running? true))

  (running? [_]
    running?)

  Publisher
  (listen! [this id f]
    (listen! this id nil f))

  (listen! [_ id m f]
    (swap! nodes_
      (fn [nodes]
        (-> nodes
            (#(-recursive-increment % id))
            (update-in [id :listeners] conj [id f m]))))
    (fn []
      (swap! nodes_
        (fn [nodes]
          (-> nodes
              (#(-recursive-decrement % id))
              (update-in [id :listeners] disj [id f m]))))))

  Subscribable
  (subscribe! [this id]
    (subscribe! this id nil))

  (subscribe! [this id m]
    (let [-m (react/useRef m)
          m' (if (= m (.-current -m)) (.-current -m) m)
          [state set-state!] (react/useState nil)]
      (react/useEffect
        (fn []
          (set! (.-current -m) m)
          (listen! this id m (fn [_ v] (set-state! v))))
        #js [(str id) m'])
      (cljs.core/reify
        IDeref
        (-deref [_] state)
        IReset
        (-reset! [_ x] (set-state! x)))))

  ILookup
  (-lookup [this k]
    (when (.get @nodes_ k) (-node this k)))

  IDeref
  (-deref [_]
    (deref nodes_))

  ISwap
  (-swap! [_ f]
    (swap! nodes_ f))
  (-swap! [_ f x]
    (swap! nodes_ f x))
  (-swap! [_ f x y]
    (swap! nodes_ f x y))
  (-swap! [_ f x y more]
    (swap! nodes_ f x y more))

  IFn
  (-invoke [this k]
    (when (.get @nodes_ k) (-node this k))))

(defn graph [id]
  (Graph. id (atom {}) (mi/mbx) (mi/dfv) nil false))

;; -----------------------------------------

(comment
 (def store {:a 1 :b 1 :c 1})

 (def dag (graph :metaxy))

 (add-node! dag ::store store)

 (add-node! dag ::a
   [::store]
   (fn [id {::keys [store]}]
     (println :eval-node :id id :val (:a store))
     (:a store)))

 (add-node! dag ::b
   [::store]
   (fn [id {::keys [store]}]
     (println :store store)
     (println :eval-node :id id :val (:b store))
     (:b store)))

 (add-node! dag ::c
   [::a ::b]
   (fn [id {::keys [a b]}]
     (fn [{:keys [x]}]
       (println :eval-node :id id :a+b+ (+ a b x)))
     (+ a b x)))

 (def dispose (-> dag build! run!))
 ((mi/sp (println ::c (mi/? (dag ::c)))) prn prn)
 (tap> @dag)
 (dispose)

 ((mi/sp (println ::x (mi/? (::b dag)))) prn prn)
 @(::store dag)


 (tap> @dag)

 (emit! dag
        (reify ::update-test
          UpdateEvent
          (update [id {::keys [store]}]
            (tap> [:event id store])
            {::store {:a 2 :b 3 :c 4}})))

 (tap> @dag)

 (def a_ (listen! dag
           ::a
           (fn [_id r]
             (println :listen _id :start)
             (println :listen _id r))))
 (a_)

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

 (def nodes_ (atom {:a {:subscribers 0}
                    :b {:subscribers 0}
                    :c {:subscribers 0}}))
 (def store_ (atom {:a 0 :b 0 :c 0}))

 (def r
   (mi/reactor
     (let [>n (mi/signal! (mi/watch nodes_))
           >s (mi/signal! (mi/watch store_))

           >as (mi/signal! (mi/eduction (map :a) >s))
           >ac (mi/signal! (mi/eduction (comp (map (fn [m] (get-in m [:a :n]))) (filter pos?) (dedupe)) >n))
           >af (mi/signal! (mi/latest vector >as >ac))

           >bs (mi/signal! (mi/eduction (map :b) >s))
           >bc (mi/signal! (mi/eduction (comp (map (fn [m] (get-in m [:b :n]))) (filter pos?) (dedupe)) >n))
           >bf (mi/signal! (mi/latest vector >bs >bc))

           >cs (mi/signal! (mi/eduction (map :c) >s))
           >cc (mi/signal! (mi/eduction (comp (map (fn [m] (get-in m [:c :n]))) (filter pos?) (dedupe)) >n))
           >cf (mi/signal! (mi/latest vector >cs >cc))

           >v (mi/signal! (mi/latest (fn [[a _] [b _] [c _]] (+ a b c)) >af >bf >cf))]
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
