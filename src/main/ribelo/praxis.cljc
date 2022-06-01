(ns ribelo.praxis
  (:refer-clojure :exclude [update run! reify resolve])
  #?(:cljs (:require-macros [ribelo.praxis :refer [defnode defeffect defstream emit]]))
  (:require
   [missionary.core :as mi]
   #?(:clj [clojure.core :as core]
      :cljs [cljs.core :as core])
   #?(:cljs [goog.string :refer [format]])
   [ribelo.fatum :as f]
   [taoensso.timbre :as timbre]))

;; * utils
(def -sentinel #?(:clj (Object.) :cljs (js/Object.)))

(defn -now-udt []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn -kw-identical? [x y]
  #?(:clj
     (identical? x y)
     :cljs
     (keyword-identical? x y)))

(deftype CacheEntry [udt v])

(defn -memoize
  "faster than [[clojure.core/memoize]], but `f` can takes only one argument and
  support `ttl`"
  ([f]
   (let [cache_ (volatile! {})]
     (fn
       ([x]
        (if-some [ov (@cache_ x)]
          (if (identical? ov -sentinel) nil ov)
          (if-some [v (f x)]
            (do (vswap! cache_ assoc x v) v)
            (do (vswap! cache_ assoc x -sentinel) nil)))))))
  ([ttl-ms f]
   (assert (pos? ttl-ms))
   (let [cache_ (volatile! {})]
     (fn [x]
       (let [instant (-now-udt)]
         (if-some [?e (@cache_ x)]
           (if (> (- instant (.-udt ^CacheEntry ?e)) ttl-ms)
             (let [v (f x)]
               (vswap! cache_ assoc x (CacheEntry. instant v))
               v)
             (.-v ^CacheEntry ?e))
           (let [v (f x)]
             (vswap! cache_ assoc x (CacheEntry. instant v))
             v)))))))

;; * macros

(defn- -parse-input
  "parses the `map` `m` and extracts all namespaced and unnamespaced keys.
  supports both format, `{::keys [x y z]}` and `{v :k}` format"
  [m]
  (when (map? m)
    (loop [[[k v] & more] m acc (transient [])]
      (if (some? k)
        (cond
          (= "keys" (name k))
          (recur more (reduce conj! acc (mapv (comp (partial keyword (namespace k)) name) v)))

          (= "as" (name k))
          (recur more acc)

          :else
          (recur more (conj! acc (keyword (namespace k) (name v)))))
        (persistent! acc)))))

#?(:clj
   (defmacro defnode

     "defines the computational graph [[Node]].


  [[Node]] can be a single static value, a variable that supports `add-watch`,
  or a `function`


  `function` [[Node]] may have dependencies on other `nodes`. calculations are
  incremental and a [[Node]] is recalculated only when dependencies change.


  see also [[-add-node!]]"
     [id & args]
     (assert (keyword? id) "id should be keyword")
     (let [env (meta &form)]
       (cond
         (zero? (count args))
         `(emit! ::add-node! {:id ~id :value nil})

         (= 1 (count args))
         ;; add static node
         `(emit! ::add-node! {:id ~id :value ~(nth args 0)})

         :else
         ;; add dynamic node
         (let [memoize? (boolean (or (::memoize? env) (::ttl env)))
               ttl (::ttl env)
               vargs (nth args 0)
               [nid m & more] vargs
               deps (-parse-input m)
               body (drop 1 args)]
           (assert (or (nil? ttl) (and (number? ttl) (pos? ttl))) "ttl must be a positive number")
           (assert (<= (count more) 1) "defnode can only take one additional argument, use map if you need to pass multiple parameters")
           (let [params (first more)]
             `(emit! ::add-node!
                     {:id ~id
                      :deps ~deps
                      :f ~(if-not params
                            (do (assert (not memoize?) "memoize only works for nodes that take aditional argument")
                                `(fn [~nid ~m]
                                   ~@body))
                            (let [inner-fn `(fn [~params] ~@body)]
                              `(fn [~nid ~m]
                                 ~(if memoize?
                                    (if ttl
                                      `(-memoize ~ttl ~inner-fn)
                                      `(-memoize ~inner-fn))
                                    inner-fn))))})))))))

(defrecord Event [id deps silent? custom? reactor-context? stream? result f])

#?(:clj
   (defmacro defevent
     "declares an [[event]] method.


  the macro underneath creates an [[event]] method for the `id` returning a
  [[Event]] record.

  supports `::silent` and `::in-reactor-context` metadata, see [[SilentEvent]]
  and [[ReactorContext]].
  "
     [id & args]
     (assert (keyword? id) "id should be keyword")
     (let [env (meta &form)
           [[_ input _ :as args] & body] (if (string? (first args)) (drop 1 args) args)]
       `(defmethod ~'ribelo.praxis/event ~id ~['id]
          (->Event
           ~id
           ~(or (::deps env) (-parse-input input))
           ~(::silent env)
           ~(::custom env)
           ~(::in-reactor-context env)
           ~(::stream env)
           (mi/dfv)
           (fn
             (~(vec args) ~@body)))))))

#?(:clj
   (defmacro defeffect
     "like [[defevent]] creates an [[event]] method."
     [id & args]
     (assert (keyword? id) "id should be keyword")
     (let [env (meta &form)
           [[e input & more] & body] (if (string? (first args)) (drop 1 args) args)]
       (with-meta
         `(defevent ~id [~e ~input ~@more] ~@body)
         env))))

#?(:clj
   (defmacro defstream
     "like [[defevent]] creates an [[event]] method."
     [id & args]
     (assert (keyword? id) "id should be keyword")
     (let [env (meta &form)
           [[e input stream & more] & body] (if (string? (first args)) (drop 1 args) args)]
       (with-meta
         `(defevent ~id [~e ~input ~stream ~@more] ~@body)
         (assoc env ::stream true)))))

(declare node)

(def ^:private nodes_ (atom {}))
(def ^:private mbx (mi/mbx))
(def ^:private <reactor (mi/dfv))

(deftype Listener [publisher f])

#?(:clj
   (defrecord Node [id kind f input deps flow listeners]
     clojure.lang.IFn
     (invoke [_ m]
       (f id m))
     (invoke [_ s f]
       ((mi/reduce (comp reduced second {}) nil flow) s f))

     clojure.lang.IAtom
     (reset [_ newval]
       (if (instance? clojure.lang.Atom input)
         (reset! input newval)
         (f/fail! "can't reset node" {:id id}))))

   :cljs
   (defrecord Node [id kind f input deps flow listeners]
     IFn
     (-invoke [_ m]
       (f id m))
     (-invoke [_ s f]
       ((mi/reduce (comp reduced second {}) nil flow) s f))
     IReset
     (-reset! [_ newval]
       (if (instance? Atom input)
         (reset! input newval)
         (f/fail! "can't reset node" {:id id :type (type input)})))))

(defn- -node
  "creates [[Node]]"
  [{:keys [id kind f input deps flow]}]
  (->Node id kind f input deps flow (atom [])))

#?(:clj
   (def dag
     "the whole body of the `graph`. it is a proxy for the private [[nodes_]] atom to
  make sure we don't hurt ourselves by directly manipulating it"
     (core/reify
       clojure.lang.ILookup
       (valAt [_ k]
         (@nodes_ k))

       clojure.lang.IRef
       (core/deref [_]
         @nodes_)

       clojure.lang.IFn
       (invoke [_ k]
         (when-let [node (@nodes_ k)]
           ((.-f node))))
       (invoke [_ k x]
         (when-let [node (@nodes_ k)]
           ((.-f node) x)))
       (invoke [_ k x y]
         (when-let [node (@nodes_ k)]
           (((.-f node) x) y)))))

   :cljs
   (def dag
     (core/reify
       ILookup
       (-lookup [_ k]
         (@nodes_ k))

       IDeref
       (-deref [_]
         @nodes_)

       IFn
       (-invoke [_ k]
         (when-let [node (@nodes_ k)]
           ((.-f node))))
       (-invoke [_ k x]
         (when-let [node (@nodes_ k)]
           ((.-f node) x)))
       (-invoke [_ k x y]
         (when-let [node (@nodes_ k)]
           (((.-f node) x) y))))))

(defn -watchable?
  "checks if `x` supports `add-watch`"
  [x]
  #?(:clj (instance? clojure.lang.IRef x)
     :cljs (satisfies? cljs.core.IWatchable x)))

(defn event?
  "checks if `e` is instance of [[Event]] record, when `id` is also given,
  checks furthermore if the [[event-id]] is identical to the `id`.


  can be used to filter [[SilentEvent]] from the `event stream`"
  ([e]
   (instance? Event e))
  ([id e]
   (and (event? e) (-kw-identical? id (:id e)))))

(defmulti event
  "declare an event, should return a [[Event]] reocrd. for flexibility supports
  up to four arguments, if you need more you should really consider using a map."
  (fn [e] e))

(defmethod event :default [e]
  (if (keyword? e)
    (f/fail "event don't exists" {::id e})
    (f/fail "event must be a keyword" {::type (type e)})))

(defn -emit
  [e & more]
  (cond
    (keyword? e)
    (f/when-ok [e' (assoc (event e) :args more)]
      (mbx e')
      (:result e'))

    (event? e)
    (let [e' (assoc e :args more)]
      (mbx (assoc e' :args more))
      (:result e'))

    (vector? e)
    (mi/sp
     (->> (mi/ap
           (let [x (mi/?= (mi/seed (into [e] more)))]
             (mi/? (mi/? (apply -emit x)))))
          (mi/reduce conj)))))

(defn emit
  "[pure] add an [[event]] or a sequence of events to the `event stream`.
  the [[event]] can be either a [[Event]] record, or a
  `keyword` identifying an [[event]] `method`.

  returns the value returned by the event, or `stream` if the event
  implements [[ReactorContext]]."
  [e & args]
  (mi/sp
   (f/if-ok [<v (mi/? (apply -emit e args))]
     (mi/? <v)
     (let [<r (mi/dfv)] (<r <v)))))

(defn emit!
  "calls the [[emit]] and immediately execute the returned `task`. returns
  `dataflow` containing the result of the reduction."
  [e & args]
  (let [<r (mi/dfv)
        <v (apply emit e args)]
    ((mi/sp (mi/? <v)) <r <r)
    <r))

(defn emit>
  "calls the [[emit]] and immediately `reduce` the returned `flow`.
  returns `dataflow` containing the result of the reduction."
  [e & args]
  (let [<r (mi/dfv)
        <v (apply emit e args)]
    ((mi/sp (mi/? (mi/reduce conj <v))) <r <r)
    <r))

(defmulti handle-error
  "allows automatic handling of errors arising as a result of [[Event]]
  function calls


  a more functional approach to error handling. "
  (fn [e _err] (when (event? e) (:id e))))

(defmethod handle-error :default
  [e err]
  (when-not (:silent? e)
    (timbre/error (ex-message e)))
  err)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn reset-graph-inputs!
  "resets the atoms holding the values of the static nodes of the dag"
  [m]
  (persistent!
   (reduce-kv
    (fn [acc k v]
      (let [atm_ (get-in @nodes_ [k :input])]
        (reset! atm_ v)
        (assoc! acc k @atm_)))
    (transient {})
    m)))

(defn -listen
  "[pure] creates a `listener` for the [[Node]] of the `dag`, every time the
  value of a [[Node]] changes the function is called.


  function `f` should take two arguments, the first is the listener `id`, the
  second is the [[Node]] value. returns a function that allows to delete a
  `listener`


  use as event
  ```clojure
  (emit ::listen! id f)
  ```"
  [>flow f]
  (mi/ap
   (mi/?> (mi/eduction (comp (map (fn [[e v]] (f e v)))) >flow))))

(defn- -link!
  "creates an edge between the nodes of the graph"
  [id >fs]
  (if-let [^Node node (get @nodes_ id)]
    (mi/eduction (dedupe) (apply mi/latest (fn [& args] [id (node (into {} args))]) >fs))
    (f/fail! (format "node %s dosen't exists!" id) {::id id})))

(defn- -build-graph!
  "creates a graph based on the declared nodes and their relationships"
  []
  (mi/sp
   (loop [[[id ^Node node] & more] @nodes_ acc []]
     (if id
       (if (or (-kw-identical? :watchable (.-kind node)) (-kw-identical? :static (.-kind node)))
         (recur more (conj acc (mi/signal! (.-flow node))))
         (let [ks (.-deps node)
               in (mapv (fn [k]
                          (if-let [x (@nodes_ k)]
                            (.-flow ^Node x)
                            (f/fail! (format "node %s dosen't exists!" k) {::id k}))) ks)]
           (if (every? some? in)
             (let [>flow (-link! id in)
                   listeners_ (.-listeners node)]
               (swap! nodes_ assoc-in [id :flow] (mi/signal! >flow))
               (loop [[^Listener lstn & more] @listeners_ acc (transient [])]
                 (if lstn
                   (let [pub (.-publisher lstn)
                         f (.-f lstn)]
                     ;; unlisten
                     (when pub (pub))
                     (recur more (conj! acc (Listener. (mi/stream! (-listen >flow f)) f))))
                   (reset! listeners_ (persistent! acc))))

               (recur more (conj acc >flow)))
             (recur (conj (into [] more) [id node]) acc))))
       acc))))

(defn -debounce [>f]
  (mi/ap
   (let [x (mi/?< >f)]
     (try (mi/? (mi/sleep 100 x))
          (catch missionary.Cancelled _e
            (mi/amb>))))))

^::custom
(defeffect ::build-graph!
  "rebuilds graph inside reactor context"
  [_ _]
  (mi/sp
   (mi/? (-build-graph!))))

(defstream ::watch-build-graph
  [_ _ >s]
  (mi/ap
   (let [e (mi/?> (mi/stream! (-debounce (mi/eduction (filter (partial event? ::build-graph!)) >s))))
         <v (:result e)]
     (<v ((:f e) e nil)))))

(defn -add-node!
  "[pure] adds a node to the graph, prefer using [[defnode]]


  `id` should be a unique `keyword`, preferably namespaced

  `deps` should be a collection of `id` referring to other nodes

  `f` should by should be a function that takes as first paramter an `id`, second
  an map with resolved deps, the optional third argument can be anything, if
  you need to pass more variables use a map"
  ([id x]
   (mi/sp
    (if (-watchable? x)
      (let [>flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch x))
            node (-node {:id id :kind :watchable :flow >flow :input x})]
        (swap! nodes_ assoc id node))
      (let [atm_ (atom x)
            >flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch atm_))
            node (-node {:id id :kind :static :flow >flow :input atm_})]
        (swap! nodes_ assoc id node)))
    (mi/? (emit ::build-graph!))))

  ([id deps f]
   (mi/sp
    (let [node (-node {:id id :kind :fn :f f :deps deps})]
      (swap! nodes_ assoc id node)
      (mi/? (emit ::build-graph!))))))

^::in-reactor-context
(defeffect ::add-node!
  [_ _ {:keys [id value deps f] :as node}]
  (mi/sp
   (cond
     (and (keyword? id) (fn? f))
     (mi/? (-add-node! id deps f))

     (keyword? id)
     (mi/? (-add-node! id value))
     :else
     (f/fail! "bad node format" {::node node}))))

(def -add-watch!
  "create `event stream` watcher. argument should be an [[Event]] record.


  in fact adds the [[Event]] to the `event stream` as soon as the
  `graph` is built, but cache ensures that each event is only added to the
  stream once"
  (let [cache_ (volatile! #{})]
    (fn [& args]
      (mi/sp
       (when (contains? @cache_ args)
         (vswap! cache_ conj args)
         (mi/? (mi/? (apply -emit args))))))))

^::in-reactor-context
(defeffect ::once
  [_ dag e & args]
  (mi/sp (mi/? (apply -add-watch! e args))))

^::in-reactor-context
(defeffect ::listen!
  "see [[-listen!]]"
  [_ dag id f]
  (mi/sp
   (if-let [node (get dag id)]
     (let [>flow (:flow node)
           listeners_ (.-listeners node)]
       (if >flow
         (let [lstn (Listener. (mi/stream! (-listen >flow f)) f)]
           (swap! listeners_ conj lstn))
         (let [lstn (Listener. nil f)]
           (swap! listeners_ conj lstn)
           (mi/? (emit ::build-graph!))))))))
(defn- -resolve-deps
  "[pure] based on the [[Event]] [[deps]], creates a `map` containing the
  current state for the given `nodes`"
  [dag deps]
  (mi/sp
   (if (seq deps)
     (let [ks (keep (fn [k] (get-in dag [k :flow])) deps)
           >f (apply mi/latest (fn [& args] (into {} args)) (cond-> ks (keyword? ks) vector))]
       (mi/? (mi/reduce (comp reduced {}) nil >f)))
     {})))

(defn run!
  "[inpure] starts the `missionary.core/reactor`"
  []
  ((mi/sp
    (if-not (mi/? (mi/timeout <reactor 0))
      (let [r (mi/reactor
               (let [>e (mi/stream! (mi/ap (loop [] (mi/amb> (mi/? mbx) (recur)))))]
                 (mi/stream!
                  (mi/ap
                   (let [e (mi/?= >e)]
                     (when (and (event? e) (not (:custom? e)))
                       (let [f (:f e)
                             args (:args e)
                             <v (:result e)
                             <r (mi/dfv)
                             effect (cond
                                      (:stream? e)
                                      (f/attempt (apply f e dag >e args))
                                      (:reactor-context? e)
                                      (f/attempt (apply f e dag args))
                                      :else
                                      (f/attempt (apply f e (mi/? (-resolve-deps dag (:deps e))) args)))]
                         (if (f/ok? effect)
                           (cond
                             (:reactor-context? e)
                             (do (<r (mi/? effect)) (<v <r))
                             (:stream? e)
                             (do (<r (mi/stream! effect)) (<v <r))
                             :else
                             (<v effect))
                           (do (<r (f/ensure-fail effect)) (<v <r))))))))))]
        (emit! ::watch-build-graph)
        (<reactor (r #(timbre/infof "succesfuly shutdown reactor" %) #(timbre/error %))))
      (timbre/warn "graph already builded!")))
   (constantly nil) #(timbre/error %)))

(defn dispose!
  "[inpure] dispose reactor"
  []
  ((mi/sp
    (when-let [cb (mi/? <reactor)]
      (cb)
      (reset! nodes_ nil)))
   (fn [_] (timbre/info "reactor disposed!")) #(timbre/error %)))

^::in-reactor-context
(defeffect ::reset!
  [_ dag & [x v]]
  (mi/sp
   (f/attempt
    (cond
      (map? x)
      (persistent!
       (reduce-kv
        (fn [acc k v]
          (if-let [node (get dag k)]
            (assoc! acc k (reset! node v))
            (f/fail "node dosen't exists" {::id k})))
        (transient {})
        x))
      (keyword? x)
      (if-let [node (get dag x)]
        {x (reset! node v)}
        (f/fail "node dosen't exists" {::id x}))))))

^::in-reactor-context
(defeffect ::swap!
  [_ dag & [k f & args]]
  (mi/sp
   (f/attempt
    (if-let [node (get dag k)]
      (let [newval (if (seq args) (apply f (mi/? node) args) (f (mi/? node)))]
        {k (reset! node newval)})
      (f/fail "node dosen't exists" {::id k})))))

(defstream ::after
  [_ _ >s k & args]
  (mi/ap
   (let [_ (mi/?> (mi/eduction (filter (partial event? k)) (take 1) >s))]
     (mi/? (mi/sleep 0)) ; switch
     (mi/? (mi/? (apply -emit args))))))

(defstream ::after>
  [_ _ >s k & args]
  (mi/ap
   (let [_ (mi/?> (mi/eduction (filter (partial event? k)) (take 1) >s))]
     (mi/? (mi/sleep 0)) ; swtich
     (mi/?> (mi/? (apply -emit args))))))

^::in-reactor-context
(defeffect ::node
  [_ dag id]
  (mi/sp
   (if-let [node (get dag id)]
     (mi/? node)
     (prn (format "node '%s' dosen't exists!" id)))))

^::in-reactor-context
(defeffect ::tap-node
  [_ dag id]
  (mi/sp
   (if-let [node (get dag id)]
     (tap> (mi/? node))
     (prn (format "node '%s' dosen't exists!" id)))))

^::in-reactor-context
(defeffect ::prn-node
  [_ dag id]
  (mi/sp
   (if-let [node (get dag id)]
     (node prn prn)
     (prn (format "node '%s' dosen't exists!" id)))))

;; -----------------------------------------

(comment

  (defnode ::store {:a 2 :b 3 :c 1})

  (defnode ::a
    [id {::keys [store]}]
    (println :store store)
    (println :eval-node :id id :val (:a store))
    (:a store))

  (do (run!)
      (mi/? (mi/sleep 10))
      (emit! ::listen! ::a (fn [e v] (prn :listen ::a e v))))
  (mi/? (emit ::prn-node ::a))

  (defeffect ::a
    [e dag]
    (mi/sp
     (prn :befor ::a)
     (mi/? (mi/sleep 10))
     (prn :after ::a)
     ::a))

  (emit! ::reset! {::store {:a (rand-int 10) :b (rand-int 10) :c (rand-int 10)}})

  (println @nodes_)
  (event ::listen!)
  (def unlisten (mi/? (emit! ::listen! ::a (fn [e v] (prn :listen ::a e v)))))

  (defupdate ::update-test
    [_ {::keys [store]}]
    (mi/ap
     (println :update-test store)
     {::store {:a (rand-int 10) :b (rand-int 10) :c (rand-int 10)}}))

  (mi/? (emit! ::update-test))
  (unlisten)

  (emit! ::prn-node ::store)

  (emit! ::prn-node ::a)

  (deftask ::task1
    [e {::keys [store]}]
    (mi/sp (+ 1 1)))

  (emit-task ::proc1)

  (emit-task ::update-test)

  (::store dag)
  (defeffect ::effect1
    [e {::keys [b]} stream]
    (mi/ap (prn :b b)))
  (ex/-keep pos? nil)
  (emit-task ::effect1)

  (defupdate ::update-test
    [e {::keys [store]}]
    (mi/ap
     (println :update-test (mi/? store))
     {::store {:a (rand-int 10) :b (rand-int 10) :c (rand-int 10)}}))

  (defnode ::b
    [id {::keys [store]}]
    (println :eval-node :id id :val (:b store))
    (:b store))

  @dag

  (mi/? (mi/reduce conj (emit-task :a)))

  (emit-task ::proc1)

  (def box (mi/mbx))
  ((mi/reduce (constantly nil)
              (mi/ap
               (loop []
                 (mi/amb>
                  (let [v (mi/? box)]
                    (mi/!)
                    (when-not (= :close! v)
                      (prn :box v)
                      (recur)))))))
   (constantly nil) (constantly nil))

  (def box (mi/mbx))

  ((mi/reactor
    (let [<stream (mi/ap (loop [] (mi/amb> (mi/? box) (recur))))]
      (mi/stream!
       (mi/ap
        (let [[<v flow] (mi/?> <stream)
              ! (mi/? <v)]
          (mi/? (mi/reduce (constantly nil) (mi/ap (! (mi/?> (mi/buffer 10 (mi/relieve {} flow))))))))))))
   #(prn :ok %) #(prn :err %))

  ((mi/reduce (constantly nil) (dispatch (mi/ap (mi/amb> 1 2 3 4 5 6 7 8 9 10)))) (constantly nil) (constantly nil))

  @dag
  (defeffect ::effect-test
    [e {::keys [store]} _]
    (mi/ap 1))

  (def <v (mi/dfv))

  (mi/observe)

  (mi/? (mi/reduce conj (dispatch ::effect-test)))

  (dispatch ::effect-test)

  (defupdate ::update-test2
    [e {::keys [store]} {:keys [x]}]
    (println :update-test2 :x x)
    {::store {:a x :b x :c x}})

  (defmethod event :commit
    [event-id id]
    (reify event-id
      UpdateEvent
      (update [_ _]
        id)))

  (def xs (vec (range 1e6)))

  (ex/-qb 1e1
          (ex/-reduce + xs)
          (reduce + xs)
          (mi/? (mi/reduce + (mi/seed xs))))

  (update (event :commit :bla) nil)

  (def box (mi/mbx))

  ((mi/reactor
    (let [>e (mi/stream! (mi/ap (loop [] (mi/amb> (mi/? box) (recur)))))]
      (mi/stream!
       (mi/ap
        (let [>f (mi/?> >e)]
          (mi/stream! >f))))))
   #(prn :ok %) #(prn :err %))

  (def atm1_ (atom nil))

  (box (mi/ap (prn :atm1 (mi/?> (mi/signal! (mi/watch atm1_))))))
  (reset! atm1_ (rand-int 100))

  (defn debounce [dur >in]
    (mi/ap
     (let [x (mi/?< >in)]
       (try (mi/? (mi/sleep dur x))
            (catch Throwable _
              (mi/amb>))))))

  (def atm2_ (atom nil))
  (box (debounce 1000 (mi/ap (prn :atm2 (mi/?> (mi/signal! (mi/watch atm2_)))))))
  (reset! atm2_ (rand-int 100)))
