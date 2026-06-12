(ns isaac.system
  (:refer-clojure :exclude [get get-in])
  (:require
    [isaac.nexus :as nexus]))

(defn get [k]
  (nexus/get k))

(defn get-in [path]
  (nexus/get-in path))

(defn register! [k v]
  (nexus/register! [k] v))

(defn deregister! [k]
  (nexus/deregister! [k]))

(defn registered? [k]
  (nexus/registered? [k]))

(defn init!
  ([] (nexus/init!))
  ([overrides] (nexus/init! overrides)))

(defn reset! []
  (nexus/reset!))

(defmacro with-system [m & body]
  `(nexus/-with-nexus ~m ~@body))

(defmacro with-nested-system [m & body]
  `(nexus/-with-nested-nexus ~m ~@body))
