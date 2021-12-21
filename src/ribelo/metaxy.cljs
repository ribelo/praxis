(ns ribelo.metaxy
  (:refer-clojure :exclude [memoize update run! reify])
  (:require-macros [ribelo.metaxy :refer [reify defnode defevent defupdate defwatch defeffect]])
  (:require
   [taoensso.timbre :as timbre]
   [missionary.core :as mi]
   ;; ["react" :as react]
   ))

(declare get-node running?)

(def ^:private nodes_ (atom {}))
(def ^:private mbx (mi/mbx))
(def ^:private stream (mi/dfv))
(def reactor (atom nil))

(def dag
  (cljs.core/reify
    ILookup
    (-lookup [this k]
      (when (.get @nodes_ k) (get-node k)))

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
    (-invoke [this k]
      (when (.get @nodes_ k) (get-node k)))
    (-invoke [this k x]
      (when-let [n (dag k)]
        (n x)))
    (-invoke [this k x y]
      (when-let [n (dag k)]
        ((n x) y)))))

(defmulti dispatch (fn [e _] e))

(defmethod dispatch :default
  [e]
  (mbx e))

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

(defn promise?
  "Return `true` if `v` is a promise instance or is a thenable
  object.
  https://github.com/funcool/potok/blob/master/src/potok/core.cljs#L74"
  [v]
  (or (instance? js/Promise v)
      (and (goog.isObject v)
           (fn? (unchecked-get v "then")))))

(defn- -reset-graph-input! [dag m]
  (reduce-kv (fn [_ k v] (when-let [atm_ (-> @dag (.get k) .-input)] (reset! atm_ v))) nil m))

(defprotocol Identifiable
  (id   [_]))

(defprotocol INode
  (dependencies [_]))

(defn get-node [id]
  (when (.get @nodes_ id)
    (reify id
      INode
      (dependencies [_]
        (-> @nodes_ (.get id) .-deps))

      IFn
      (-invoke [_]
        ((-> @nodes_ (.get id) .-flow)))

      (-invoke [_ deps]
        ((-> @nodes_ (.get id) .-f) id deps))

      (-invoke [_ s f]
        ((-> @nodes_ (.get id) .-flow) s f))

      IDeref
      (-deref [_]
        (let [>f (-> @nodes_ (.get id) .-flow)]
          (mi/reduce (comp reduced {}) nil >f))))))

(defrecord Vertex [id f input deps flow])

(defn- -vertex [{:keys [id f input deps flow]}]
  (->Vertex id f input deps flow))

(defn- -link! [id >fs]
  (if-let [vrtx (get-node id)]
    (mi/signal! (apply mi/latest (fn [& args] [id (vrtx (into {} args))]) >fs))
    (throw (ex-info "node dosen't exists!" {:id id}))))

(defn- -build-graph []
  (loop [[[id vrtx] & more] @dag acc []]
    (if id
      (if-let [f (some-> vrtx .-flow mi/signal!)]
        (recur more (conj acc f))
        (let [ks (.-deps vrtx)
              in (mapv (fn [k]
                         (if-let [>f (some-> @dag (.get k) .-flow)]
                           >f
                           (throw (ex-info "node dosen't exists!" {:id k})))) ks)]
          (if (every? some? in)
            (let [>f (-link! id in)]
              (swap! dag assoc-in [id :flow] >f)
              (recur more (conj acc >f)))
            (recur (conj (into [] more) [id vrtx]) acc))))
      acc)))

(defmulti handle-error (fn [e _err] (id e)))

(defmethod handle-error :default
  [e err]
  (timbre/warn "Using default error handler, consider using your own!")
  (timbre/error "event failure id:" (id e) (ex-message err)))

(defn- -process-update-event [e]
  ((mi/sp
     (let [fs   (mapv (fn [id] (.-flow (.get @dag id))) (dependencies e))
           r    (mi/? (mi/reduce (comp reduced {}) nil (apply mi/latest (fn [& args] (update e (into {} args))) fs)))]
       (if (map? r)
         (-reset-graph-input! dag r)
         (timbre/errorf "result of UpdateEvent: %s should be a map or missionary ap!" (id e)))))
   #() #(handle-error e %)))

(defn- -process-watch-event [e]
  ((mi/sp
    (when-let [>f (watch e dag (mi/? stream))]
      (cond
        (fn? >f)
        (mi/? (mi/reduce (fn [_ e] (dispatch e)) >f))

        (promise? >f)
        (-> >f (.then (fn [s] (dispatch s))) (.catch (fn [err] (handle-error e err))))

        :else
        (timbre/errorf "result of WatchEvent: %s should be missionary ap! or js/promise" (id e)))))
   #() #(handle-error e %)))

(defn- -process-effect-event [e]
  ((mi/sp
    (when-let [>f (effect e dag (mi/? stream))]
      (cond
        (fn? >f)
        (mi/? (mi/reduce (constantly nil) >f))

        (promise? >f)
        (-> >f (.then #()) (.catch (fn [err] (handle-error e err))))

        :else
        (timbre/errorf "result of EffectEvent: %s should be missionary ap! or js/promise" (id e)))))
   #() #(handle-error e %)))

(defn add-node!
  ([id x]
   (when-let [vrtx (.get @dag id)]
     (timbre/warn "overwriting node id:" id)
     (when-let [>flow (.-flow vrtx)]
       (when (running?) (>flow))))
   (cond
     (instance? Atom x)
     (let [>flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch x))]
       (swap! dag assoc id (-vertex {:id id :flow >flow :input x})))

     (fn? x)
     (swap! dag assoc id (-vertex {:id id :flow x}))

     :else
     (add-node! id (atom x))))

  ([id deps f]
   (when-let [vrtx (.get @dag id)]
     (timbre/warn "overwriting node id:" id)
     (when-let [>flow (.-flow vrtx)]
       (>flow)))
   (swap! dag assoc id (-vertex {:id id :f f :deps deps})))

  ([id deps f >flow]
   (when-let [vrtx (.get @dag id)]
     (timbre/warn "overwriting node id:" id)
     (when-let [>flow (.-flow vrtx)]
       (>flow)))
   (swap! @dag assoc id (-vertex {:id id :f f :deps deps :flow >flow}))))

(defn add-watch! [e]
  ((mi/sp
     (when (mi/? stream)
       (dispatch e)))
   #() #()))

(defn listen! [deps f]
  (let [dfv (mi/dfv)]
    (dispatch
     (reify (str "listen-" (random-uuid))
       EffectEvent
       (effect [e m _]
         (let [deps (cond-> deps (not (seq? deps)) vector)
               fs   (mapv (fn [id] (some-> (.get @dag id) .-flow)) deps)]
           (if (reduce (fn [_ >f] (if >f true (reduced false))) false fs)
             (dfv (mi/stream! (apply mi/latest (fn [& args] (f e (into {} args))) fs)))
             (timbre/error "some of deps dosen't exists!"))))))
    (fn []
      ((mi/sp (when-let [>f (mi/? dfv)] (>f))) #() #()))))

(defn subscribe!
  ([id]
   (subscribe! id ::none))
  ([id x]
   (let [atm_ (atom nil)]
     (listen! id
       (fn [_ m]
         (if (= ::none x)
           (reset! atm_ (.get m id))
           (reset! atm_ ((.get m id) x)))))
     atm_)))

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

(defn build! []
  (if-not @reactor
    (reset! reactor
      (mi/reactor
       (let [xs (-build-graph)
             >e (mi/stream! (mi/ap (loop [] (mi/amb> (mi/? mbx) (recur)))))]
         (stream >e)
         (reduce (fn [acc >f] (conj acc (mi/stream! >f))) [] xs)
         (mi/stream! (->> >e (mi/eduction (filter update-event?) (map (fn [e] (-process-update-event e))))))
         (mi/stream! (->> >e (mi/eduction (filter watch-event?)  (map (fn [e] (-process-watch-event  e))))))
         (mi/stream! (->> >e (mi/eduction (filter effect-event?) (map (fn [e] (-process-effect-event e)))))))))
    (timbre/error "graph already builded!")))

(defn run! []
  (if-let [r @reactor]
    (r #() #(timbre/error %))
    (do (build!) (run!))))

(defn running? []
  (some? @reactor))

(defn dispose! []
  (@reactor))

;; -----------------------------------------

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

(def sub (subscribe! dag ::c {:x 10}))
@sub

(lstn)
(dispose)

(defupdate ::update-test
  [e {::keys [store]}]
  (println :update-test store)
  {::store {:a (rand-int 10) :b (rand-int 10) :c (rand-int 10)}})

(defupdate ::update-test2
  [e {::keys [store]} {:keys [x]}]
  (println :update-test2 :x x)
  {::store {:a x :b x :c x}})

(emit! dag
       (reify ::update-test
         INode
         (dependencies [_] [::store])
         UpdateEvent
         (update [id {::keys [store] :as dag}]
           (println :store-event store :dag dag)
           {::store {:a (rand-int 10) :b (rand-int 10) :c (rand-int 10)}})))

(macroexpand-1 (defupdate update-test
                 [e {:keys [store a b]}]
                 {:store (assoc store :d (+ a b))}))

(emit! dag (update-test))

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
