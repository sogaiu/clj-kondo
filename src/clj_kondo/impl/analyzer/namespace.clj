(ns clj-kondo.impl.analyzer.namespace
  {:no-doc true}
  (:refer-clojure :exclude [ns-name])
  (:require
   [clj-kondo.impl.analysis :as analysis]
   [clj-kondo.impl.findings :as findings]
   [clj-kondo.impl.linters.misc :refer [lint-duplicate-requires!]]
   [clj-kondo.impl.metadata :as meta]
   [clj-kondo.impl.namespace :as namespace]
   [clj-kondo.impl.utils :refer [node->line one-of tag sexpr vector-node
                                 token-node string-from-token symbol-from-token
                                 assoc-some]]
   [clojure.set :as set]
   [clojure.string :as str]))

(def valid-ns-name? (some-fn symbol? string?))

(defn- prefix-spec?
  "Adapted from clojure.tools.namespace"
  [form]
  (and (sequential? form) ; should be a list, but often is not
       (symbol? (first form))
       (not-any? keyword? form)
       (< 1 (count form))))  ; not a bare vector like [foo]

(defn- option-spec?
  "Adapted from clojure.tools.namespace"
  [form]
  (and (sequential? form)  ; should be a vector, but often is not
       (valid-ns-name? (first form))
       (or (keyword? (second form))  ; vector like [foo :as f]
           (= 1 (count form)))))  ; bare vector like [foo]

(defn normalize-libspec
  "Adapted from clojure.tools.namespace."
  [ctx prefix libspec-expr]
  (let [libspec-expr (meta/lift-meta-content2 ctx libspec-expr)
        children (:children libspec-expr)
        form (sexpr libspec-expr)]
    (cond (prefix-spec? form)
          (mapcat (fn [f]
                    (normalize-libspec ctx
                                       (symbol (str (when prefix (str prefix "."))
                                                    (first form)))
                                       f))
                  (rest children))
          (option-spec? form)
          [(with-meta
             (vector-node (into (normalize-libspec ctx prefix (first children)) (rest children)))
             (meta libspec-expr))]
          (valid-ns-name? form)
          (let [full-form (symbol (str (when prefix (str prefix "."))
                                       form))]
            [(with-meta (token-node full-form)
               (assoc (meta libspec-expr)
                      :raw-name form))])
          (keyword? form)  ; Some people write (:require ... :reload-all)
          nil
          :else
          (throw (ex-info "Unparsable namespace form"
                          {:reason ::unparsable-ns-form
                           :form form})))))

(defn analyze-libspec
  [{:keys [:base-lang :lang
           :filename :findings]} current-ns-name require-kw-expr libspec-expr]
  (let [require-sym (:value require-kw-expr)
        require-kw (or (:k require-kw-expr)
                       (when require-sym
                         (keyword require-sym)))
        use? (= :use require-kw)]
    (if-let [s (symbol-from-token libspec-expr)]
      [{:type :require
        :referred-all (when use? require-kw-expr)
        :ns (with-meta s
              (assoc (meta libspec-expr)
                     :filename filename))}]
      (let [[ns-name-expr & option-exprs] (:children libspec-expr)
            ns-name (:value ns-name-expr)
            ns-name (if (= :cljs lang)
                      (case ns-name
                        clojure.test 'cljs.test
                        clojure.pprint 'cljs.pprint
                        ns-name)
                      ns-name)
            ns-name (with-meta ns-name
                      (assoc (meta (first (:children libspec-expr)))
                             :filename filename
                             :raw-name (-> (meta ns-name-expr) :raw-name)))
            self-require? (and
                           (= :cljc base-lang)
                           (= :cljs lang)
                           (= current-ns-name ns-name)
                           (= :require-macros require-kw))]
        (loop [children option-exprs
               {:keys [:as :referred :excluded
                       :referred-all :renamed] :as m}
               {:as nil
                :referred #{}
                :excluded #{}
                :referred-all (when use? require-kw-expr)
                :renamed {}}]
          ;; (prn "children" children)
          (if-let [child-expr (first children)]
            (let [opt-expr (fnext children)
                  opt (sexpr opt-expr)
                  child-k (:k child-expr)]
              (case child-k
                (:refer :refer-macros :only)
                (recur
                 (nnext children)
                 (cond (and (not self-require?) (sequential? opt))
                       (let [;; undo referred-all when using :only with :use
                             m (if (and use? (= :only child-k))
                                 (do (findings/reg-finding!
                                      findings
                                      (node->line
                                       filename
                                       referred-all
                                       :warning
                                       :use
                                       (format "use %srequire with alias or :refer with [%s]"
                                               (if require-sym
                                                 "" ":")
                                               (str/join " " (sort opt)))))
                                     (dissoc m :referred-all))
                                 m)]
                         (update m :referred into
                                 (map #(with-meta (sexpr %)
                                         (meta %))) (:children opt-expr)))
                       (= :all opt)
                       (assoc m :referred-all opt-expr)
                       :else m))
                :as (recur
                     (nnext children)
                     (assoc m :as opt))
                ;; shadow-cljs:
                ;; https://shadow-cljs.github.io/docs/UsersGuide.html#_about_default_exports
                :default
                (recur (nnext children)
                       (update m :referred conj opt))
                :exclude
                (recur
                 (nnext children)
                 (update m :excluded into (set opt)))
                :rename
                (recur
                 (nnext children)
                 (-> m (update :renamed merge opt)
                     ;; for :refer-all we need to know the excluded
                     (update :excluded into (set (keys opt)))
                     ;; for :refer it is sufficient to pretend they were never referred
                     (update :referred set/difference (set (keys opt)))))
                (recur (nnext children)
                       m)))
            [{:type :require
              :ns ns-name
              :as as
              :require-kw require-kw
              :excluded excluded
              :referred (concat (map (fn [refer]
                                       [refer {:ns ns-name
                                               :name refer}])
                                     referred)
                                (map (fn [[original-name new-name]]
                                       [new-name {:ns ns-name
                                                  :name original-name}])
                                     renamed))
              :referred-all referred-all}]))))))

(defn analyze-java-import [_ctx _ns-name libspec-expr]
  (case (tag libspec-expr)
    (:vector :list) (let [children (:children libspec-expr)
                          java-package-name-node (first children)
                          java-package (:value java-package-name-node)
                          imported (map :value (rest children))]
                      (into {} (for [i imported]
                                 [i java-package])))
    :token (let [package+class (:value libspec-expr)
                 splitted (-> package+class name (str/split #"\."))
                 java-package (symbol (str/join "." (butlast splitted)))
                 imported (symbol (last splitted))]
             {imported java-package})
    nil))

(defn analyze-require-clauses [{:keys [:lang] :as ctx} ns-name kw+libspecs]
  (let [analyzed (for [[require-kw libspecs] kw+libspecs
                       libspec-expr libspecs
                       normalized-libspec-expr (normalize-libspec ctx nil libspec-expr)
                       analyzed (analyze-libspec ctx ns-name require-kw normalized-libspec-expr)]
                   analyzed)
        refer-alls (reduce (fn [acc clause]
                             (if-let [m (:referred-all clause)]
                               (assoc acc (:ns clause)
                                      {:excluded (:excluded clause)
                                       :node m
                                       :referred #{}})
                               acc))
                           {}
                           analyzed)]
    (lint-duplicate-requires! ctx (map (juxt :require-kw :ns) analyzed))
    {:required (map (fn [req]
                      (vary-meta (:ns req)
                                 #(assoc % :alias (:as req)))) analyzed)
     :qualify-ns (reduce (fn [acc sc]
                           (cond-> (assoc acc (:ns sc) (:ns sc))
                             (:as sc)
                             (assoc (:as sc) (:ns sc))))
                         {}
                         analyzed)
     :referred-vars (into {} (mapcat :referred analyzed))
     :refer-alls refer-alls
     :used-namespaces
     (-> (case lang
           :clj '#{clojure.core}
           :cljs '#{cljs.core})
         (into (keys refer-alls))
         (conj ns-name)
         (into (when-not
                   (-> ctx :config :linters :unused-namespace :simple-libspec)
                 (keep (fn [req]
                         (when (and (not (:as req))
                                    (empty? (:referred req)))
                           (:ns req)))
                       analyzed))))}))

(defn analyze-ns-decl
  [{:keys [:base-lang :lang :findings :filename] :as ctx} expr]
  (let [{:keys [row col]} (meta expr)
        children (next (:children expr))
        ns-name-expr (first children)
        ns-name-expr  (meta/lift-meta-content2 ctx ns-name-expr)
        metadata (meta ns-name-expr)
        children (next children) ;; first = docstring, attr-map or libspecs
        fc (first children)
        docstring (when fc
                    (string-from-token fc))
        meta-node (when fc
                    (let [t (tag fc)]
                      (if (= :map t)
                        fc
                        (when-let [sc (second children)]
                          (when (= :map (tag sc))
                            sc)))))
        ns-meta (if meta-node
                  (merge metadata
                         (sexpr meta-node))
                  metadata)
        local-config (-> ns-meta :clj-kondo/config second)
        ns-name (or
                 (when-let [?name (sexpr ns-name-expr)]
                   (if (symbol? ?name) ?name
                       (findings/reg-finding!
                        findings
                        (node->line (:filename ctx)
                                    ns-name-expr
                                    :error
                                    :syntax
                                    "namespace name expected"))))
                 'user)
        clauses children
        kw+libspecs (for [?require-clause clauses
                          :let [require-kw-node (-> ?require-clause :children first)
                                require-kw (:k require-kw-node)
                                require-kw (one-of require-kw [:require :require-macros :use])]
                          :when require-kw]
                      [require-kw-node (-> ?require-clause :children next)])
        analyzed-require-clauses
        (analyze-require-clauses ctx ns-name kw+libspecs)
        java-imports
        (apply merge
               (for [?import-clause clauses
                     :let [import-kw (some-> ?import-clause :children first :k
                                             (= :import))]
                     :when import-kw
                     libspec-expr (rest (:children ?import-clause))]
                 (analyze-java-import ctx ns-name libspec-expr)))
        refer-clojure-clauses
        (apply merge-with into
               (for [?refer-clojure (nnext (sexpr expr))
                     :when (= :refer-clojure (first ?refer-clojure))
                     [k v] (partition 2 (rest ?refer-clojure))
                     :let [r (case k
                               :exclude
                               {:excluded (set v)}
                               :rename
                               {:renamed v
                                :excluded (set (keys v))})]]
                 r))
        refer-clojure {:referred-vars
                       (into {} (map (fn [[original-name new-name]]
                                       [new-name {:ns 'clojure.core
                                                  :name original-name}])
                                     (:renamed refer-clojure-clauses)))
                       :clojure-excluded (:excluded refer-clojure-clauses)}
        ns (cond->
               (merge {:type :ns
                       :filename filename
                       :base-lang base-lang
                       :lang lang
                       :name ns-name
                       :bindings #{}
                       :used-bindings #{}
                       :used-referred-vars #{}
                       :used-vars []
                       :vars {}
                       :java-imports java-imports
                       :row row
                       :col col}
                      (merge-with into
                                  analyzed-require-clauses
                                  refer-clojure))
             local-config (assoc :config local-config)
             (= :clj lang) (update :qualify-ns
                                   #(assoc % 'clojure.core 'clojure.core))
             (= :cljs lang) (update :qualify-ns
                                    #(assoc % 'cljs.core 'cljs.core
                                            'clojure.core 'cljs.core)))]
    (when (-> ctx :config :output :analysis)
      (analysis/reg-namespace! ctx {:filename filename
                                    :row row
                                    :col col
                                    :ns-name ns-name
                                    :in-ns false
                                    :metadata (assoc-some {}
                                                :deprecated (:deprecated ns-meta)
                                                :doc docstring
                                                :added (:added ns-meta)
                                                :no-doc (:no-doc ns-meta)
                                                :author (:author ns-meta))})
      (doseq [req (:required ns)]
        (let [{:keys [row col alias]} (meta req)]
          (analysis/reg-namespace-usage! ctx {:filename filename
                                              :row row
                                              :col col
                                              :from-ns ns-name
                                              :to-ns req
                                              :alias alias}))))
    (namespace/reg-namespace! ctx ns)
    ns))

;;;; Scratch

(comment
  )
