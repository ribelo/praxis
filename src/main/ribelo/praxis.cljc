(ns ribelo.praxis
  (:refer-clojure :exclude [update run! reify resolve])
  #?(:cljs (:require-macros [ribelo.praxis :refer [reify defnode defupdate defeffect defstream]]))
  (:require
   #?(:clj  [clojure.core :as core]
      :cljs [cljs.core :as core])
   [taoensso.timbre :as timbre]
   [missionary.core :as mi]
   [ribelo.fatum :as f]
   #?(:cljs [goog.string :refer [format]])
   #?(:browser ["react" :as react])))

;; * utils

(def -sentinel #?(:clj (Object.) :cljs (js/Object.)))

(defn -now-udt []
  #?(:clj  (System/currentTimeMillis)
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

#?(:clj
   (defmacro reify
     "like [[clojure.core/reify]], but the first argument should be a unique
  `keyword` that will be used to identify the `event`"
     ([id & impls]
      `(let [<v# (mi/dfv)]
         (core/reify
           ~'ribelo.praxis/Event
           (event-id [~'_] ~id)
           (resolve [this# x#]
             (<v# x#)
             this#)
           (result [~'_] <v#)
           ~@impls)))))

#?(:clj
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

             :else
             (recur more (conj! acc (keyword (namespace k) (name v)))))
           (persistent! acc))))))

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

#?(:clj
   (defmacro defevent

     "declares an [[event]] method.


  the macro underneath creates an [[event]] method for the `id` returning a
  [[reify]] that supports the [[Event]] protocol.


  supports `::silent` and `::in-reactor-context` metadata, see [[SilentEvent]]
  and [[ReactorContext]]
  "
     [id & args]
     (assert (keyword? id) "id should be keyword")
     (let [env (meta &form)
           [[_ input _ & more] & body] (if (string? (first args)) (drop 1 args) args)]
       `(defmethod ~'ribelo.praxis/event ~id ~(into ['id] more)
          (reify ~id
            ~'ribelo.praxis/EventDeps
            (deps [~'this] ~(or (::deps env) (-parse-input input)))
            ~@(when (::in-reactor-context env)
                `(~'ribelo.praxis/ReactorContext))
            ~@(when (::as-stream env)
                `(~'ribelo.praxis/StreamEvent))
            ~@(when (::custom env)
                `(~'ribelo.praxis/CustomEvent))
            ~@(when (::silent env)
                `(~'ribelo.praxis/SilentEvent))
            ~@body)))))

#?(:clj
   (defmacro defupdate
     "like [[defevent]] creates an [[event]] defmethod that returns [[reify]] where the body is
  unrolled as the [[update]] function body of [[UpdateEvent]] protocol.


  see also `[[UpdateEvent]]`
  "
     [id & args]
     (assert (keyword? id) "id should be keyword")
     (let [env (meta &form)
           [[e input & more] & body] (if (string? (first args)) (drop 1 args) args)]
       `(defmethod ~'ribelo.praxis/event ~id ~(into ['id] more)
          (reify ~id
            ~'ribelo.praxis/EventDeps
            (deps [~'this] ~(or (::deps env) (-parse-input input)))
            ~@(when (::silent env)
                `(~'ribelo.praxis/SilentEvent))
            ~'ribelo.praxis/UpdateEvent
            (ribelo.praxis/update [~e ~input] ~@body))))))

#?(:clj
   (defmacro defeffect
     "declaration `effect` [[event]].


  like [[defevent]] creates an [[event]] `method` that returns [[reify]] where the body is
  unrolled as the `flow` function body of [[FlowEvent]] protocol.


  check [[FlowEvent]]"
     {:style/indent 1}
     [id & args]
     (assert (keyword? id) "id should be keyword")
     (let [env (meta &form)
           [[e input & more] & body] (if (string? (first args)) (drop 1 args) args)]
       `(defmethod ~'ribelo.praxis/event ~id ~(into ['id] more)
          (reify ~id
            ~'ribelo.praxis/EventDeps
            (deps [~'this] ~(or (::deps env) (-parse-input input)))
            ~@(when (::in-reactor-context env)
                `(~'ribelo.praxis/ReactorContext))
            ~@(when (::custom env)
                `(~'ribelo.praxis/CustomEvent))
            ~@(when (::silent env)
                `(~'ribelo.praxis/SilentEvent))
            ~'ribelo.praxis/EffectEvent
            (ribelo.praxis/effect [~e ~input] ~@body))))))

#?(:clj
   (defmacro defstream
     "declaration `stream` [[event]].


  underneath creates an [[event]] `method` that returns [[reify]] where the body is
  unrolled as the `flow` function body of [[FlowEvent]] protocol and execution is
  performed in reactor context.


  check [[FlowEvent]], [[ReactorContext]]"
     [id & args]
     (assert (keyword? id) "id should be keyword")
     (let [env (meta &form)
           [[e input stream & more] & body] (if (string? (first args)) (drop 1 args) args)]
       `(defmethod ~'ribelo.praxis/event ~id ~(into ['id] more)
          (reify ~id
            ~'ribelo.praxis/EventDeps
            (deps [~'this] ~(or (::deps env) (-parse-input input)))
            ~'ribelo.praxis/StreamEvent
            ~'ribelo.praxis/EffectEvent
            (ribelo.praxis/effect [~e ~input ~stream] ~@body))))))

(declare node)

(defonce ^:private nodes_ (atom {}))
(defonce ^:private mbx (mi/mbx))
(defonce ^:private <reactor (mi/dfv))

(defprotocol Event
  "basic protocol to distinguish between events and non-events"
  (event-id [_]
    "returns `event-id`, nothing more nothing less")
  (resolve [_ x]
    "TODO")
  (result [_]
    "TODO"))

(defprotocol EventDeps
  (deps [_]
    "a sequence of deps in the form of keys referring to the `id` of
    `nodes`"))

#?(:clj
   (defrecord Node [id kind f input deps flow]
     clojure.lang.IFn
     (invoke [_ m]
       (f id m))
     (invoke [_ s f]
       ((mi/reduce (comp reduced second {}) nil flow) s f)))

   :cljs
   (defrecord Node [id kind f input deps flow]
     IFn
     (-invoke [_ m]
       (f id m))
     (-invoke [_ s f]
       ((mi/reduce (comp reduced second {}) nil flow) s f))))

(defn- -node
  "creates [[Node]]"
  [{:keys [id kind f input deps flow]}]
  (->Node id kind f input deps flow))

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

(defprotocol UpdateEvent
  "protocol for state change of static `nodes` or those supporting `reset!`."
  (update [_ dag]
    "the first element is the event itself, the second is the dependency map
    relating to the nodes of the dag. must return `missionary.core/Sequential`.
    in [[ReactorContext]], `map` is replaced by the [[dag]] itself, but in that
    case it makes no sense."))

(defprotocol EffectEvent
  "protocol for flows to be handled in the near future. check
  `missionary.core/ap`"
  (effect [_ m] [_ dag stream]
    "the first element is the event itself, the second is the dependency `map`
    relating to the `nodes` of the `dag` and the third argument is the [[event]]
    `stream`. must return `missionary.core/Ambiguous`. in [[ReactorContext]],
    `map` is replaced by the [[dag]] itself."))

(defprotocol StreamEvent
  ;; TODO
  )

(defprotocol SilentEvent
  "protocol that allows you to mute default error handler")

(defprotocol CustomEvent
  "protocol that allows you to bypass [[event]] execution. bypassing allows you to
  handle it yourself using another [[FlowEvent]] and `filter` it in the
  `event stream`.")

(defprotocol ReactorContext
  "protocol to force [[event]] handling inside the `reactor context`. causes the
  second argument of the resolved `dependency` `map` to be replaced by the `dag`
  itself.")

(defn update-event?
  "checks if the [[event]] `e` implements [[UpdateEvent]]"
  [e]
  (satisfies? UpdateEvent e))

(defn effect-event?
  "checks if the [[event]] `e` implements [[FlowEvent]]"
  [e]
  (satisfies? EffectEvent e))

(defn silent-event?
  "checks if the [[event]] `e` implements [[SilentEvent]]"
  [e]
  (satisfies? SilentEvent e))

(defn custom-event?
  "checks if the [[event]] `e` implements [[CustomEvent]]"
  [e]
  (satisfies? CustomEvent e))

(defn reactor-context?
  "checks if the [[event]] `e` implements [[ReactorContext]]"
  [e]
  (satisfies? ReactorContext e))

(defn stream-event?
  "checks if the [[event]] `e` implements [[ReactorContext]]"
  [e]
  (satisfies? StreamEvent e))

(defn -publisher?
  "checks if `x` is `missionary.impl.Reactor$Publisher`"
  [x]
  #?(:clj  (instance? missionary.impl.Reactor$Publisher x)
     :cljs (instance? missionary.impl.Reactor/Publisher x)))

(defn promise?
  "checks if `x` is `js/Promise`, for obvious reasons, in `clj` always returns
  false


  shamelessly stolen from
  https://github.com/funcool/potok/blob/master/src/potok/core.cljs#L74"
  [x]
  #?(:clj false
     :cljs (or (instance? js/Promise x)
               (and (goog.isObject x)
                    (fn? (unchecked-get x "then"))))))

(defn -watchable?
  "checks if `x` support `add-watch`"
  [x]
  #?(:clj  (instance? clojure.lang.IRef x)
     :cljs (satisfies? cljs.core.IWatchable x)))

(defn -wrap-promise [p]
  #?(:cljs
     (let [<v (mi/dfv)]
       (reify ::promise-event
         EffectEvent
         (effect [_ _]
           (mi/ap
            (-> p (.then (fn [ok] (<v ok))) (.catch (fn [err] (<v (f/ensure-fail err)))))
            (mi/? <v)))))))

(defn event?
  "checks if `e` implements the [[Event]] protocol, when `id` is also given,
  checks furthermore if the [[event-id]] is identical to the `id`.


  can be used to filter [[SilentEvent]] from the `event stream`"
  ([e]
   (satisfies? Event e))
  ([id e]
   (and (event? e) (-kw-identical? id (event-id e)))))

(defmulti event
  "declare an event, should return a [[reify]] that supports the [[Event]]
  protocol. for flexibility supports up to four arguments, if you need more you
  should really consider using map"
  (fn
    ([e        ] e)
    ([e _      ] e)
    ([e _ _    ] e)
    ([e _ _ _  ] e)
    ([e _ _ _ _] e)))

(defmethod event :default [e]
  (if (keyword? e)
    (f/fail "event don't exists" {::e e})
    (f/fail "event must be a keyword" {::type (type e)})))

(defn -ensure-event
  [e & args]
  (cond
    (keyword? e) (apply event e args)
    (and (event? e) (empty? args)) e
    :else
    (f/fail "event must be keyword or something that implements the Event protocol" {::e e ::args args})))

(defn emit
  "[pure] adds an [[event]] or a sequence of events to the `event stream`.
  the [[event]] can be either a [[reify]] implements [[Event]] protocol, or a
  `keyword` identifying an [[event]] `method`.


  returns a `flow` containing the data produced by the individual [[reify]]
  functions, or `stream` if the event implements [[ReactorContext]]. [[reify]]
  functions are executed in the order: `update`, `task`, `flow` and results is
  returned in that order."
  [e & args]
  (mi/ap
   (cond
     (or (keyword? e) (event? e))
     (f/when-ok [e' (apply -ensure-event e args)]
       (mbx e')
       (f/when-ok [x (mi/? (result e'))]
         (if (and (fn? x) (not (-publisher? x)))
           (mi/?> x)
           x)))

     (promise? e)
     (f/when-ok [e (-wrap-promise e)]
       (mbx e)
       (f/when-ok [x (mi/? (result e))]
         (if (and (fn? x) (not (-publisher? x)))
           (mi/?> x)
           x)))

     (sequential? e)
     (let [e (mi/?> (mi/seed (into [e] args)))]
       (mi/reduce conj (apply emit e))))))

(defn emit!
  "calls the [[emit]] and immediately `reduce` the returned `flow`


  returns `dataflow` containing the result of the reduction. if the result of
  the reduction contains only one element, that element is returned itself."
  [& args]
  (let [v (mi/dfv)]
    ((mi/reduce conj (apply emit args)) (fn [ok] (v ok)) (fn [err] (v err)))
    (mi/sp
     (f/when-ok [x (mi/? v)]
       (or (and (= 1 (count x)) (first x)) x)))))

(defmulti handle-error
  "allows the automatic handling of errors arising as a result of protocol
  function calls

  a more functional approach to error handling. "
  (fn [e _err] (when (event? e) (event-id ^Event e))))

(defmethod handle-error :default
  [e err]
  (when-not (silent-event? e)
    (timbre/error (ex-message e)))
  err)

(defn -reset-graph-inputs!
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
             (let [>f (-link! id in)]
               (swap! nodes_ assoc-in [id :flow] (mi/signal! >f))
               (recur more (conj acc >f)))
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
  (mi/ap
   (mi/? (-build-graph!))))

(defstream ::watch-build-graph
  [_ _ >s]
  (mi/ap
   (let [e (mi/?> (mi/stream! (-debounce (mi/eduction (filter (partial event? ::build-graph!)) >s))))]
     (mi/?> (effect e nil)))))

(emit! ::watch-build-graph)

(defn -process-effect-elem
  "[[EffectEvent]] can return another [[Event]], which can be added to the
  [[Event]] `stream`"
  [x]
  (mi/ap
   (cond
     (event? x)
     (mi/?> (emit x))

     (promise? x)
     (mi/?> (emit (-wrap-promise x)))

     (and (fn? x) (not (-publisher? x)))
     (mi/?> x)

     :else
     x)))

(defn- -process-update-event
  "handles all [[UpdateEvent]] that are not [[SilentEvent]]"
  [e m]
  (mi/ap
   (when-some [?task (update e m)]
     (if (fn? ?task)
       (-> (f/attempt (mi/?> ?task))
           (f/fail-if (complement map?) "UpdateEvent task should be a map" {::id (event-id e)})
           (f/then -reset-graph-inputs!)
           (f/catch (partial handle-error e))
           (f/catch (fn [err] (f/fail (ex-message err) (assoc (ex-data err) ::type ::update-event ::id (event-id e)))))
           (f/thru-if (every-pred f/fail? (complement (comp ::silent?))) (fn [err] (timbre/error (ex-message err) (ex-data err)))))

       (-> (f/fail "UpdateEvent result should be a missionary task" {::id (event-id e)})
           (f/catch (partial handle-error e))
           (f/thru-if f/fail? (fn [err] (timbre/error (ex-message err) (ex-data err)))))))))

(defn- -process-effect-event
  "handles all [[EffectEvent]] that are not [[SilentEvent]]"
  [e m stream]
  (mi/ap
   (when-some [?flow (if (stream-event? e) (effect e m stream) (effect e m))]
     (if (fn? ?flow)
       (-> (f/attempt (mi/?> (-process-effect-elem (mi/?> ?flow))))
           (f/catch (partial handle-error e))
           (f/catch (fn [err] (f/fail (ex-message err) (assoc (ex-data err)
                                                              ::type (if (stream-event? e) ::stream-event ::effect-event)
                                                              ::id (event-id e)))))
           (f/thru-if (every-pred f/fail? (complement (comp ::silent?))) (fn [err] (timbre/error (ex-message err) (ex-data err)))))

       (-> (f/fail "EffectEvent result should be a missionary flow" {::id (event-id e)})
           (f/catch (partial handle-error e))
           (f/thru-if f/fail? (fn [err] (timbre/error (ex-message err) (ex-data err)))))))))

(defn -add-node!
  "[pure] adds a node to the graph, prefer using [[defnode]]


  `id` should be a unique `keyword`, preferably namespaced

  `deps` should be a collection of `id` referring to other nodes

  `f` should by should be a function that takes as first paramter an `id`, second
  an map with resolved deps, the optional third argument can be anything, if
  you need to pass more variables use a map"
  ([id x]
   (mi/ap
    (if (-watchable? x)
      (let [>flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch x))
            node (-node {:id id :kind :watchable :flow >flow :input x})]
        (swap! nodes_ assoc id node))
      (let [atm_ (atom x)
            >flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch atm_))
            node (-node {:id id :kind :static :flow >flow :input atm_})]
        (swap! nodes_ assoc id node)))
    (mi/?> (emit ::build-graph!))))

  ([id deps f]
   (mi/ap
    (let [node (-node {:id id :kind :fn :f f :deps deps})]
      (swap! nodes_ assoc id node)
      (mi/?> (emit ::build-graph!))))))

^::in-reactor-context
(defeffect ::add-node!
  [_ _ {:keys [id value deps f] :as node}]
  (mi/ap
   (cond
     (and (keyword? id) (vector? deps) (fn? f)) (-add-node! id deps f)
     (keyword? id) (-add-node! id value)
     :else
     (f/fail! "bad node format" {::node node}))))

(def -add-watch!
  "create `event stream` watcher. argument should be an [[reify]] that
  implements [[EffectEvent]] and [[ReactorContext]] protocols.


  in fact adds the [[reify]] [[Event]] to the `event stream` as soon as the
  `graph` is built, but cache ensures that each event is only added to the
  stream once"
  (let [cache_ (volatile! #{})]
    (fn [& args]
      (mi/ap
       (when (contains? @cache_ args)
         (vswap! cache_ conj args)
         (mi/?> (apply emit args)))))))

^::in-reactor-context
(defeffect ::once
  [_ dag e & args]
  (mi/ap (mi/?> (apply -add-watch! e args))))

(defn -listen!
  "[inpure] creates a `listener` for the [[Node]] of the `dag`, every time the
  value of a [[Node]] changes the function is called.


  function `f` should take two arguments, the first is the listener `id`, the
  second is the [[Node]] value. returns a function that allows to delete a
  `listener`


  use as event
  ```clojure
  (emit ::listen! id f)
  ```"
  [id dag f]
  (mi/ap
   (if-let [>flow (get-in dag [id :flow])]
     (mi/?> (mi/eduction (comp (map (fn [[e v]] (f e v)))) >flow))
     (f/fail (format "node %s dosen't exists!" id) {::id id ::nodes @nodes_}))))

(defstream ::listen!
  "see [[-listen!]]"
  [_ dag _ id f]
  (-listen! id dag f))

(defn -event->deps-map
  "[pure] based on the [[Event]] [[deps]], creates a `map` containing the
  current state for the given `nodes`"
  [e dag]
  (mi/sp
   (if (and (event? e) (satisfies? EventDeps e))
     (let [ks (keep (fn [k] (get-in dag [k :flow])) (deps e))
           >f (apply mi/latest (fn [& args] (into {} args)) (cond-> ks (keyword? ks) vector))]
       (mi/? (mi/reduce (comp reduced {}) nil >f)))
     {})))

(defn -process-event
  "[pure] processes the [[Event]] and maintains the specified order of protocol
  function calls"
  [e m stream]
  (mi/ap
   (mi/amb>
    (if (update-event? e) (mi/?> (-process-update-event e m)) (mi/amb>))
    (if (effect-event? e) (mi/?> (-process-effect-event e m stream)) (mi/amb>)))))

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
                     (when (and (event? e) (not (custom-event? e)))
                       (let [>f (partial -process-event e)]
                         (cond
                           (reactor-context? e)
                           (resolve e (mi/? (mi/reduce conj (>f dag >e))))
                           (stream-event? e)
                           (resolve e (mi/stream! (>f dag >e)))
                           :else
                           (resolve e (>f (mi/? (-event->deps-map e dag)) >e))))))))))]
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

(defmethod event ::reset!
  ([id x]
   (reify id
     EventDeps
     (deps [_] (keys x))
     UpdateEvent
     (update [_ m]
       (mi/ap
        (persistent!
         (reduce-kv
          (fn [acc k v]
            (if (find m k)
              (assoc! acc k v)
              (f/fail "node dosen't exists" {::id k})))
          (transient {})
          x))))))

  ([id k x]
   (reify id
     EventDeps
     (deps [_] [k])
     UpdateEvent
     (update [_ m]
       (mi/ap
        (if (find m k)
          {k x}
          (f/fail "node dosen't exists" {::id k})))))))

(defmethod event ::swap!
  [id k f]
  (reify id
    EventDeps
    (deps [_] [k])
    UpdateEvent
    (update [_ m]
      (mi/ap
       (if-let [[_ v] (find m k)]
         {k (f v)}
         (-add-node! k (f nil)))))))

(defstream ::after
  [_ _ >s k & args]
  (mi/ap
   (let [_ (mi/?> (mi/eduction (filter (partial event? k)) (take 1) >s))]
     (mi/?> (apply emit args)))))

(defstream ::always-after
  [_ _ >s k x]
  (mi/ap
   (let [_ (mi/?> (mi/eduction (filter (partial event? k)) >s))]
     (mi/?> (emit x)))))

(defmethod event ::node
  ([id k]
   (reify id
     EventDeps
     (deps [_] [k])
     EffectEvent
     (effect [_ m]
       (mi/ap
        (if-let [[_ node] (find m k)]
          node
          (f/fail "node dosen't exists" {:id id}))))))
  ([id k x]
   (reify id
     EventDeps
     (deps [_] [k])
     EffectEvent
     (effect [_ m]
       (mi/ap
        (if-let [[_ node] (find m k)]
          (node x)
          (f/fail "node dosen't exists" {:id id})))))))

(defmethod event ::tap-node
  ([id k]
   (reify id
     EventDeps
     (deps [_] [k])
     EffectEvent
     (effect [_ m]
       (mi/ap
        (if-let [[_ node] (find m k)]
          (tap> node)
          (tap> (str "node '" k "' dosen't exists!")))))))
  ([id k x]
   (reify id
     EventDeps
     (deps [_] [k])
     EffectEvent
     (effect [_ m]
       (mi/ap
        (if-let [[_ node] (find m k)]
          (tap> (node x))
          (tap> (str "node '" k "' dosen't exists!"))))))))

(defmethod event ::prn-node
  [id k]
  (reify id
    EventDeps
    (deps [_] k)
    EffectEvent
    (effect [_ m]
      (mi/ap
       (if-let [[_ node] (find m k)]
         (prn node)
         (prn (format "node '%s' dosen't exists!" k)))))))

;; -----------------------------------------

(comment

  (defnode ::store {:a 2 :b 3 :c 1})

  (defnode ::a
    [id {::keys [store]}]
    (println :store store)
    (println :eval-node :id id :val (:a store))
    (:a store))

  (run!)

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
       (prn :xxx x)
       (try (mi/? (mi/sleep dur x))
            (catch Throwable _
              (mi/amb>))))))

  (def atm2_ (atom nil))
  (box (debounce 1000 (mi/ap (prn :atm2 (mi/?> (mi/signal! (mi/watch atm2_)))))))
  (reset! atm2_ (rand-int 100)))
