(ns ribelo.praxis
  (:refer-clojure :exclude [update run! reify])
  #?(:cljs (:require-macros [ribelo.praxis :refer [reify defnode defupdate defwatch defeffect]]))
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
        ~'ribelo.praxis/IIdentifiable
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
   (defmacro defevent [id [_e deps _ & more] & body]
     `(defmethod ~'ribelo.praxis/event ~id ~(into ['id] more)
        (reify ~id
          ~'ribelo.praxis/INode
          (-dependencies [~'_] ~(-parse-input deps))
          ~@body))))

#?(:clj
   (defmacro defupdate [id [e deps & more] & body]
     `(defevent ~id [~e ~deps nil ~@more]
        ~'ribelo.praxis/UpdateEvent
        (ribelo.praxis/update [~e ~deps]
          ~@body))))

#?(:clj
   (defmacro defwatch [id [e deps stream & _more :as vargs] & body]
     `(defevent ~id ~vargs
        ~'ribelo.praxis/WatchEvent
        (ribelo.praxis/watch [~e ~deps ~stream]
                             ~@body))))

#?(:clj
   (defmacro defeffect [name' [e deps stream & _more :as vargs] & body]
     `(defevent ~name' ~vargs
        ~'ribelo.praxis/EffectEvent
        (ribelo.praxis/effect [~e ~deps ~stream]
                              ~@body))))

;;

(declare get-node)

(defonce ^:private nodes_ (atom {}))
(defonce ^:private mbx (mi/mbx))
(defonce ^:private stream_ (volatile! nil))
(defonce ^:private reactor_ (volatile! nil))

(defprotocol IIdentifiable
  (-id [_]))

(defn identifiable? [e]
  (satisfies? IIdentifiable e))

(defprotocol INode
  (-dependencies [_]))

#?(:clj
   (def dag
     (clj/reify
       clojure.lang.ILookup
       (valAt [this k]
         (when (ex/-get* @nodes_ k) (get-node k)))

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
         (when (ex/-get* @nodes_ k)
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
         (when (ex/-get* @nodes_ k)
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
         (when (ex/-get* @nodes_ k) (get-node k)))
       (-invoke [this k x]
         (when-let [n (dag k)]
           (n x)))
       (-invoke [this k x y]
         (when-let [n (dag k)]
           ((n x) y))))))

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
  (when-not (and (identifiable? e) (string? (-id e)) (zero? (.indexOf (-id e) "listen")))
    (timbre/warnf "Using default event handler for %s, returning value as is, consider adding own method!"
                  (if (identifiable? e) (-id e) e)))
  e)

(defn dispatch [& args]
  (mbx (apply event args)))

(defprotocol UpdateEvent
  (update [_ dag]))

(defprotocol WatchEvent
  (watch [_ dag stream]))

(defprotocol EffectEvent
  (effect [_ dag stream]))

(defprotocol CustomEvent
  (process [_ dag stream])
  (props   [_           ]))

(defn update-event? [e]
  (satisfies? UpdateEvent e))

(defn watch-event? [e]
  (satisfies? WatchEvent e))

(defn effect-event? [e]
  (satisfies? EffectEvent e))

(defn custom-event? [e]
  (satisfies? CustomEvent e))

(defn event-type [e]
  (cond
    (update-event? e) "UpdateEvent"
    (watch-event? e)  "WatchEvent"
    (effect-event? e) "EffectEvent"
    :else             "UnknownEvent"))

(defmulti handle-error (fn [e _err] (-id ^IIdentifiable e)))

(defmethod handle-error :default
  [e err]
  (timbre/warn "Using default error handler, consider using your own!")
  (timbre/errorf "%s failure id: %s"
                 (event-type e)
                 (-id e))
  (timbre/error (ex-message err)))


#?(:cljs
   (defn promise?
     "Return `true` if `v` is a promise instance or is a thenable
  object.
  https://github.com/funcool/potok/blob/master/src/potok/core.cljs#L74"
     [v]
     (or (instance? js/Promise v)
         (and (goog.isObject v)
              (fn? (unchecked-get v "then"))))))

(defn publisher?
  [v]
  #?(:clj  (instance? missionary.impl.Reactor$Publisher v)
     :cljs (instance? missionary.impl.Reactor/Publisher v)))

(defn- -reset-graph-input! [dag m]
  (ex/-reduce-kv (fn [_ k v] (when-let [atm_ (-> @dag (ex/-get k) .-input)] (reset! atm_ v))) nil m))

#?(:clj
   (defn get-node [id]
     (when (ex/-get* @nodes_ id)
       (reify id
         INode
         (-dependencies [_]
           (-> (ex/-get*  @nodes_ id) .-deps))

         clojure.lang.IFn
         (invoke [_]
           ((-> (ex/-get*  @nodes_ id) .-flow)))

         (invoke [_ deps]
           ((-> (ex/-get*  @nodes_ id) .-f) id deps))

         (invoke [_ s f]
           ((-> (ex/-get*  @nodes_ id) .-flow) s f))

         clojure.lang.IRef
         (deref [_]
           (let [>f (-> (ex/-get*  @nodes_ id) .-flow)]
             (mi/reduce (comp reduced second {}) nil >f)))
         )))

   :cljs
   (defn get-node [id]
     (when (ex/-get* @nodes_ id)
       (reify id
         INode
         (-dependencies [_]
           (-> @nodes_ ^Vertex (ex/-get id) .-deps))

         IFn
         (-invoke [_]
           (.-flow ^Vertex (ex/-get @nodes_ id)))

         (-invoke [_ deps]
           ((.-f ^Vertex (ex/-get @nodes_ id)) id deps))

         (-invoke [_ s f]
           ((.-flow ^Vertex (ex/-get @nodes_ id)) s f))

         IDeref
         (-deref [_]
           (let [>f (.-flow ^Vertex (ex/-get @nodes_ id))]
             (mi/reduce (comp reduced second {}) nil >f)))
         ))))

(defrecord Vertex [id f input deps flow])

(defn- -vertex [{:keys [id f input deps flow]}]
  (->Vertex id f input deps flow))

(defn- -link! [id >fs]
  (if-let [vrtx (get-node id)]
    (mi/signal! (mi/eduction (dedupe) (apply mi/latest (fn [& args] [id (vrtx (into {} args))]) >fs)))
    (throw (ex-info (str "node " id " dosen't exists!") {:id id}))))

(defn- -dispose-graph! []
  (loop [[[id ^Vertex vrtx] & more] @dag]
    (when id
      (when-let [>f (.-flow ^Vertex vrtx)]
        (when (publisher? >f)
          (println :id id)
          (>f)))
      (recur more))))

(defeffect ::dispose-graph!
  [_ _ _]
  (-dispose-graph!))

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
          (println :id id :in in)
          (if (every? some? in)
            (let [>f (-link! id in)]
              (swap! dag assoc-in [id :flow] >f)
              (recur more (conj acc >f)))
            (recur (conj (into [] more) [id vrtx]) acc))))
      acc)))

(defeffect ::build-graph!
  [_ _ _]
  (-build-graph!))

(defn- -process-update-event [e]
  ((mi/sp
    (let [fs   (mapv (fn [id] (let [^Vertex vrtx (ex/-get* @dag id)] (.-flow vrtx))) (-dependencies e))
          r    (mi/? (mi/reduce (comp reduced {}) nil (apply mi/latest (fn [& args] (update e (into {} args))) fs)))]
      (if (map? r)
        (-reset-graph-input! dag r)
        (timbre/errorf "result of UpdateEvent: %s should be a map!, but is %s" (-id e) (type r)))))
   (constantly nil) #(handle-error e %)))

(defn- -process-watch-event [e]
  ((mi/sp
    (when-let [>f (watch e dag @stream_)]
      (cond
        (or (update-event? >f) (watch-event? >f) (effect-event? >f) (custom-event? >f))
        (mbx >f)

        (fn? >f)
        (mi/? (mi/reduce (fn [_ e] (mbx e)) >f))

        (publisher? >f)
        >f

        #?@(:cljs
            [(promise? >f)
             (-> >f (.then (fn [s] (dispatch s))) (.catch (fn [err] (handle-error e err))))])

        :else
        (timbre/errorf "result of WatchEvent: %s should be missionary ap! or js/promise, but is: %s" (-id e) (type >f)))))
   (constantly nil) #(handle-error e %)))

(defn- -process-effect-event [e]
  ((mi/sp
    (timbre/info "-process-effect-event 1")
    (when-let [>f (effect e dag @stream_)]
      (timbre/info "-process-effect-event 2")
      (cond
        (fn? >f)
        (mi/stream! >f)

        (publisher? >f)
        >f

        #?@(:cljs
            [(promise? >f)
             (-> >f (.then (constantly nil)) (.catch (fn [err] (handle-error e err))))]))))
   (constantly nil) #(handle-error e %)))

(defn add-node!
  ([id x]
   (when-not @reactor_
     (cond
       (instance? #?(:clj clojure.lang.Atom :cljs Atom) x)
       (let [>flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch x))]
         (swap! dag assoc id (-vertex {:id id :flow >flow :input x})))

       (fn? x)
       (swap! dag assoc id (-vertex {:id id :flow x}))

       :else
       (let [atm_ (atom x)
             >flow (mi/eduction (comp (map (fn [x] [id x])) (dedupe)) (mi/watch atm_))]
         (swap! dag assoc id (-vertex {:id id :flow >flow :input atm_})))))
   ;; (when @reactor_ (dispatch ::build-graph!))
   )

  ([id deps f]
   (when-not @reactor_
     (swap! dag assoc id (-vertex {:id id :f f :deps deps})))
   ;; (when @reactor_ (dispatch ::build-graph!))
   )

  ([id deps f >flow]
   (when-not @reactor_
     (swap! @dag assoc id (-vertex {:id id :f f :deps deps :flow >flow})))
   ;; (when @reactor_ (dispatch ::build-graph!))
   ))

(defn add-watch! [e]
  ((mi/sp
    (when @stream_
       (dispatch e)))
   (constantly nil) (constantly nil)))

(defeffect ::listen!
  [e m _ {:keys [deps dfv f]}]
  (let [deps (cond-> deps (not (seq? deps)) vector)
        fs   (mapv (fn [id] (or (some-> ^Vertex (ex/-get @dag id) .-flow) id)) deps)]
    (timbre/info "inside listenen!")
    (if-let [missing (seq (persistent!
                           (reduce
                            (fn [acc x]
                              (if (or (publisher? x) (fn? x)) acc (conj! acc x)))
                            (transient [])
                            fs)))]
      (timbre/errorf "some of deps dosen't exists! %s" missing)
      (dfv (->> (apply mi/latest (fn [& args] (into {} args)) fs)
                (mi/eduction (comp (dedupe) (map (fn [dag] (f e dag)))))
                (mi/stream!))))))

(defn listen! [deps f]
  (timbre/info "try to listen!")
  (let [dfv (mi/dfv)]
    (timbre/info "reactor" @reactor_ "stream" @stream_)
    (dispatch ::listen! {:deps deps :dfv dfv :f f})
    (fn []
      ((mi/sp (when-let [>f (mi/? dfv)] (>f)))
       #(timbre/debugf "successful unlisten %s" deps %)
       #(timbre/errorf "unsuccessful unlisten %s %s" deps %)))))

#?(:clj
   (defn value
     ([id]
      (value id ::none))
     ([id x]
      (let [atm_ (atom nil)
            lstn (listen! id
                   (fn [_ m]
                     (if #?(:clj (identical? ::none x) :cljs (keyword-identical? ::none x))
                       (when-not (= (ex/-get* m id) @atm_)
                         (reset! atm_ (ex/-get* m id)))
                       (when-not (= ((ex/-get* m id) x) @atm_)
                         (reset! atm_ ((ex/-get* m id) x))))))]
        (clojure.core/reify
          clojure.lang.IRef
          (deref [_] @atm_)

          clojure.lang.IFn
          (invoke [_] (lstn))))))
   :cljs
   (defn value
     ([-id]
      (value -id ::none))
     ([-id x]
      (let [atm_ (atom nil)
            lstn (listen! -id
                   (fn [_ m]
                     (if #?(:clj (identical? ::none x) :cljs (keyword-identical? ::none x))
                       (when-not (= (ex/-get* m -id) @atm_)
                         (reset! atm_ (ex/-get* m -id)))
                       (when-not (= ((ex/-get* m -id) x) @atm_)
                         (reset! atm_ ((ex/-get* m -id) x))))))]
        (cljs.core/reify
          IDeref
          (-deref [_] @atm_)

          IFn
          (-invoke [_] (lstn)))))))

#?(:clj
   (defn subscribe
     ([id]
      (value id ::none))
     ([-id x]
      (value -id x)))

   :cljs
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
           (listen! id (fn [_ v]
                         (if (identical? m ex/-sentinel)
                           (set-state! (ex/-get* v id))
                           (set-state! ((ex/-get* v id) m))))))
         #js [(str id) m'])
        (cljs.core/reify
          IDeref
          (-deref [_] state)
          IReset
          (-reset! [_ x] (set-state! x)))))))

(defn run! []
  ((mi/sp
    (timbre/info "try to run")
    (if-not @reactor_
      (let [r (mi/reactor
               (let [xs (-build-graph!)
                     _ (timbre/info "after build")
                     >e (mi/stream! (mi/ap (loop [] (mi/amb> (mi/? mbx) (recur)))))]
                 (timbre/info "after stream")
                 (vreset! stream_ >e)
                 (reduce (fn [acc >f] (conj acc (mi/stream! >f))) [] xs)
                 (timbre/info "after reduce")
                 (mi/stream! (->> >e (mi/eduction (filter update-event?) (map (fn [e] (-process-update-event e))))))
                 (timbre/info "after reduce 2")
                 (mi/stream! (->> >e (mi/eduction (filter watch-event?)  (map (fn [e] (-process-watch-event  e))))))
                 (timbre/info "after reduce 3")
                 (mi/stream! (->> >e (mi/eduction (filter effect-event?) (map (fn [e] (-process-effect-event e))))))
                 (timbre/info "after reduce 4")))]
        (timbre/info "vreset!")
        (vreset! reactor_ (r #(timbre/infof "succesfuly shutdown reactor" %) #(timbre/error %))))
      (do
        (tap> [:reactor :run! :builded!])
        (timbre/warn "graph already builded!"))))
   (constantly nil) #(timbre/error %)))

(defn dispose! []
  ((mi/sp
    (timbre/info "try to dispose")
    (when-let [cb @reactor_]
      (cb)
      (timbre/info "after callback")
      (vreset! reactor_ nil)
      (vreset! stream_ nil)))
   (constantly nil) #(timbre/error %)))

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

  (def !input (atom 1))
  (def main (mi/reactor
             (let [>x (mi/signal! (mi/watch !input)) ; continuous signal reflecting atom state
                   >y (mi/signal! (mi/latest + >x >x))] ; derived computation, diamond shape
               (mi/stream! (mi/ap (println (mi/?< >y))))))) ; discrete effect performed on successive values

  (def dispose! (main #(tap> [::success %]) #(tap> [::crash %])))
                                        ; 2
  (swap! !input inc)

  ((mi/sp (mi/? (mi/sleep 1000 :success))) prn prn)
                                        ; 4
  (dispose!)

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

  (defupdate ::update-test2
    [e {::keys [store]} {:keys [x]}]
    (println :update-test2 :x x)
    {::store {:a x :b x :c x}}))
