(ns clj-kondo.impl.analysis
  "Helpers for analysis output"
  {:no-doc true}
  (:refer-clojure :exclude [ns-name])
  (:require [clj-kondo.impl.utils :refer [assoc-some select-some]]))

(defn reg-usage! [{:keys [analysis] :as _ctx}
                  filename row col from-ns to-ns full-fn-name var-name arity lang metadata]
  (let [to-ns (or (some-> to-ns meta :raw-name) to-ns)]
    (swap! analysis update :var-usages conj
           (assoc-some
            (merge
             {:filename filename
              :row row
              :col col
              :from from-ns
              :to to-ns
              :name var-name}
             (select-some metadata
                          [:private :macro
                           :fixed-arities
                           :var-args-min-arity
                           :deprecated]))
            :full-fn-name full-fn-name
            :arity arity
            :lang lang))))

(defn reg-var! [{:keys [:analysis :base-lang :lang] :as _ctx}
                filename row col ns name attrs]
  (let [attrs (select-keys attrs [:private :macro :fixed-arities :var-args-min-arity
                                  :doc :added :deprecated])]
    (swap! analysis update :var-definitions conj
           (assoc-some
            (merge {:filename filename
                    :row row
                    :col col
                    :ns ns
                    :name name}
                   attrs)
            :lang (when (= :cljc base-lang) lang)))))

(defn reg-namespace! [{:keys [:analysis :base-lang :lang] :as _ctx}
                      filename row col ns-name in-ns metadata]
  (swap! analysis update :namespace-definitions conj
         (assoc-some
          (merge {:filename filename
                  :row row
                  :col col
                  :name ns-name}
                 metadata)
          :in-ns (when in-ns in-ns) ;; don't include when false
          :lang (when (= :cljc base-lang) lang))))

(defn reg-namespace-usage! [{:keys [:analysis :base-lang :lang] :as _ctx}
                            filename row col from-ns to-ns alias]
  (let [to-ns (or (some-> to-ns meta :raw-name) to-ns)]
    (swap! analysis update :namespace-usages conj
           (assoc-some
            {:filename filename
             :row row
             :col col
             :from from-ns
             :to to-ns}
            :lang (when (= :cljc base-lang) lang)
            :alias alias))))
