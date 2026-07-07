(ns isaac.manifest-spec
  (:require
    [clojure.edn :as edn]
    [isaac.cli.registry :as registry]
    [isaac.comm.registry :as comm-registry]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.server.routes :as routes]
    [isaac.system :as system]
    [speclj.core :refer :all]))

(defn- manifest []
  (-> "src/isaac-manifest.edn" slurp edn/read-string))

(describe "isaac-manifest.edn"

  (around [example]
    (try
      (example)
      (finally
        (module-loader/shutdown-modules!)
        (module-loader/clear-activations!)
        (registry/clear-berth-commands!))))

  (it "declares a resolvable module factory"
    (should-not-be-nil (requiring-resolve (get (manifest) :factory))))

  (it "registers only the acp CLI command when berths are processed"
    (system/with-system {:fs (fs/real-fs)}
      (binding [comm-registry/*registry* (atom (comm-registry/fresh-registry))
                routes/*registry*        (atom (routes/fresh-registry))]
        (let [module-index (merge (module-loader/builtin-index)
                                  {:isaac.comm.acp {:manifest (manifest)}})]
          (registry/clear-module-commands!)
          (should= :activated (module-loader/activate! :isaac.comm.acp module-index))
          (should= [] (module-loader/process-manifest-berths! module-index))
          (should-not-be-nil (registry/get-command "acp"))
          (should-be-nil (registry/get-command "chat"))
          (should-not (routes/route-registered? :get "/acp")))))))
