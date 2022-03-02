(ns ribelo.praxis
  (:refer-clojure :exclude [update run! reify deref])
  #?(:cljs (:require-macros [ribelo.praxis :refer [reify defnode defupdate defproc defeffect]]))
  (:require
   #?(:clj  [clojure.core :as clj]
      :cljs [cljs.core :as cljs])
   [taoensso.timbre :as timbre]
   [missionary.core :as mi]
   [ribelo.extropy :as ex]
   [ribelo.fatum :as f]
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
   (defmacro defnode [& args]
     (if (= 2 (count args))
       `(ribelo.praxis/add-node!
          ~(nth args 0) ~(nth args 1))
       (let [id (nth args 0)
             props (nth args 1)]
         (if-not (map? props)
           (let [vargs (nth args 1)
                 body (drop 2 args)]
             `(defnode ~id {::memoize? false} ~vargs ~@body))
           (let [memoize? (ex/-get props ::memoize?)
                 vargs (nth args 2)
                 [nid m & more] vargs
                 deps (-parse-input m)
                 body (drop 3 args)]
             `(ribelo.praxis/add-node!
                ~id ~deps
                ~(if-not (seq more)
                   (if memoize?
                     (f/fail! "memoize only works for nodes that take arguments")
                     `(fn [~nid ~m]
                        ~@body))
                   `(fn [~nid ~m]
                      ~(if memoize?
                         `(ribelo.extropy/-memoize
                            (fn ~(vec more)
                              ~@body))
                         `(fn ~(vec more)
                            ~@body)))))))))))

#?(:clj
   (defmacro defevent [id [_ _ _ & more] & body]
     (let [env (meta &form)
           timeout (get env ::timeout (ex/-ms 5 :mins))]
       `(defmethod ~'ribelo.praxis/event ~id ~(into ['id] more)
          (reify ~id
            ~'ribelo.praxis/PEvent
            (~'-timeout [~'_] ~timeout)
            ~'ribelo.praxis/PNode
            ~@body)))))

#?(:clj
   (defmacro defupdate [id [e deps & more] & body]
     (with-meta
       `(defevent ~id [~e ~deps nil ~@more]
          ~'ribelo.praxis/UpdateEvent
          (ribelo.praxis/update [~e ~deps]
            ~@body))
       (meta &form))))

#?(:clj
   (defmacro defproc [id [e deps stream & _more :as vargs] & body]
     (with-meta
       `(defevent ~id ~vargs
          ~'ribelo.praxis/PorcedureEvent
          (ribelo.praxis/proc [~e ~deps ~stream]
                              ~@body))
       (meta &form))))

#?(:clj
   (defmacro defeffect [id [e deps stream & _more :as vargs] & body]
     (with-meta
       `(defevent ~id ~vargs
          ~'ribelo.praxis/EffectEvent
          (ribelo.praxis/effect [~e ~deps ~stream]
            ~@body))
       (meta &form))))

(declare node)

(def ^:private nodes_ (atom {}))
(def ^:private mbx (mi/mbx))
(def ^:private <stream (mi/dfv))
(def ^:private <reactor (mi/dfv))

(defprotocol PEvent
  (-timeout [_]))

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
       (-invoke [_ k x]
         (when-let [n (dag k)]
           (n x)))
       (-invoke [_ k x y]
         (when-let [n (dag k)]
           ((n x) y))))))

(defprotocol UpdateEvent
  (update [_ dag]))

(defprotocol PorcedureEvent
  (proc [_ dag stream]))

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

(defn event?
  ([e]
   (or (update-event? e)
       (procedure-event? e)
       (effect-event? e)
       (silent-event? e)))
  ([e id]
   (and (event? e) (ex/-kw-identical? id (-id e)))))

(defmulti event
  (fn
    ([e    ] e)
    ([e _  ] e)
    ([e _ _] e)))

(defmethod event :default [e]
  (when-not (or (silent-event? e) (and (identifiable? e) (or (ex/-kw-identical? ::listen! (-id e)) (ex/-kw-identical? ::deref (-id e)))))
    (timbre/warnf "Using default event handler for %s, returning value as is, consider adding own method!"
                  (if (identifiable? e) (-id e) e)))
  e)

(defn dispatch [& args]
  (cond
    (keyword? (ex/-first args))
    (let [<v (mi/dfv)]
      ((mi/sp
         (when (mi/? <stream)
           (mbx [<v (apply event args)])))
       #(constantly nil) (fn [err] (f/fail! (ex/-format "dispatch error! %s" (ex/-first args)) {:err err})))
      <v)
    (vector? (ex/-first args))
    (ex/-loop [e args :let [acc (transient [])]]
      (let [<v (mi/dfv)]
        ((mi/sp
           (when (mi/? <stream)
             (mbx [<v (apply event e)])))
         #(constantly nil) (fn [err] (f/fail! (ex/-format "dispatch error! %s" e) {:err err})))
        (recur (conj! acc <v)))
      (apply mi/join vector (persistent! acc)))))

(defn event-type [e]
  (cond
    (update-event? e) "UpdateEvent"
    (procedure-event? e) "PorcedureEvent"
    (effect-event? e) "EffectEvent"
    :else "UnknownEvent"))

(defmulti handle-error (fn [e _err] (-id ^PIdentifiable e)))

(defmethod handle-error :default
  [e err]
  (when-not (f/isa? err ::silent? true)
    (timbre/warn "Using default error handler, consider using your own!")
    (timbre/errorf "%s failure id: %s" (event-type e) (-id e))
    (timbre/error (ex-message err))))

(defn promise?
  "https://github.com/funcool/potok/blob/master/src/potok/core.cljs#L74"
  [x]
  #?(:clj false
     :cljs (or (instance? js/Promise x)
               (and (goog.isObject x)
                    (fn? (unchecked-get x "then"))))))

(defn publisher?
  [x]
  #?(:clj  (instance? missionary.impl.Reactor$Publisher x)
     :cljs (instance? missionary.impl.Reactor/Publisher x)))

(defn dataflow?
  [x]
  #?(:clj  (instance? missionary.impl.Dataflow x)
     :cljs (instance? missionary.impl/Dataflow x)))

(defn -reset-graph-inputs! [dag m]
  (ex/-run! (fn [[k v]] (when-let [atm_ (some-> @dag (ex/-get k) .-input)] (reset! atm_ v))) m))

(defrecord Vertex [id kind f input deps flow])

(defn- -vertex [{:keys [id kind f input deps flow]}]
  (->Vertex id kind f input deps flow))

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
    (f/fail! (ex/-format "node %s dosen't exists!" id) {:id id})))

(defn- -build-graph! []
  (loop [[[id vrtx] & more] @dag acc []]
    (if id
      (if (or (ex/-kw-identical? :atom (.-kind vrtx)) (ex/-kw-identical? :value (.-kind vrtx)))
        (recur more (conj acc (mi/signal! (.-flow ^Vertex vrtx))))
        (let [ks (.-deps ^Vertex vrtx)
              in (mapv (fn [k]
                         (if-let [x (some-> @dag (ex/-get k))]
                           (.-flow ^Vertex x)
                           (f/fail! (ex/-format "node %s dosen't exists!" k) {:id k}))) ks)]
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

(defn -process-flow [>f ms]
  (mi/sp
    (let [xs (mi/? (mi/reduce (fn [acc x] (conj acc x)) [] >f))
          dfvs (ex/-loop [x xs :let [acc (transient [])]]
                 (let [<v (mi/dfv)]
                   (cond
                     (or (event? x) (promise? x))
                     (do (mbx [<v x]) (recur (conj! acc <v)))

                     (fn? x)
                     (do (<v (-process-flow x ms)) (recur (conj! acc <v)))

                     :else
                     (do (<v x) (recur (conj! acc <v)))))
                 (persistent! acc))]
      (mi/? (mi/timeout (apply mi/join vector dfvs) ms (f/fail "timeout!"))))))

(defn- -process-update-event [<r e]
  ((mi/sp
     (when-let [<f (update e dag)]
       (if (fn? <f)
         (when-let [x (mi/? <f)]
           (if (map? x)
             (-reset-graph-inputs! dag x)
             (timbre/errorf "result of %s - UpdateEvent task should be a map!, but is %s" (-id e) (type x))))
         (timbre/errorf "result of %s - UpdateEvent should be a missionary task, but is %s" (-id e) (type <f)))))
   (fn [_] (-maybe-assign <r true)) (fn [err] (-maybe-assign <r (f/fail (ex-message err))) (handle-error e err))))

(defn- -process-procedure-event [<r e]
  ((mi/sp
     (when-let [>f (proc e dag (mi/? <stream))]
       (if (fn? >f)
         (-> (mi/? (-process-flow >f (-timeout e)))
             (f/then (fn [ok] (-maybe-assign <r (if (= 1 (ex/-count ok)) (ex/-first ok) ok))))
             (f/catch (fn [err] (-maybe-assign <r err) (handle-error e err))))
         (timbre/errorf "result of PorcedureEvent: %s should be event, missionary flow, but is: %s" (-id e) (type >f)))))
   (constantly nil) (fn [err] (-maybe-assign <r (f/fail (ex-message err) (ex-data err))) (handle-error e err))))

(defn- -process-effect-event [e]
  ((mi/sp
     (mi/? (mi/reduce (constantly nil) (effect e dag (mi/? <stream)))))
   (constantly nil) (fn [err] (handle-error e err))))

#?(:cljs
   (defn- -process-promise [<r ^js e]
     ((mi/sp
        (let [<v (mi/dfv)]
          (-> e
              (.then (fn [ok] (<v ok)))
              (.catch (fn [err] (<v (f/fail (ex-message err) (ex-data err))) (timbre/errorf "error in promise %s" err)))) ;
          (when-let [x (mi/? <v)]
            (cond
              (or (event? x) (promise? x))
              (mbx [<r x])

              (fn? x)
              (<r (mi/? (-process-flow x (ex/-ms* 5 :mins))))

              (dataflow? x)
              (<r (mi/? x))

              :else
              (<r x)))))
      (constantly nil) (constantly nil))))

(defn -process-event [<r e]
  (when (update-event? e) (-process-update-event <r e))
  (when (procedure-event? e) (-process-procedure-event <r e))
  (when (effect-event? e) (-process-effect-event e))
  #?(:cljs (when (promise? e) (-process-promise <r e))))

(defn add-node!
  ([id x]
   ((mi/sp
      (if (instance? #?(:clj clojure.lang.Atom :cljs Atom) x)
        (let [>flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch x))]
          (swap! dag assoc id (-vertex {:id id :kind :atom :flow >flow :input x})))
        (let [atm_ (atom x)
              >flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch atm_))]
          (swap! dag assoc id (-vertex {:id id :kind :value :flow >flow :input atm_}))))
      (when (mi/? <reactor) (dispatch ::build-graph!)))
    #(timbre/debugf "node added successfuly %s" id %) #(timbre/error %)))

  ([id deps f]
   ((mi/sp
      (swap! dag assoc id (-vertex {:id id :kind :fn :f f :deps deps}))
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
        (mi/stream! (mi/eduction (comp (dedupe) (map (fn [[e v]] (f e v)))) >flow))
        (f/fail! (ex/-format "node %s dosen't exists!" id) {:id id})))))

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
                                         (filter (fn [[_ e]] (not (silent-event? e))))
                                         (map (fn [[<r e]] (-process-event <r e))))))))]
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
      (tap> (mi/? node))
      (tap> (str "node '" id "' dosen't exists!")))))

(defeffect ::prn-node
  [_ dag _ id]
  (mi/ap
    (if-let [node (ex/-get* dag id)]
      (prn id (mi/? node))
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

  (defnode ::c (mi/latest vector (mi/signal! (mi/ap (loop [i 0] (mi/? (mi/sleep 1000)) (mi/amb> i (recur (inc i))))))))

  (defnode ::d
    [id {::keys [c]}]
    (* 10 c))

  (mi/? (ex/-get dag ::d))
  (mi/? (mi/sp (prn :a (mi/?< (.-flow (ex/-get @dag ::c))))))
  (mi/? (mi/sp (prn :a (mi/? (mi/reduce (comp reduced{}) nil (mi/ap (mi/amb> 1 2 3 4)))))))

  (dispatch ::prn-node ::d)

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
    (mi/sp
      (println :update-test (mi/? store))
      {::store {:a (rand-int 10) :b (rand-int 10) :c (rand-int 10)}}))

  (dispatch ::update-test)
  @dag

  (defeffect ::effect-test
    [e {::keys [store]} _]
    (mi/ap (println :effect-test (mi/? store))))

  (dispatch ::effect-test)

  (defupdate ::update-test2
    [e {::keys [store]} {:keys [x]}]
    (println :update-test2 :x x)
    {::store {:a x :b x :c x}}))
