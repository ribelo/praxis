(ns ribelo.metaxy
  (:refer-clojure :exclude [memoize update run! reify defevent])
  (:require-macros [ribelo.metaxy :refer [reify with-increment!]])
  (:require
   [taoensso.timbre :as timbre]
   [missionary.core :as mi]
   ;; ["react" :as react]
   ))

(defprotocol Identifiable
  (-id   [_])
  (-type [_]))

(defprotocol INode
  (-deps         [_     ])
  (-fn           [_    x]))

(defprotocol IGraph
  (add-node!     [_ id  ] [_ id out] [_ id deps f] [_ id deps out f])
  (add-watch!    [_    x])
  (emit!         [_    e])

  (add-listener! [_ id f])
  (listen!       [_    f] [_ id   f] [_ id m f])
  (subscribe!    [_     ] [_ id    ] [_ id m  ])

  (build!        [_     ])
  (run!          [_     ])
  (running?      [_     ]))

(defprotocol ListenEvent
  (on-change [_ dag]))

(defprotocol UpdateEvent
  (update [_ dag]))

(defprotocol WatchEvent
  (watch [_ node stream]))

(defprotocol EffectEvent
  (effect [_ node stream]))

(defn listen-event? [e]
  (satisfies? ListenEvent e))

(defn update-event? [e]
  (satisfies? UpdateEvent e))

(defn watch-event? [e]
  (satisfies? WatchEvent e))

(defn effect-event? [e]
  (satisfies? EffectEvent e))

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

(deftype Node [id dag]
  Identifiable
  (-id [_]
    id)

  INode
  (-deps [_]
    (-> @dag (.get id) .-deps))

  IFn
  (-invoke [_]
    (let [>f  (-> @dag (.get id) .-flow)
          dfv (mi/dfv)]
      ((mi/ap (dfv (mi/?> >f))) prn prn)
      dfv))

  (-invoke [_ deps]
    ((-> @dag (.get id) .-f) id deps))

  (-invoke [_ s f]
    ((-> @dag (.get id) .-flow) s f))

  IDeref
  (-deref [_]
    (let [>f  (-> @dag (.get id) .-flow)
          dfv (mi/dfv)]
      ((mi/ap (dfv (mi/?> >f))) prn prn)
      dfv)))

(defn- -node [dag id]
  (Node. id dag))

(defrecord Vertex [id type f state deps flow])

(defn- -vertex [{:keys [id type f state deps flow]}]
  (->Vertex id type f state deps flow))

(defn- -link! [dag id fs]
  (if-let [node (get dag id)]
    (mi/signal! (apply mi/latest (fn [& args] (let [v (node (into {} args))] [id v])) (mapv second fs)))
    (throw (ex-info "node dosen't exists!" {:id id}))))

(defn- -build-graph [dag]
  (let [snapshot @dag]
    (loop [[[id vrtx] & more] snapshot acc [] m {}]
      (if id
        (if-let [f (some-> vrtx .-flow mi/signal!)]
          (recur more (conj acc f) (assoc m id f))
          (let [ks (.-deps vrtx)]
            (let [in (mapv (fn [k]
                             (if (snapshot k)
                               (when-let [ap (.get m k)]
                                 [k ap])
                               (throw (ex-info "node dosen't exists!" {:id k})))) ks)]
              (if (every? some? in)
                (let [f (-link! dag id in)]
                  (swap! dag assoc-in [id :flow] f)
                  (recur more (conj acc f) (assoc m id f)))
                (recur (conj (into [] more) [id vrtx]) acc m)))))
        acc))))

(defmulti handle-error (fn [e err] (-id e)))

(defmethod handle-error :default
  [e err]
  (timbre/warn "Using default error handler, consider using your own!")
  (timbre/error "event failure id:" (-id e) (ex-message err)))

(defn- -process-listen-event [e dag]
  (let [fs       (mapv (fn [id] (.-flow (.get @dag id))) (-deps e))
        >f       (mi/stream! (apply mi/latest (fn [& args] (println :args args) (on-change e (into {} args))) fs))
        snapshot @(.-listeners dag)]
    (when-some [lstn (.get snapshot (-id e))]
      (timbre/warn "overwriting node id:" (-id e) "for graph id:" (-id dag))
      (lstn))
    (swap! (.-listeners dag) assoc (-id e) >f)))

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

(deftype Graph [graph-id nodes_ listeners_ ^:unsynchronized-mutable mbx stream ^:unsynchronized-mutable graph-reactor ^:unsynchronized-mutable running?]
  Identifiable
  (-id [_] graph-id)

  IGraph
  (add-node! [_ id x]
      (when (-> @nodes_ (.get id))
        (timbre/warn "overwriting node id:" id "for graph id:" graph-id))
      (when-let [f (.get @nodes_ id)]
        (f))
      (cond
        (instance? Atom x)
        (let [>f (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch x))]
          (swap! nodes_ assoc id (-vertex {:id id :type :atom :state x :f (constantly nil) :deps nil :flow >f})))

        (fn? x)
        (swap! nodes_ assoc id (-vertex {:id id :type :flow :f (constantly nil) :deps nil :flow x}))

        :else
        (let [>f (mi/eduction (comp (map (fn [nodes] [id (.-state ^Vertex (.get nodes id))])) (dedupe)) (mi/watch nodes_))]
          (swap! nodes_ assoc id (-vertex {:id id :type :value :state x :f (constantly nil) :deps nil :flow >f})))))

  (add-node! [_ id deps f]
      (when (.get @nodes_ id)
        (timbre/warn "overwriting node id:" id "for graph:" graph-id))
      (swap! nodes_ assoc id (-vertex {:id id :type :dataflow :state nil :f f :deps deps :ap nil})))

  (add-node! [_ id deps f ap]
      (when (.get @nodes_ id)
        (timbre/warn "overwriting node id:" id "for graph:" graph-id))
      (swap! nodes_ assoc id (-vertex {:id id :type :dataflow :state nil :f f :deps deps :ap ap})))

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
                (let [xs (-build-graph this)
                      >e (mi/stream! (mi/ap (loop [] (mi/amb> (mi/? mbx) (recur)))))]
                  (stream >e)
                  (reduce (fn [acc >f] (conj acc (mi/stream! >f))) [] xs)
                  (mi/stream! (->> >e (mi/eduction (filter listen-event?) (map (fn [e] (-process-listen-event e this   ))))))
                  (mi/stream! (->> >e (mi/eduction (filter update-event?) (map (fn [e] (-process-update-event e this   ))))))
                  (mi/stream! (->> >e (mi/eduction (filter watch-event?)  (map (fn [e] (-process-watch-event  e this >e))))))
                  (mi/stream! (->> >e (mi/eduction (filter effect-event?) (map (fn [e] (-process-effect-event e this >e)))))))))
        this)
      (timbre/error "graph" graph-id "already builded!")))

  (emit! [_ e]
    (mbx e))

  (run! [_]
    (set! graph-reactor (graph-reactor #(prn :reactor %) #(timbre/error %)))
    (set! running? true)
    (fn []
      (set! running? false)
      (graph-reactor)))

  (running? [_]
    running?)

  (add-listener! [_ id f]
    (swap! listeners_ assoc id f))

  (listen! [this deps f]
    (let [uuid (random-uuid)]
      (emit! this
             (reify uuid
               INode
               (-deps [_]
                 (if (vector? deps) deps [deps]))

               ListenEvent
               (on-change [e m]
                 (f e m))))
      (fn []
        (if-let [lstn (.get @listeners_ uuid)]
          (lstn)
          (timbre/warn "can't unlisten id:" uuid "for graph id:" graph-id)))))

  ;; (subscribe! [this id m]
  ;;   (let [-m (react/useRef m)
  ;;         m' (if (= m (.-current -m)) (.-current -m) m)
  ;;         [state set-state!] (react/useState nil)]
  ;;     (react/useEffect
  ;;       (fn []
  ;;         (set! (.-current -m) m)
  ;;         (listen! this id m (fn [_ v] (set-state! v))))
  ;;       #js [(str id) m'])
  ;;     (cljs.core/reify
  ;;       IDeref
  ;;       (-deref [_] state)
  ;;       IReset
  ;;       (-reset! [_ x] (set-state! x)))))

  ILookup
  (-lookup [this k]
    (when (.get @nodes_ k) (-node this k)))

  IDeref
  (-deref [_]
    (deref nodes_))

  ISwap
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
  (Graph. id (atom {}) (atom {}) (mi/mbx) (mi/dfv) nil false))

;; -----------------------------------------

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
    (println :eval-node :id id :val (:b store))
    (:b store)))

(def dispose (-> dag build! run!))
(def lstn (listen! dag ::a (fn [e dag]
                             (println :e e)
                             (println :dag dag))))
(lstn)
(dispose)

(emit! dag
       (reify ::update-test
         UpdateEvent
         (update [id {::keys [store] :as dag}]
           {::store {:a (rand-int 10) :b (rand-int 10) :c (rand-int 10)}})))

(emit! dag
       (reify ::watch-test
         WatchEvent
         (watch [id {::keys [store a] :as dag} _]
           (mi/ap
             (println :watch-test :store (mi/? @store))))))

(emit! dag
       (reify ::c
         UpdateEvent
         (update [id {::keys [store]}]
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

(def d (mi/dfv))

(loop [i 0]
  (when (< i 100)
    ((mi/sp (println :i i :dfv (mi/? d))) prn prn)
    (recur (inc i))))

(d 1)

"http://ix.io/3Gw0"
