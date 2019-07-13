(ns re-streamer.core
  #?(:cljs (:require [reagent.core :as reagent]))
  (:refer-clojure :rename {map c-map distinct c-distinct filter c-filter flush c-flush}))

;; types

(defprotocol Subscribable
  (subscribe [this sub])
  (unsubscribe [this sub])
  (destroy [this]))

(defprotocol Emitable
  (emit [this val])
  (flush [this]))

(defrecord Stream [subs state type])

(defonce stream-subscriber-impl
         {:subscribe   (fn [this sub] (swap! (:subs this) conj sub))
          :unsubscribe (fn [this sub] (swap! (:subs this) disj sub))
          :destroy     (fn [this] (remove-watch (:state this) :state-watcher))})

(defonce emitter-impl
         {:emit  (fn [this val] (reset! (:state this) val))
          :flush (fn [this] (reset! (:subs this) #{}))})

(extend Stream
  Subscribable
  stream-subscriber-impl
  Emitable
  emitter-impl)

(defrecord BehaviorStream [subs state type])

(defonce behavior-stream-subscriber-impl
         (assoc stream-subscriber-impl
           :subscribe (fn [this sub]
                        (swap! (:subs this) conj sub)
                        (sub @(:state this)))))

(extend BehaviorStream
  Subscribable
  behavior-stream-subscriber-impl
  Emitable
  emitter-impl)

(defrecord Subscriber [subs state parent type])

(defonce subscriber-impl
         (assoc stream-subscriber-impl
           :destroy (fn [this]
                      (remove-watch (:state this) :state-watcher)
                      (remove-watch (:state (:parent this)) (:watcher-key (:parent this))))))

(extend Subscriber
  Subscribable
  subscriber-impl)

(defrecord BehaviorSubscriber [subs state parent type])

(defonce behavior-subscriber-impl
         (assoc behavior-stream-subscriber-impl
           :destroy (fn [this]
                      (remove-watch (:state this) :state-watcher)
                      (remove-watch (:state (:parent this)) (:watcher-key (:parent this))))))

(extend BehaviorSubscriber
  Subscribable
  behavior-subscriber-impl)

;; factories

(defn create-stream
  ([] (create-stream nil))
  ([val] (let [subs (atom #{})
               state #?(:cljs    (reagent/atom val)
                        :default (atom val))]

           (add-watch state :state-watcher #(doseq [sub @subs] (sub %4)))

           (->Stream subs state ::subscriber))))

(defn create-behavior-stream
  ([] (create-behavior-stream nil))
  ([val]
   (let [subs (atom #{})
         state #?(:cljs    (reagent/atom val)
                  :default (atom val))]

     (add-watch state :state-watcher #(doseq [sub @subs] (sub %4)))

     (->BehaviorStream subs state ::behavior-subscriber))))

(derive Subscriber ::subscriber)
(derive Stream ::subscriber)

(derive BehaviorSubscriber ::behavior-subscriber)
(derive BehaviorStream ::behavior-subscriber)

(defmulti create-subscriber (fn [stream _ _ _] (:type stream)))

(defmethod create-subscriber ::subscriber [stream watcher-key subs state]
  (->Subscriber subs state {:state (:state stream) :watcher-key watcher-key} ::subscriber))

(defmethod create-subscriber ::behavior-subscriber [stream watcher-key subs state]
  (->BehaviorSubscriber subs state {:state (:state stream) :watcher-key watcher-key} ::behavior-subscriber))

;; watcher keys generator

(defonce ^:private watcher-key
         (let [counter (atom 0)]
           #(swap! counter inc)))

;; operators

(defn map
  ([stream f]
   (map stream f (watcher-key)))
  ([stream f watcher-key]
   (let [state #?(:cljs    (reagent/atom (f @(:state stream)))
                  :default (atom (f @(:state stream))))
         subs (atom #{})]

     (add-watch (:state stream) watcher-key #(reset! state (f %4)))
     (add-watch state :watch #(doseq [sub @subs] (sub %4)))

     (create-subscriber stream watcher-key subs state))))
