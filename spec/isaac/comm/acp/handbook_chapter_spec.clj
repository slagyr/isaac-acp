(ns isaac.comm.acp.handbook-chapter-spec
  "Lint for isaac-acp's own handbook chapter (isaac-vwa6): every backtick
   `config:<path>` reference must resolve against the composed config schema,
   and every `isaac <command>` invocation must name a registered top-level
   CLI command. Mirrors isaac.foundation's own, adapted like isaac-gmail's
   (isaac-g8k2) to read raw manifests rather than isaac.module.berths'
   introspection helpers, which aren't available at every foundation pin a
   module may carry. See the convention comment at the top of the chapter
   file itself."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.schema.resolve :as schema-resolve]
    [isaac.fs :as fs]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private chapter-resource "isaac/comm/acp/handbook.md")

(defn- chapter-text []
  (some-> (io/resource chapter-resource) slurp))

(defn- config-refs
  "Backtick `config:<path>` references in `text`, skipping `<placeholder>`
   shapes (any reference whose path still contains an angle bracket)."
  [text]
  (->> (re-seq #"`config:([^`]+)`" text)
       (map second)
       (remove #(str/includes? % "<"))
       distinct))

(defn- cli-commands-mentioned
  "The word immediately following `isaac ` wherever it appears — inline
   code, fenced examples, or plain prose — for every top-level `isaac
   <command>` invocation in `text`."
  [text]
  (->> (re-seq #"isaac\s+([a-zA-Z][a-zA-Z0-9_-]*)" text)
       (map second)
       distinct))

(defn- known-cli-commands
  "Top-level command names contributed to the :isaac/cli berth by every
   module in `index` (builtin only, for this repo's own spec) — read
   directly off each module's manifest rather than through
   isaac.module.berths, whose report helpers vary across pinned foundation
   shas."
  [index]
  (->> (vals index)
       (mapcat (fn [entry] (keys (get-in entry [:manifest :isaac/cli]))))
       (map name)
       set))

(describe "isaac-acp handbook chapter (isaac-vwa6)"

  (around [example] (nexus/-with-nexus {:fs (fs/real-fs)} (example)))

  (it "ships at the manifest's declared classpath resource"
    (should-not-be-nil (chapter-text)))

  (it "every `config:<path>` reference resolves against the composed config schema"
    (let [text        (chapter-text)
          root-schema (schema-compose/effective-root-schema (discovery/builtin-index))
          unresolved  (remove #(schema-resolve/schema-for-data-path root-schema %)
                              (config-refs text))]
      (should= [] unresolved)))

  (it "every `isaac <command>` invocation names a registered top-level CLI command"
    (let [text    (chapter-text)
          known   (known-cli-commands (discovery/builtin-index))
          unknown (remove known (cli-commands-mentioned text))]
      (should= [] unknown))))
