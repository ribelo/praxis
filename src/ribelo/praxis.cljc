(ns ribelo.praxis
  (:refer-clojure :exclude [update run! reify])
  #?(:cljs (:require-macros [ribelo.praxis :refer [if-clj reify defnode defupdate deftask defflow defstream]]))
  (:require
   #?(:clj  [clojure.core :as core]
      :cljs [cljs.core :as core])
   [taoensso.timbre :as timbre]
   [missionary.core :as mi]
   [ribelo.fatum :as f]
   #?(:cljs [goog.string :refer [format]])
   #?(:cljs ["react" :as react])))

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
  "faster than [[clojure.core/memoize]], but `f` can takes only one argument,
  supports `ttl`"
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
      `('core/reify
         ~'ribelo.praxis/Event
         (~'event-id [~'_] ~id)
         ~@impls))))

#?(:clj
   (defn- -parse-input
     "parses the `map` `m` and extracts all namespaced and unnamespaced keys.
  supports both format, `{:keys [x y z]}` and `{v :k}` format"
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

[[Node]] can be a single static value, a variable that supports `add-watch`, or
  a `function`

`function` [[Node]] may have dependencies on other `nodes`. calculations are
  incremental and a [[Node]] is recalculated only when dependencies change.

  see also [[add-node!]]"
     [& args]
     (assert (> (count args) 1) "defnode take at least two argument")
     (let [env (meta &form)]
       (if (= 2 (count args))
         ;; add static node
         `(ribelo.praxis/add-node!
            ~(nth args 0) ~(nth args 1))
         ;; add dynamic node
         (let [memoize? (boolean (or (::memoize? env) (::ttl env)))
               ttl (::ttl env)
               id (nth args 0)
               vargs (nth args 1)
               [nid m & more] vargs
               deps (-parse-input m)
               body (drop 2 args)]
           (assert (or (nil? ttl) (and (number? ttl) (pos? ttl))) "ttl must be a positive number")
           (assert (= 1 (count more)) "defnode can only take one additional argument, use map if you need to pass multiple parameters")
           (let [params (first more)]
             `(ribelo.praxis/add-node!
                ~id ~deps
                ~(if-not params
                   (do (assert memoize? "memoize only works for nodes that take argument")
                       `(fn [~nid ~m]
                          ~@body))
                   (let [inner-fn `(fn [~nid ~m ~params] ~@body)]
                     `(fn [~nid ~m]
                        ~(if memoize?
                           (if ttl
                             `(-memoize ~ttl ~inner-fn)
                             `(-memoize ~inner-fn))
                           inner-fn)))))))))))

#?(:clj
   (defmacro defevent

     "declares an event method.

  the macro underneath creates an [[event]] method for the `id` returning a
  [[reify]] that supports the [[Event]] protocol.

  supports `::silent` and `::in-reactor-context` metadata, see [[SilentEvent]]
  and [[ReactorEvent]]
  "
     [id & args]
     (let [env (meta &form)
           [[_ _ _ & more] & body] (if (string? (first args)) (drop 1 args) args)]
       `(defmethod ~'ribelo.praxis/event ~id ~(into ['id] more)
          (reify ~id
            ~@(when (::in-reactor-context env)
                `(~'ribelo.praxis/ReactorEvent))
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
     (let [env (meta &form)
           [[e deps & more] & body] (if (string? (first args)) (drop 1 args) args)]
       (with-meta
         `(defevent ~id [~e ~deps nil ~@more]
            ~'ribelo.praxis/Event
            (dependencies [~'this] ~(or (::dependencies env) (-parse-input deps)))
            ~'ribelo.praxis/UpdateEvent
            (ribelo.praxis/update [~e ~deps] ~@body))
         env))))

#?(:clj
   (defmacro deftask
     "declaration `task` [[event]].

  like [[defevent]] underneath creates an [[event]] `method` that returns [[reify]]
  where the body is unrolled as the `task` function body of [[TaskEvent]]
  protocol. must return `missionary.core/Sequential`.

  see also [[TaskEvent]]
"
     [id & args]
     (let [env (meta &form)
           [[e deps & more] & body] (if (string? (first args)) (drop 1 args) args)]
       (with-meta
         `(defevent ~id [~e ~deps ~'_ ~@more]
            ~'ribelo.praxis/Event
            (dependencies [~'this] ~(or (::dependencies env) (-parse-input deps)))
            ~'ribelo.praxis/TaskEvent
            (ribelo.praxis/task [~e ~deps] ~@body))
         env))))

#?(:clj
   (defmacro defflow
     "declaration `flow` [[event]].

  like [[defevent]] creates an [[event]] `method` that returns [[reify]] where the body is
  unrolled as the `flow` function body of [[FlowEvent]] protocol.

  check [[FlowEvent]]
"
     [id & args]
     (let [env (meta &form)
           [[e deps & more] & body] (if (string? (first args)) (drop 1 args) args)]
       (with-meta
         `(defevent ~id [~e ~deps ~'_ ~@more]
            ~'ribelo.praxis/Event
            (dependencies [~'this] ~(or (::dependencies env) (-parse-input deps)))
            ~'ribelo.praxis/FlowEvent
            (ribelo.praxis/flow [~e ~deps] ~@body))
         env))))

#?(:clj
   (defmacro defstream
     "declaration `stream` [[event]].

  underneath creates an [[event]] `method` that returns [[reify]] where the body is
  unrolled as the `flow` function body of [[FlowEvent]] protocol and execution is
  performed in reactor context.

  check [[FlowEvent]], [[ReactorContext]]
"
     [id & args]
     (let [env (meta &form)
           [[e deps stream & more] & body] (if (string? (first args)) (drop 1 args) args)]
       (with-meta
         `(defevent ~id [~e ~deps ~stream ~@more]
            ~'ribelo.praxis/Event
            (dependencies [~'this] ~(or (::dependencies env) (-parse-input deps)))
            ~'ribelo.praxis/FlowEvent
            (ribelo.praxis/flow [~e ~deps ~stream] ~@body))
         env))))

(declare node)

(def ^:private nodes_ (atom {}))
(def ^:private mbx (mi/mbx))
(def ^:private <reactor (mi/dfv))

(defprotocol Event
  "basic protocol to distinguish between events and non-events"
  (dependencies [_]
    "a sequence of dependencies in the form of keys referring to the `id` of
    `nodes`")
  (event-id [_]
    "returns `event-id`, nothing more nothing less"))

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
  make sure we don't hurt ourselves by directly manipulating it

  "
     (core/reify
       clojure.lang.ILookup
       (valAt [_ k]
         (@nodes_ k))

       clojure.lang.IRef
       (core/deref [_]
         (persistent!
          (reduce
           (fn [acc [k ^Node vrtx]] (assoc! acc k (.-flow vrtx)))
           (transient {})
           @nodes_)))

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
         (persistent!
          (reduce
           (fn [acc [k ^Node node]] (assoc! acc k (.-flow node)))
           (transient {})
           @nodes_)))

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

(defprotocol TaskEvent
  "protocol for tasks to be executed in the near future. check `missionary.core/sp`"
  (task [_ m]
    "the first element is the event itself, the second is the dependency `map`
    relating to the `nodes` of the [[dag]]. must return
    `missionary.core/Sequential`. in [[ReactorContext]], `map` is replaced by
    the [[dag]] itself."))

(defprotocol FlowEvent
  "protocol for flows to be handled in the near future. check
  `missionary.core/ap`"
  (flow [_ m] [_ dag stream]
    "the first element is the event itself, the second is the dependency `map`
    relating to the `nodes` of the `dag` and the third argument is the [[event]]
    `stream`. must return `missionary.core/Ambiguous`. in [[ReactorContext]],
    `map` is replaced by the [[dag]] itself."))

(defprotocol SilentEvent
  "protocol that allows you to bypass [[event]] execution. bypassing allows you to
  handle it yourself using another [[FlowEvent]] and `filter` it in the
  [[event]] `stream`.")

(defprotocol ReactorContext
  "protocol to force [[event]] handling inside the `reactor context`. causes the
  second argument of the resolved `dependency` `map` to be replaced by the `dag`
  itself.")

(defn update-event?
  "checks if the [[event]] `e` implements [[UpdateEvent]]"
  [e]
  (satisfies? UpdateEvent e))

(defn task-event?
  "checks if the [[event]] `e` implements [[TaskEvent]]"
  [e]
  (satisfies? TaskEvent e))

(defn flow-event?
  "checks if the [[event]] `e` implements [[FlowEvent]]"
  [e]
  (satisfies? FlowEvent e))

(defn silent-event?
  "checks if the [[event]] `e` implements [[SilentEvent]]"
  [e]
  (satisfies? SilentEvent e))

(defn reactor-context-event?
  "checks if the [[event]] `e` implements [[ReactorContext]]"
  [e]
  (satisfies? ReactorContext e))

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
  #?(:clj  (satisfies? clojure.lang.IRef x)
     :cljs (satisfies? cljs.core.IWatchable x)))

(defn event?
  "checks if `e` implements the [[Event]] protocol, when `id` is also given,
  checks furthermore if the [[event-id]] is identical to the `id`.

  can be used to filter [[SilentEvent]] from the `event stream`"
  ([e]
   (satisfies? Event e))
  ([e id]
   (and (event? e) (-kw-identical? id (event-id e)))))

(defmulti event
  "declare an event, should return a [[reify]] that supports the [[Event]]
  protocol, however, any value can be added to the `stream` but will not be
  handled. you have to handle yourself. for flexibility supports up to four
  arguments, if you need more you should really consider using map"
  (fn
    ([e        ] e)
    ([e _      ] e)
    ([e _ _    ] e)
    ([e _ _ _  ] e)
    ([e _ _ _ _] e)))

(defmethod event :default [e]
  (f/thru-if e
    (fn [e] (not (or (silent-event? e) (event? e ::listen!))))
    (fn [e] (timbre/warnf "Using default event handler for %s, returning value as is, consider adding own method!"
                          (if (event? e) (event-id e) e)))))
(defn emit
  "[pure] adds an [[event]] or a sequence of events to the `event stream`.
  the [[event]] can be either a [[reify]] implements [[Event]] protocol, or a
  `keyword` identifying an [[event]] `method`, however, any value can be
  `emited` to the `stream` but will not be handled, you have to handle yourself.

  returns a `flow` containing the data produced by the individual [[reify]]
  functions, or `stream` if the event implements [[ReactorContext]]. [[reify]]
  functions are executed in the order: `update`, `task`, `flow` and results is
  returned in that order."
  [& args]
  (mi/ap
   (when (mi/? <reactor)
     (cond
       (keyword? (first args))
       (let [<v (mi/dfv)]
         (mbx [<v (apply event args)])
         (let [x (mi/? <v)]
           (if (and (fn? x) (not (-publisher? x)))
             (mi/?> x)
             x)))

       (sequential? (first args))
       (let [e (mi/?> (mi/seed args))
             <v (mi/dfv)]
         (mbx [<v (apply event e)])
         (let [x (mi/? <v)]
           (if (and (fn? x) (not (-publisher? x)))
             (mi/?> x)
             x)))

       :else
       (let [e (mi/?> (mi/seed args))
             <v (mi/dfv)]
         (mbx [<v e])
         (let [x (mi/? <v)]
           (if (and (fn? x) (not (-publisher? x)))
             (mi/?> x)
             x)))))))

(defn emit!
  "calls the [[emit]] and immediately `reduce` the returned `flow`

  returns `dataflow` containing the result of the reduction. if the result of
  the reduction contains only one element, that element is returned itself."
  [& args]
  (let [v (mi/dfv)]
    ((mi/reduce conj (apply emit args)) (fn [ok] (v ok)) (fn [err] (v err)))
    (mi/sp
     (let [x (mi/? v)]
       (or (and (= 1 (count x)) (first x)) x)))))

(defmulti handle-error
  "allows the automatic handling of errors arising as a result of protocol
  function calls"
  (fn [e _err] (when (event? e) (event-id ^Event e))))

(defmethod handle-error :default
  [_e err]
  err)

(defn -reset-graph-inputs!
  "resets the atoms holding the values of the static nodes of the dag"
  [m]
  (core/run! (fn [[k v]] (when-let [atm_ (get-in @nodes_ k :input)] (reset! atm_ v))) m)
  true)

(defn- -link!
  "creates an edge between the nodes of the graph"
  [id >fs]
  (if-let [^Node node (get @nodes_ id)]
    (mi/eduction (dedupe) (apply mi/latest (fn [& args] [id (node (into {} args))]) >fs))
    (f/fail! (format "node %s dosen't exists!" id) {:id id})))

(defn- -build-graph!
  "creates a graph based on the declared nodes and their relationships"
  []
  (loop [[[id ^Node node] & more] @nodes_ acc []]
    (if id
      (if (or (-kw-identical? :watchable (.-kind node)) (-kw-identical? :static (.-kind node)))
        (recur more (conj acc (mi/signal! (.-flow node))))
        (let [ks (:deps node)
              in (mapv (fn [k]
                         (if-let [x (@nodes_ k)]
                           (.-flow ^Node x)
                           (f/fail! (format "node %s dosen't exists!" k) {:id k}))) ks)]
          (if (every? some? in)
            (let [>f (-link! id in)]
              (swap! nodes_ assoc-in [id :flow] (mi/signal! >f))
              (recur more (conj acc >f)))
            (recur (conj (into [] more) [id node]) acc))))
      acc)))

^::in-reactor-context
(deftask ::build-graph!
  "rebuilds graph inside reactor context"
  [_ _]
  (mi/sp (-build-graph!)))

(defn -process-task-elem
  "[[TaskEvent]] can return another [[Event]], which will be added to the `event
  stream`"
  [x]
  (mi/sp
   (if (or (event? x) (promise? x))
     (mi/? (mi/reduce conj (emit x)))
     x)))

(defn -process-flow-elem
  "[[FLowEvent]] can return another [[Event]], which can be added to the
  [[Event]] `stream`"
  [x]
  (mi/ap
   (if (or (event? x) (promise? x))
     (mi/?> (emit x))
     x)))

(defn- -process-update-event
  "handles all [[UpdateEvent]] that are not [[SilentEvent]]"
  [^Event e m]
  (mi/sp
   (when-some [?task (update (event-id e) m)]
     (if (fn? ?task)
       (-> (f/attempt (mi/? ?task))
           (f/fail-if (complement map?) "UpdateEvent task should be a map" {:id (event-id e)})
           (f/then -reset-graph-inputs!)
           (f/catch (partial handle-error e))
           (f/catch (fn [err] (f/fail (ex-message err) (assoc (ex-data err) :type ::update-event :id (event-id e)))))
           (f/thru-if (every-pred f/fail? (complement (comp ::silent?))) (fn [err] (timbre/error (ex-message err) (ex-data err)))))

       (-> (f/fail "UpdateEvent result should be a missionary task" {:id (event-id e)})
           (f/catch (partial handle-error e))
           (f/thru-if f/fail? (fn [err] (timbre/error (ex-message err) (ex-data err)))))))))

(defn- -process-task-event
  "handles all [[TaskEvent]] that are not [[SilentEvent]]"
  [^Event e m]
  (mi/sp
   (when-some [?task (task e m)]
     (if (fn? ?task)
       (-> (f/attempt (mi/? (-process-task-elem (mi/? ?task))))
           (f/catch (partial handle-error e))
           (f/catch (fn [err] (f/fail (ex-message err) (assoc (ex-data err) :type ::task-event :id (event-id e)))))
           (f/thru-if (every-pred f/fail? (complement (comp ::silent?))) (fn [err] (timbre/error (ex-message err) (ex-data err)))))

       (-> (f/fail "TaskEvent result should be a missionary task" {:id (event-id e)})
           (f/catch (partial handle-error e))
           (f/thru-if f/fail? (fn [err] (timbre/error (ex-message err) (ex-data err)))))))))

(defn- -process-flow-event
  "handles all [[FlowEvent]] that are not [[SilentEvent]]"
  [^Event e m stream]
  (mi/ap
   (when-some [?flow (if (reactor-context-event? e) (flow e m stream) (flow e m))]
     (if (fn? ?flow)
       (-> (f/attempt (mi/?> (-process-flow-elem (mi/?> ?flow))))
           (f/catch (partial handle-error e))
           (f/catch (fn [err] (f/fail (ex-message err) (assoc (ex-data err)
                                                           :type (if (reactor-context-event? e) ::stream-event ::flow-event)
                                                           :id (event-id e)))))
           (f/thru-if (every-pred f/fail? (complement (comp ::silent?))) (fn [err] (timbre/error (ex-message err) (ex-data err)))))

       (-> (f/fail "FlowEvent result should be a missionary flow" {:id (event-id e)})
           (f/catch (partial handle-error e))
           (f/thru-if f/fail? (fn [err] (timbre/error (ex-message err) (ex-data err)))))))))

#?(:cljs
   (defn- -process-promise
     "in cljs event can be a promise, so handles all `js/Promise` [[event]]"
     [^js e]
     (mi/sp
      (let [<v (mi/dfv)]
        (-> e (.then (fn [ok] (<v ok))) (.catch (fn [err] (<v (f/fail (ex-message err) (ex-data err))))))
        (when-let [x (mi/? <v)]
          (-> (f/attempt (mi/? (-process-task-elem x)))
              (f/catch (fn [err] (handle-error e err)))
              (f/catch (fn [err] (f/fail (ex-message err) (assoc (ex-data err) :type ::promise-event))))
              (f/thru-if (every-pred f/fail? (complement (comp ::silent?))) (fn [err] (timbre/error (ex-message err) (ex-data err))))))))))

(defn add-node!
  "[inpure] adds a node to the graph, prefer using [[defnode]]

  `id` should be a unique `keyword`, preferably namespaced
  `deps` should be a collection of `id` referring to other nodes
  `f` should by should be a function that takes as first paramter an `id`, second
  an map with resolved dependencies, the optional third argument can be anything, if
  you need to pass more variables use a map"
  ([id x]
   ((mi/sp
     (if (-watchable? x)
       (let [>flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch x))]
         (swap! nodes_ assoc id (-node {:id id :kind :watchable :flow >flow :input x})))
       (let [atm_ (atom x)
             >flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch atm_))]
         (swap! nodes_ assoc id (-node {:id id :kind :static :flow >flow :input atm_}))))
     (when (mi/? <reactor) (emit! ::build-graph!)))
    #(timbre/debugf "node added successfuly %s" id %) #(timbre/error %)))

  ([id deps f]
   ((mi/sp
     (swap! nodes_ assoc id (-node {:id id :kind :fn :f f :deps deps}))
     (when (mi/? <reactor) (mi/? (emit! ::build-graph!))))
    #(timbre/debugf "node added successfuly %s" id %) #(timbre/error %))))

(def add-watch!
  "create `event stream` watcher. argument should be an [[reify]] that
  implements [[FlowEvent]] and [[ReactorContext]] protocols

  in fact adds the [[reify]] [[Event]] to the `event stream` as soon as the
  `graph` is built"
  (let [cache_ (volatile! #{})]
    (fn [e]
      (when-not (contains? @cache_ (event-id e))
        ((mi/sp
          (when (mi/? <reactor)
            (vswap! cache_ conj (event-id e))
            (emit! e)))
         (constantly nil) #(timbre/error %))))))

(defmethod event ::listen!
  [_ id f]
  (reify ::listen!
    ReactorContext
    FlowEvent
    (flow [_ dag _]
      (mi/ap
       (if-let [>flow (get dag id)]
         (mi/?> (mi/eduction (comp (map (fn [[e v]] (f e v)))) >flow))
         (f/fail (format "node %s dosen't exists!" id) {:id id :nodes @nodes_}))))))

(defn listen!
  "[inpure] creates a `listener` for the [[Node]] of the `dag`, every time the
  value of a [[Node]] changes the function is called.

  function `f` should take two arguments, the first is the listener `id`, the
  second is the [[Node]] value. returns a function that allows to delete a
  `listener`"
  [id f]
  (let [<v (emit! ::listen! id f)]
    (fn []
      ((mi/sp
        (when-let [>f (mi/? <v)]
          (when (or (fn? >f) (-publisher? >f))
            (>f))))
       #(timbre/debugf "successful unlisten %s" id %)
       #(timbre/errorf "unsuccessful unlisten %s %s" id %)))))

#?(:cljs
   (defn subscribe
     "creates `React/useEffect`, that creates a `listener` and calls the
`set-state!` function on every change.

returns [[reify]], which, when `deref`, returns `state`"
     ([id]
      (subscribe id -sentinel))
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

(defn -event->deps-map
  "[pure] based on the [[Event]] [[dependencies]], creates a `map` containing the current
  state for the given `nodes`"
  [e dag]
  (mi/sp
   (if (satisfies? Event e)
     (let [deps (keep (fn [k] (get dag k)) (dependencies e))
           >f (apply mi/latest (fn [& args] (into {} args)) deps)]
       (mi/? (mi/reduce (comp reduced {}) nil >f)))
     {})))

(defn -process-event
  "processes the [[Event]] and maintains the specified order of protocol
  function calls"
  [e m stream]
  (mi/ap
   (mi/amb>
    (if (update-event? e) (mi/? (-process-update-event e m)) (mi/amb>))
    (if (task-event? e) (mi/? (-process-task-event e m)) (mi/amb>))
    (if (flow-event? e) (mi/?> (-process-flow-event e m stream)) (mi/amb>))
    #?(:cljs (if (promise? e) (mi/? (-process-promise e)) (mi/amb>))))))

(defn run!
  "[inpure] starts the `missionary.core/reactor`"
  []
  ((mi/sp
    (if-not (mi/? (mi/timeout <reactor 0))
      (let [r (mi/reactor
               (let [>e (mi/stream! (mi/ap (loop [] (mi/amb> (mi/? mbx) (recur)))))]
                 (mi/stream!
                  (mi/ap
                   (let [[<v e] (mi/?= >e)]
                     (when (or (event? e) (promise? e))
                       (let [>f (partial -process-event e)]
                         (if (reactor-context-event? e)
                           (<v (mi/stream! (>f dag >e)))
                           (<v (>f (mi/? (-event->deps-map e dag)) >e))))))))))]
        (<reactor (r #(timbre/infof "succesfuly shutdown reactor" %) #(timbre/error %))))
      (timbre/warn "graph already builded!")))
   (constantly nil) #(timbre/error %)))

(defn dispose!
  "dispose reactor"
  []
  ((mi/sp
    (when-let [cb (mi/? <reactor)]
      (timbre/info "dispose!")
      (cb)
      (reset! nodes_ nil)))
   (constantly nil) #(timbre/error %)))

(defmethod event ::tap-node
  [id k]
  (reify id
    Event
    (dependencies [_] [k])
    TaskEvent
    (task [_ m]
      (mi/sp
       (if-let [[_ node] (find m k)]
         (tap> node)
         (tap> (str "node '" k "' dosen't exists!")))))))

(defmethod event ::prn-node
  [od k]
  (reify od
    Event
    (dependencies [_] [k])
    TaskEvent
    (task [_ m]
      (mi/sp
       (if-let [[_ node] (find m k)]
         (prn node)
         (prn (format "node '%s' dosen't exists!" k)))))))


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

  (defnode ::store {:a 2 :b 3 :c 1})

  (defnode ::a
    [id {::keys [store]}]
    (println :store store)
    (println :eval-node :id id :val (:a store))
    (:a store))

  (run!)

  (emit! ::prn-node ::store)
  (emit! ::prn-node ::a)

  (listen! ::a (fn [e v] (prn :listen ::a e v)))

  (deftask ::task1
    [e {::keys [store]}]
    (mi/sp e))

  (mi/? (emit! ::task1))

  (defupdate ::update-test
    [_ {::keys [store]}]
    (mi/ap
     (println :update-test store)
     {::store {:a (rand-int 10) :b (rand-int 10) :c (rand-int 10)}}))

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

  )
