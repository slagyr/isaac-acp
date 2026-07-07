(ns isaac.config.schema-spec
  (:require
    [clojure.edn :as edn]
    [speclj.core :refer :all]))

(defn- manifest []
  (-> "src/isaac-manifest.edn"
      slurp
      edn/read-string))

(describe "config schema"

  (it "does not declare ACP config schema entries"
    (should-be-nil (get (manifest) :isaac.config/schema))))
