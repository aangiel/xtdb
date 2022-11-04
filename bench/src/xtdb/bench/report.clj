(ns xtdb.bench.report
  (:require [clojure.string :as str]
            [juxt.clojars-mirrors.hiccup.v2v0v0-alpha2.hiccup2.core :as hiccup2]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import (java.io File)
           (java.time Duration)))

(defn stage-points [metric-data]
  (into (sorted-map)
        (for [[stage metric-data] (group-by :stage metric-data)
              :let [first-samples (keep (comp first :samples) metric-data)
                    earliest-sample (reduce min Long/MAX_VALUE (map :time-ms first-samples))]]
          [stage earliest-sample])))

;; use vega to plot metrics for now
;; works at repl, no servers needed
;; if this approach takes of the time series data wants to be graphed in something like a shared prometheus / grafana during run
(defn vega-plots [metric-data]
  (let [stage-time-points (stage-points metric-data)]
    (vec
      (for [[[stage metric] metric-data]
            (sort-by (fn [[[stage metric]]] [(stage-time-points stage) metric])
                     (group-by (juxt :stage :metric) metric-data))]
        {:title (str stage " " metric)
         :hconcat (vec (for [[[_statistic unit] metric-data] (sort-by key (group-by (juxt :statistic :unit) metric-data))
                             :let [data {:values (vec (for [{:keys [vs-label, series, samples]} metric-data
                                                            {:keys [time-ms, value]} samples
                                                            :when (Double/isFinite value)]
                                                        {:time (str time-ms)
                                                         :config vs-label
                                                         :series series
                                                         :value value}))
                                         :format {:parse {:time "utc:'%Q'"}}}
                                   any-series (some (comp not-empty :series) metric-data)

                                   series-dimension (and any-series (< 1 (count metric-data)))
                                   vs-dimension (= 2 (bounded-count 2 (keep :vs-label metric-data)))

                                   stack-series series-dimension
                                   stack-vs (and (not stack-series) vs-dimension)
                                   facet-vs (and vs-dimension (not stack-vs))

                                   layer-instead-of-stack
                                   (cond stack-series (str/ends-with? metric "percentile value")
                                         stack-vs true)

                                   mark-type (if stack-vs "line" "area")

                                   spec {:mark {:type mark-type, :line true, :tooltip true}
                                         :encoding {:x {:field "time"
                                                        :type "quantitative"
                                                        :timeUnit "utcdayofyearhoursminutessecondsmilliseconds"
                                                        :title "Time"}
                                                    :y (let [y {:field "value"
                                                                :type "quantitative"
                                                                :title (or unit "Value")}]
                                                         (if layer-instead-of-stack
                                                           (assoc y :stack false)
                                                           y))
                                                    :color
                                                    (cond
                                                      stack-series
                                                      {:field "series",
                                                       :legend {:labelLimit 280}
                                                       :type "nominal"}
                                                      stack-vs
                                                      {:field "config",
                                                       :legend {:labelLimit 280}
                                                       :type "nominal"})}}]]
                         (if facet-vs
                           {:data data
                            :facet {:column {:field "config"}
                                    :header {:title nil}}
                            :spec spec}
                           (assoc spec :data data))))}))))

(defn group-metrics [rs]
  (let [{:keys [metrics]} rs

        group-fn
        (fn [{:keys [meter]}]
          (condp #(str/starts-with? %2 %1) meter
            "bench." "001 - Benchmark"
            "node." "002 - XTDB Node"
            "jvm.gc" "003 - JVM Memory / GC"
            "jvm.memory" "003 - JVM Memory / GC"
            "jvm.buffer" "004 - JVM Buffer"
            "system." "005 - System / Process"
            "process." "005 - System / Process"
            "006 - Other"))

        metric-groups (group-by group-fn metrics)]

    metric-groups))

(def transaction-prefix "bench.")

(defn transaction-count [metrics]
  (->> metrics
       (filter
         (every-pred
           #(= "count" (:statistic %))
           #(= "count" (:unit %))
           #(str/starts-with? (:metric %) transaction-prefix)
           #(str/ends-with? (:metric %) " count")))
       (map (comp :value last :samples))
       (reduce +)))

(defn stage-summary [{:keys [stage, start-ms, end-ms]} metrics]
  (let [duration (Duration/ofMillis (- end-ms start-ms))
        transactions (transaction-count metrics)]
    {:stage stage
     :start-ms start-ms
     :end-ms end-ms
     :transactions transactions
     :throughput (double (/ transactions (.toSeconds duration)))}))



;; report

;; :system
;; :stage
;; :metrics

;; view
;; takes label [report]
;; pairs

;; considers time series data across all stages
;;


(defn hiccup-report [title report]
  (let [id-from-thing
        (let [ctr (atom 0)]
          (memoize (fn [_] (str "id" (swap! ctr inc)))))]
    (list
      [:html
       [:head
        [:title title]
        [:meta {:charset "utf-8"}]
        [:script {:src "https://cdn.jsdelivr.net/npm/vega@5.22.1"}]
        [:script {:src "https://cdn.jsdelivr.net/npm/vega-lite@5.6.0"}]
        [:script {:src "https://cdn.jsdelivr.net/npm/vega-embed@6.21.0"}]
        [:style {:media "screen"}
         ".vega-actions a {
          margin-right: 5px;
        }"]]
       [:body
        [:h1 title]

        (for [{:keys [label, system]} (:systems report)
              :let [{:keys [jre, max-heap, arch, os, cpu, memory, java-opts]} system]]
          [:div [:h2 label]
           [:table
            [:thead [:th "jre"] [:th "heap"] [:th "arch"] [:th "os"] [:th "cpu"] [:th "memory"]]
            [:tbody [:tr [:td jre] [:td max-heap] [:td arch] [:td os] [:td cpu] [:td memory]]]]
           [:pre java-opts]])

        (for [stage (distinct (map :stage (:metrics report)))]
          [:div
           [:h3 (name stage)]
           [:div {:id (name stage)}]])

        [:div
         (for [[group metric-data] (sort-by key (group-metrics report))]
           (list [:h2 group]
                 (for [meter (sort (set (map :meter metric-data)))]
                   [:div {:id (id-from-thing meter)}])))]
        [:script
         (->> (concat
                (for [[stage metric-data] (group-by :stage (:metrics report))
                      :let [data {:values (vec (for [[vs-label metric-data] (group-by :vs-label metric-data)
                                                     [transaction metric-data] (group-by :meter metric-data)
                                                     :when (str/starts-with? transaction transaction-prefix)
                                                     :let [transaction (subs transaction (count transaction-prefix))
                                                           transactions (transaction-count metric-data)]
                                                     :when (pos? transactions)]
                                                 {:config vs-label
                                                  :transaction transaction
                                                  :transactions transactions}))}
                            vega
                            {:data data
                             :hconcat [{:mark "bar"
                                        :encoding {:x {:field "config", :type "nominal", :sort "-y"}
                                                   :y {:field "transactions", :aggregate "sum", :type "quantitative"}}}
                                       {:mark "bar"
                                        :encoding {:x {:field "transaction", :type "nominal", :sort "-y"}
                                                   :y {:field "transactions", :type "quantitative"}
                                                   :xOffset {:field "config", :type "nominal"}
                                                   :color {:field "config", :type "nominal"}}}]}]
                      :when (->> data :values (map :transactions) (some pos?))]
                  (format "vegaEmbed('#%s', %s);" (name stage) (json/write-str vega)))

                (for [[meter metric-data] (group-by :meter (:metrics report))]
                  (format "vegaEmbed('#%s', %s);" (id-from-thing meter)
                          (json/write-str
                            {:hconcat (vega-plots metric-data)}))))



              (str/join "\n")
              hiccup2/raw)]]])))

(defn show-html-report [rs]
  (let [f (File/createTempFile "xtdb-benchmark-report" ".html")]
    (spit f (hiccup2/html
              {}
              (hiccup-report (:title rs "Benchmark report") rs)))
    (clojure.java.browse/browse-url (io/as-url f))))

(defn- normalize-time [report]
  (let [{:keys [metrics]} report
        min-time (->> metrics
                      (mapcat :samples)
                      (reduce #(min %1 (:time-ms %2)) Long/MAX_VALUE))
        new-metrics (for [metric metrics
                          :let [{:keys [samples]} metric
                                new-samples (mapv #(update % :time-ms - min-time) samples)]]
                      (assoc metric :samples new-samples))]
    (assoc report :metrics (vec new-metrics))))

(defn vs [label report & more]
  (let [pair-seq (cons [label report] (partition 2 more))
        ;; with order
        key-seq (map first pair-seq)
        ;; index without order
        report-map (apply hash-map label report more)]
    {:title (str/join " vs " key-seq)
     :systems (for [label key-seq] {:label label, :system (:system (report-map label))})
     :metrics (vec (for [[i label] (map-indexed vector key-seq)
                         report (:reports (report-map label))
                         :let [{:keys [stage, metrics]} (normalize-time report)]
                         metric metrics]
                     (assoc metric :vs-label (str label), :stage stage)))}))

(defn stage-only [report stage]
  (update report :reports (partial filterv #(= stage (:stage %)))))
