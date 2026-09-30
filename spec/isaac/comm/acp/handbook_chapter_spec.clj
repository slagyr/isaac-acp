(ns isaac.comm.acp.handbook-chapter-spec
  "Lint for isaac-acp's own handbook chapter (isaac-vwa6): every backtick
   `config:<path>` reference must resolve against the composed config schema,
   and every `isaac <command>` invocation must name a registered top-level
   CLI command. isaac-acp is NOT a builtin module (isaac-vwa6 follow-up:
   :builtin? true was only ever added so this spec could see acp's own
   :isaac/cli and :handbook via isaac.module.discovery/builtin-index, but
   that flag also changes acp's runtime loading — a docs bean can't do
   that). So this reads acp's own manifest and chapter directly as
   classpath resources instead, and checks CLI commands against the union
   of acp's own manifest and foundation/agent's builtin commands. Mirrors
   isaac.gmail's / isaac.google's own handbook lint specs, adapted since
   acp can't lean on being builtin the way they do."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.schema.resolve :as schema-resolve]
    [isaac.fs :as fs]
    [isaac.module.coords :as coords]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private acp-module-id :isaac.comm.acp)

(def ^:private chapter-resource "isaac/comm/acp/handbook.md")

(defn- chapter-text []
  (some-> (io/resource chapter-resource) slurp))

(defn- acp-manifest
  "isaac-acp's own manifest, read straight off its classpath resource by
   id — not through builtin-index, since acp isn't builtin."
  []
  (some-> (discovery/manifest-resource acp-module-id) coords/read-manifest-edn))

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

(defn- index-cli-commands
  "Top-level command names contributed to the :isaac/cli berth by every
   module in `index` — read directly off each module's manifest rather
   than through isaac.module.berths, whose report helpers vary across
   pinned foundation shas."
  [index]
  (->> (vals index)
       (mapcat (fn [entry] (keys (get-in entry [:manifest :isaac/cli]))))
       (map name)
       set))

(defn- known-cli-commands
  "Union of acp's own manifest CLI commands with every builtin module's
   (foundation and agent are always builtin; acp is not, so it's added
   in explicitly)."
  [manifest]
  (into (index-cli-commands (discovery/builtin-index))
        (map name (keys (:isaac/cli manifest)))))

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
          known   (known-cli-commands (acp-manifest))
          unknown (remove known (cli-commands-mentioned text))]
      (should= [] unknown))))
