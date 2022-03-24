(ns core2.sql.tpch-sql-test
  (:require [core2.sql :as sql]
            [core2.sql.analyze :as sem]
            [core2.sql.plan :as plan]
            [instaparse.core :as insta]
            [clojure.java.io :as io]
            [clojure.test :as t]))

(t/deftest test-parse-tpch-queries
  (doseq [q (range 22)
          :let [f (format "q%02d.sql" (inc q))
                sql-ast (sql/parse (slurp (io/resource (str "core2/sql/tpch/" f))))
                {:keys [errs plan]} (plan/plan-query sql-ast)]]
    (t/is (empty? errs) (str f))
    (t/is (vector? plan))))