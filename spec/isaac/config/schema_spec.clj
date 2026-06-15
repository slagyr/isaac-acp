(ns isaac.config.schema-spec
  (:require
    [clojure.edn :as edn]
    [isaac.schema.lexicon :as lexicon]
    [speclj.core :refer :all]))

(defn- acp-schema []
  (-> "src/isaac-manifest.edn"
      slurp
      edn/read-string
      (get-in [:isaac.config/schema :acp :schema])))

(describe "config schema"

  (it "acp conforms"
    (should= {:proxy-max-reconnects 5
              :proxy-reconnect-delay-ms 1000
              :proxy-reconnect-max-delay-ms 5000}
             (lexicon/conform (acp-schema)
                              {:proxy-max-reconnects 5
                               :proxy-reconnect-delay-ms 1000
                               :proxy-reconnect-max-delay-ms 5000}))))
