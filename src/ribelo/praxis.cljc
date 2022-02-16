(ns ribelo.praxis
  (:refer-clojure :exclude [update run! reify deref])
  #?(:cljs (:require-macros [ribelo.praxis :refer [reify defnode defupdate defproc defeffect]]))
  (:require
   #?(:clj [clojure.core :as clj] :cljs [cljs.core :as cljs])
   [taoensso.timbre :as timbre]
   [missionary.core :as mi]
   [ribelo.extropy :as ex]
   #?(:cljs ["react" :as react])))

;; * macros

#?(:clj
   (defmacro reify
     ([id & impls]
      `(~(ex/-if-clj 'clojure.core/reify 'cljs.core/reify)
        ~'ribelo.praxis/PIdentifiable
        (~'-id [~'_] ~id)
        ~@impls))))

#?(:clj
   (defn- -parse-input [m]
     (when (map? m)
       (ex/-loop [me m :let [acc (transient [])]]
         (cond
           (= "keys" (name (ex/-k* me)))
           (recur (ex/-reduce conj! acc (ex/-mapv (ex/-comp (partial keyword (namespace (ex/-k* me))) name) (ex/-v* me))))

           :else
           (recur (conj! acc (keyword (namespace (ex/-k* me)) (name (ex/-v* me))))))
         (persistent! acc)))))

#?(:clj
   (defmacro defnode [id vargs & body]
     (if (seq body)
       (let [[nid m & more] vargs
             deps (-parse-input m)]
         `(do
            (ribelo.praxis/add-node!
              ~id ~deps
              ~(if-not (seq more)
                 `(fn [~nid ~m]
                    ~@body)
                 `(fn [~nid ~m]
                    (ribelo.extropy/-memoize
                      (fn ~(vec more)
                        ~@body)))))))
       `(ribelo.praxis/add-node!
          ~id ~vargs))))

#?(:clj
   (defmacro defevent [id [_ _ _ & more] & body]
     `(defmethod ~'ribelo.praxis/event ~id ~(into ['id] more)
        (reify ~id
          ~'ribelo.praxis/PNode
          ~@body))))

#?(:clj
   (defmacro defupdate [id [e deps & more] & body]
     `(defevent ~id [~e ~deps nil ~@more]
        ~'ribelo.praxis/UpdateEvent
        (ribelo.praxis/update [~e ~deps]
          ~@body))))

#?(:clj
   (defmacro defproc [id [e deps stream & _more :as vargs] & body]
     `(defevent ~id ~vargs
        ~'ribelo.praxis/PorcedureEvent
        (ribelo.praxis/procedure [~e ~deps ~stream]
                                 ~@body))))

#?(:clj
   (defmacro defeffect [id [e deps stream & _more :as vargs] & body]
     `(defevent ~id ~vargs
        ~'ribelo.praxis/EffectEvent
        (ribelo.praxis/effect [~e ~deps ~stream]
          ~@body))))

(declare node)

(def ^:private nodes_ (atom {}))
(def ^:private mbx (mi/mbx))
(def ^:private <stream (mi/dfv))
(def ^:private <reactor (mi/dfv))

(defprotocol PIdentifiable
  (-id [_]))

(defn identifiable? [e]
  (satisfies? PIdentifiable e))

(defprotocol PNode
  (-dependencies [_]))

#?(:clj
   (def dag
     (clj/reify
       clojure.lang.ILookup
       (valAt [this k]
         (when (ex/-get* @nodes_ k)
           (node k)))

       clojure.lang.IRef
       (clj/deref [_] @nodes_)

       clojure.lang.IAtom
       (swap [_ f x]
         (swap! nodes_ f x))
       (swap [_ f x y]
         (swap! nodes_ f x y))
       (swap [_ f x y more]
         (swap! nodes_ f x y more))

       clojure.lang.IFn
       (invoke [_ k]
         (when (ex/-get* @nodes_ k)
           (node k)))

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
       (-lookup [_ k]
         (when (ex/-get* @nodes_ k)
           (node k)))

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
         (when (ex/-get* @nodes_ k) (node k)))
       (-invoke [this k x]
         (when-let [n (dag k)]
           (n x)))
       (-invoke [this k x y]
         (when-let [n (dag k)]
           ((n x) y))))))

(defprotocol UpdateEvent
  (update [_ dag]))

(defprotocol PorcedureEvent
  (procedure [_ dag stream]))

(defprotocol EffectEvent
  (effect [_ dag stream]))

(defprotocol SilentEvent)

(defn update-event? [e]
  (satisfies? UpdateEvent e))

(defn procedure-event? [e]
  (satisfies? PorcedureEvent e))

(defn effect-event? [e]
  (satisfies? EffectEvent e))

(defn silent-event? [e]
  (satisfies? SilentEvent e))

(defn event? [e]
  (or (update-event? e)
      (procedure-event? e)
      (effect-event? e)))

(defmulti event
  (fn
    ([e                ] e)
    ([e _              ] e)
    ([e _ _            ] e)
    ([e _ _ _          ] e)
    ([e _ _ _ _        ] e)
    ([e _ _ _ _ _      ] e)
    ([e _ _ _ _ _ _    ] e)
    ([e _ _ _ _ _ _ _  ] e)
    ([e _ _ _ _ _ _ _ _] e)))

(defmethod event :default [e]
  (when-not (or (silent-event? e) (and (identifiable? e) (or (ex/-kw-identical? ::listen! (-id e)) (ex/-kw-identical? ::deref (-id e)))))
    (timbre/warnf "Using default event handler for %s, returning value as is, consider adding own method!"
                  (if (identifiable? e) (-id e) e)))
  e)

(defn dispatch [& args]
  (cond
    (keyword? (ex/-first args))
    (let [<v (mi/dfv)]
      (mbx [<v (apply event args)])
      <v)
    (vector? (ex/-first args))
    (ex/-loop [e args :let [acc (transient [])]]
      (let [<v (mi/dfv)]
        (mbx [<v (apply event e)])
        (recur (conj! acc <v)))
      (apply mi/join vector (persistent! acc)))))

(defn event-type [e]
  (cond
    (update-event? e) "UpdateEvent"
    (procedure-event? e)  "PorcedureEvent"
    (effect-event? e) "EffectEvent"
    :else             "UnknownEvent"))

(defmulti handle-error (fn [e _err] (-id ^PIdentifiable e)))

(defmethod handle-error :default
  [e err]
  (timbre/warn "Using default error handler, consider using your own!")
  (timbre/errorf "%s failure id: %s"
                 (event-type e)
                 (-id e))
  (timbre/error (ex-message err)))


(defn promise?
  "https://github.com/funcool/potok/blob/master/src/potok/core.cljs#L74"
  [v]
  #?(:clj false
     :cljs (or (instance? js/Promise v)
               (and (goog.isObject v)
                    (fn? (unchecked-get v "then"))))))

(defn publisher?
  [v]
  #?(:clj  (instance? missionary.impl.Reactor$Publisher v)
     :cljs (instance? missionary.impl.Reactor/Publisher v)))

(defn dataflow?
  [v]
  #?(:clj  (instance? missionary.impl.Dataflow$Dataflow v)
     :cljs (instance? missionary.impl/Dataflow v)))

(defn event-is? [id e]
  (and (some? e) (identifiable? e) (ex/-kw-identical? id (-id e))))

(defn -reset-graph-input! [dag m]
  (ex/-reduce-kv (fn [_ k v] (when-let [atm_ (-> @dag (ex/-get k) .-input)] (reset! atm_ v))) nil m))

(defrecord Vertex [id f input deps flow])

(defn- -vertex [{:keys [id f input deps flow]}]
  (->Vertex id f input deps flow))

#?(:clj
   (defn node [id]
     (when (ex/-get* @nodes_ id)
       (reify id
         PNode
         (-dependencies [_]
           (-> (ex/-get*  @nodes_ id) .-deps))

         clojure.lang.IFn
         (invoke [_]
           ((-> (ex/-get*  @nodes_ id) .-flow)))

         (invoke [_ deps]
           ((-> (ex/-get*  @nodes_ id) .-f) id deps))

         (invoke [_ s f]
           (let [>f (.-flow ^Vertex (ex/-get @nodes_ id))]
             ((mi/reduce (comp reduced second {}) nil >f) s f))))))

   :cljs
   (defn node [id]
     (when (ex/-get* @nodes_ id)
       (reify id
         PNode
         (-dependencies [_]
           (-> @nodes_ ^Vertex (ex/-get id) .-deps))

         IFn
         (-invoke [_]
           (.-flow ^Vertex (ex/-get @nodes_ id)))

         (-invoke [_ deps]
           ((.-f ^Vertex (ex/-get @nodes_ id)) id deps))

         (-invoke [_ s f]
           (let [>f (.-flow ^Vertex (ex/-get @nodes_ id))]
             ((mi/reduce (comp reduced second {}) nil >f) s f)))))))

(defn- -link! [id >fs]
  (if-let [vrtx (node id)]
    (mi/signal! (mi/eduction (dedupe) (apply mi/latest (fn [& args] [id (vrtx (into {} args))]) >fs)))
    (throw (ex-info (str "node " id " dosen't exists!") {:id id}))))

(defn- -build-graph! []
  (loop [[[id vrtx] & more] @dag acc []]
    (if id
      (if (some-> vrtx .-input)
        (recur more (conj acc (mi/signal! (.-flow vrtx))))
        (let [ks (.-deps ^Vertex vrtx)
              in (mapv (fn [k]
                         (if-let [x (some-> @dag (ex/-get k))]
                           (.-flow ^Vertex x)
                           (throw (ex-info (str "node " id " dosen't exists!") {:id k})))) ks)]
          (if (every? some? in)
            (let [>f (-link! id in)]
              (swap! dag assoc-in [id :flow] >f)
              (recur more (conj acc >f)))
            (recur (conj (into [] more) [id vrtx]) acc))))
      acc)))

(defeffect ::build-graph!
  [_ _ _]
  (mi/ap (-build-graph!)))

(defn -maybe-assign [<dfv v]
  (when <dfv (<dfv v)))

(defn- -process-update-event [<dfv e]
  ((mi/sp
     (when-let [<f (update e dag)]
       (if (fn? <f)
         (when-let [x (mi/? <f)]
           (if (map? x)
             (-reset-graph-input! dag x)
             (timbre/errorf "result of UpdateEvent sp: %s should be a map!, but is %s" (-id e) (type x))))
         (timbre/errorf "result of UpdateEvent: %s should be a sp!, but is %s" (-id e) (type <f)))))
   #(-maybe-assign <dfv true) #(do (handle-error e %) (-maybe-assign <dfv false))))

(defn- -process-procedure-event [<r e]
  ((mi/sp
     (when-let [>f (procedure e dag (mi/? <stream))]
       (cond
         (or (event? >f) (promise? >f))
         (mbx [<r >f])

         (fn? >f)
         (let [xs (mi/? (mi/reduce (fn [acc x] (conj acc x)) [] >f))
               dfvs (persistent!
                      (ex/-reduce
                        (fn [acc x]
                          (let [<v (mi/dfv)]
                            (if (or (event? x) (promise? x))
                              (do (mbx [<v x]) (conj! acc <v))
                              (do (<v x) (conj! acc <v)))))
                        (transient [])
                        xs))
               xs' (mi/? (mi/timeout (apply mi/join vector dfvs) (ex/-ms 1 :min) ::timeout))]
           (if-not (ex/-kw-identical? ::timeout xs')
             (-maybe-assign <r (if (= 1 (ex/-count* xs')) (ex/-first xs') xs'))
             (do (-maybe-assign <r false) (handle-error e (ex-info "timeout!" {})))))

         (publisher? >f)
         (-maybe-assign <r true)

         (dataflow? >f)
         (-maybe-assign <r (mi/? >f))

         :else
         (timbre/errorf "result of PorcedureEvent: %s should be event, missionary ap! or js/promise, but is: %s" (-id e) (type >f)))))
   (constantly nil) #(do (-maybe-assign <r false) (handle-error e %))))

(defn- -process-effect-event [e]
  ((mi/sp
     (mi/stream! (effect e dag (mi/? <stream))))
   (constantly nil) #(handle-error e %)))

#?(:cljs
   (defn- -process-promise [<r ^js e]
     ((mi/sp
        (let [<v (mi/dfv)]
          (-> e (.then (fn [ok] (<v (fn [] ok)))) (.catch (fn [err] (<v (fn [] (throw err))))))
          (when-let [x (mi/? (mi/absolve <v))]
            (cond
              (or (event? x) (promise? x))
              (mbx [<r x])

              (fn? x)
              (let [xs (mi/? (mi/reduce (fn [acc x] (conj acc x)) [] x))
                    dfvs (persistent!
                           (ex/-reduce
                             (fn [acc x]
                               (let [<v (mi/dfv)]
                                 (if (or (event? x) (promise? x))
                                   (do (mbx [<v x]) (conj! acc <v))
                                   (do (<v x) (conj! acc <v)))))
                             (transient [])
                             xs))
                    xs' (mi/? (mi/timeout (apply mi/join vector dfvs) (ex/-ms 1 :min) ::timeout))]
                (if-not (ex/-kw-identical? ::timeout xs')
                  (-maybe-assign <r (if (= 1 (ex/-count* xs')) (ex/-first xs') xs'))
                  (do (-maybe-assign <r false) (handle-error e (ex-info "timeout!" {})))))

              (publisher? x)
              (-maybe-assign <r true)

              :else
              (<r x)))))
      (constantly nil) #(do (<r %) (timbre/errorf "error in promise %s" %)))))

(defn add-node!
  ([id x]
   (tap> [:node :a id x (type x)])
   ((mi/sp
      (cond
        (instance? #?(:clj clojure.lang.Atom :cljs Atom) x)
        (let [>flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch x))]
          (swap! dag assoc id (-vertex {:id id :flow >flow :input x})))

        (fn? x)
        (swap! dag assoc id (-vertex {:id id :flow x}))

        :else
        (let [atm_ (atom x)
              >flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch atm_))]
          (swap! dag assoc id (-vertex {:id id :flow >flow :input atm_}))))
      (when (mi/? <reactor) (dispatch ::build-graph!)))
    #(timbre/debugf "node added successfuly %s" id %) #(timbre/error %)))

  ([id deps f]
   ((mi/sp
      (swap! dag assoc id (-vertex {:id id :f f :deps deps}))
      (when (mi/? <reactor) (dispatch ::build-graph!)))
    #(timbre/debugf "node added successfuly %s" id %) #(timbre/error %)))

  ([id deps f >flow]
   ((mi/sp
      (swap! @dag assoc id (-vertex {:id id :f f :deps deps :flow >flow}))
      (when (mi/? <reactor) (dispatch ::build-graph!)))
    #(timbre/debugf "node added successfuly %s" id %) #(timbre/error %))))

(defn add-watch! [e]
  ((mi/sp
     (when (mi/? <stream)
       (mbx [nil e])))
   (constantly nil) #(timbre/error %)))

(defmethod event ::listen!
  [_ id f]
  (reify ::listen!
    EffectEvent
    (effect [_ _ _]
      (if-let [>flow (some-> ^Vertex (ex/-get @dag id) .-flow)]
        (->> (mi/eduction (comp (dedupe) (map (fn [[e v]] (f e v)))) >flow)
             (mi/stream!))
        (throw (ex-info (ex/-format "node %s dosen't exists!" id) {:id id}))))))

(defn listen! [id f]
  (let [<v (dispatch ::listen! id f)]
    (fn []
      ((mi/sp (when-let [>f (mi/? <v)]
                (when (or (fn? >f) (publisher? >f))
                  (>f))))
       #(timbre/debugf "successful unlisten %s" id %)
       #(timbre/errorf "unsuccessful unlisten %s %s" id %)))))

#?(:cljs
   (defn deref [>f]
     (let [[state set-state!] (react/useState nil)
           <v (mi/dfv)]
       ((mi/sp
          (react/useEffect
            (fn []
              (<v (dispatch
                    (reify ::deref
                      EffectEvent
                      (effect [_ _ _]
                        (mi/stream! (mi/eduction (map (fn [x] (set-state! x))) >f)))))))
            (js/Array.)))
        #(constantly nil) #(timbre/errorf "deref error! %s" %))
       (cljs.core/reify
         IDeref
         (-deref [_] state)))))

#?(:cljs
   (defn subscribe
     ([id]
      (subscribe id ex/-sentinel))
     ([id m]
      (let [-m (react/useRef m)
            m' (if (= m (.-current -m)) (.-current -m) m)
            [state set-state!] (react/useState nil)]
        (react/useEffect
          (fn []
            (set! (.-current -m) m)
            (listen! id (fn [_ v] (if-not (fn? v) (set-state! v) (set-state! (v m))))))
          (js/Array. (str id) m'))
        (cljs.core/reify
          IDeref
          (-deref [_] state))))))

(defn run! []
  ((mi/sp
     (if-not (mi/? (mi/timeout <reactor 0))
       (let [r (mi/reactor
                 (let [>e (mi/stream! (mi/ap (loop [] (mi/amb> (mi/? mbx) (recur)))))]
                   (<stream >e)
                   (mi/stream! (->> >e (mi/eduction
                                         (filter (fn [[_ e]] (and (update-event? e) (not (silent-event? e)))))
                                         (map (fn [[<dfv e]] (-process-update-event <dfv e))))))
                   (mi/stream! (->> >e (mi/eduction
                                         (filter (fn [[_ e]] (and (procedure-event? e) (not (silent-event? e)))))
                                         (map (fn [[<dfv e]] (-process-procedure-event <dfv e))))))
                   (mi/stream! (->> >e (mi/eduction
                                         (filter (fn [[_ e]] (and (effect-event? e) (not (silent-event? e)))))
                                         (map (fn [[_ e]] (-process-effect-event e))))))
                   #?(:cljs
                      (mi/stream! (->> >e (mi/eduction
                                            (filter (fn [[_ e]] (promise? e)))
                                            (map (fn [[<dfv e]] (-process-promise <dfv e)))))))))]
         (<reactor (r #(timbre/infof "succesfuly shutdown reactor" %) #(timbre/error %))))
       (timbre/warn "graph already builded!")))
   (constantly nil) #(timbre/error %)))

(defn dispose! []
  ((mi/sp
     (when-let [cb (mi/? <reactor)]
       (timbre/info "dispose!")
       (cb)
       (reset! nodes_ nil)))
   (constantly nil) #(timbre/error %)))

(defeffect ::tap-node
  [_ dag _ id]
  (mi/ap
    (if-let [node (ex/-get* dag id)]
      (tap> (str "node '" id "' dosen't exists!")))))

(defeffect ::prn-node
  [_ dag _ id]
  (mi/ap
    (if-let [node (ex/-get* dag id)]
      (prn (mi/? node))
      (prn (str "node '" id "' dosen't exists!")))))


;; -----------------------------------------

(comment

  (def s1_ (atom nil))
  (def s2_ (atom nil))
  (def mbx (mi/mbx))
  (def >f (mi/ap (loop [] (mi/amb> (mi/? mbx) (recur)))))
  (def dispose
    ((mi/reactor
       (let [>f1 (mi/stream! (mi/ap (* 10 (mi/?> >f))))
             >f2 (mi/stream! (mi/ap (println :f2 (* 10 (mi/?> >f1)))))
             >f3 (mi/stream! (mi/ap (println :f3 (mi/?> >f))))]
         (reset! s1_ >f1)
         (reset! s2_ >f2)))
     #(prn :success %) #(prn :error %)))
  (@s1_)
  (mbx 1)
  ((mi/sp ((mi/? cancel))) prn prn)
  (dispose)

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
  (lstn)

  (def sub (subscribe ::c {:x 100}))
  @sub
  (sub)

  (lstn)
  (dispose!)
  ((mi/? reactor))

  (defupdate ::update-test
    [e {::keys [store]}]
    (println :update-test store)
    {::store {:a (rand-int 10) :b (rand-int 10) :c (rand-int 10)}})

  (dispatch ::update-test)

  (defeffect ::effect-test
    [e {::keys [store]} _]
    (mi/ap (println :effect-test (mi/? store))))

  (dispatch ::effect-test)

  (defupdate ::update-test2
    [e {::keys [store]} {:keys [x]}]
    (println :update-test2 :x x)
    {::store {:a x :b x :c x}}))
