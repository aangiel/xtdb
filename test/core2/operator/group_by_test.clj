(ns core2.operator.group-by-test
  (:require [clojure.test :as t]
            [core2.operator.group-by :as group-by]
            [core2.test-util :as tu]
            [core2.types :as types]
            [core2.util :as util]
            [core2.vector.indirect :as iv])
  (:import org.apache.arrow.vector.types.pojo.Schema))

(t/use-fixtures :each tu/with-allocator)

(t/deftest test-group-by
  (let [a-field (types/->field "a" types/bigint-type false)
        b-field (types/->field "b" types/bigint-type false)]
    (letfn [(run-test [group-cols agg-specs blocks]
              (with-open [in-cursor (tu/->cursor (Schema. [a-field b-field]) blocks)
                          group-by-cursor (group-by/->group-by-cursor tu/*allocator* in-cursor group-cols agg-specs)]
                (set (first (tu/<-cursor group-by-cursor)))))]

      (let [agg-specs [(group-by/->aggregate-factory :sum "b" "sum")
                       (group-by/->aggregate-factory :avg "b" "avg")
                       (group-by/->aggregate-factory :count "b" "cnt")
                       (group-by/->aggregate-factory :min "b" "min")
                       (group-by/->aggregate-factory :max "b" "max")
                       (group-by/->aggregate-factory :variance "b" "variance")
                       (group-by/->aggregate-factory :std-dev "b" "std-dev")]]

        (t/is (= #{{:a 1, :sum 140, :avg 35.0, :cnt 4 :min 10 :max 60,
                    :variance 425.0, :std-dev 20.615528128088304}
                   {:a 2, :sum 140, :avg 46.666666666666664, :cnt 3 :min 30 :max 70
                    :variance 288.88888888888914, :std-dev 16.996731711975958}
                   {:a 3, :sum 170, :avg 85.0, :cnt 2 :min 80 :max 90,
                    :variance 25.0, :std-dev 5.0}}
                 (run-test ["a"] agg-specs
                           [[{:a 1 :b 20}
                             {:a 1 :b 10}
                             {:a 2 :b 30}
                             {:a 2 :b 40}]
                            [{:a 1 :b 50}
                             {:a 1 :b 60}
                             {:a 2 :b 70}
                             {:a 3 :b 80}
                             {:a 3 :b 90}]])))

        (t/is (empty? (run-test ["a"] agg-specs []))
              "empty input"))

      (t/is (= #{{:a 1} {:a 2} {:a 3}}
               (run-test ["a"] []
                         [[{:a 1 :b 10}
                           {:a 1 :b 20}
                           {:a 2 :b 10}
                           {:a 2 :b 20}]
                          [{:a 1 :b 10}
                           {:a 1 :b 20}
                           {:a 2 :b 10}
                           {:a 3 :b 20}
                           {:a 3 :b 10}]]))
            "group without aggregate")

      (t/is (= #{{:a 1, :b 10, :cnt 2}
                 {:a 1, :b 20, :cnt 2}
                 {:a 2, :b 10, :cnt 2}
                 {:a 2, :b 20, :cnt 1}
                 {:a 3, :b 10, :cnt 1}
                 {:a 3, :b 20, :cnt 1}}
               (run-test ["a" "b"] [(group-by/->aggregate-factory :count "b" "cnt")]
                         [[{:a 1 :b 10}
                           {:a 1 :b 20}
                           {:a 2 :b 10}
                           {:a 2 :b 20}]
                          [{:a 1 :b 10}
                           {:a 1 :b 20}
                           {:a 2 :b 10}
                           {:a 3 :b 20}
                           {:a 3 :b 10}]]))
            "multiple group columns (distinct)")

      (t/is (= #{{:cnt 9}}
               (run-test [] [(group-by/->aggregate-factory :count "b" "cnt")]
                         [[{:a 1 :b 10}
                           {:a 1 :b 20}
                           {:a 2 :b 10}
                           {:a 2 :b 20}]
                          [{:a 1 :b 10}
                           {:a 1 :b 20}
                           {:a 2 :b 10}
                           {:a 3 :b 20}
                           {:a 3 :b 10}]]))
            "aggregate without group"))))

(t/deftest test-promoting-sum
  (with-open [group-mapping (tu/->mono-vec "gm" types/int-type (map int [0 0 0]))
              v0 (tu/->mono-vec "v" types/bigint-type [1 2 3])
              v1 (tu/->duv "v" [1 2.0 3])]
    (let [sum-spec (-> (group-by/->aggregate-factory :sum "v" "vsum")
                       (.build tu/*allocator*))]
      (try
        (.aggregate sum-spec (iv/->direct-vec v0) group-mapping)
        (.aggregate sum-spec (iv/->direct-vec v1) group-mapping)
        (t/is (= [12.0] (tu/<-column (.finish sum-spec))))
        (finally
          (util/try-close sum-spec))))))

(t/deftest test-array-agg
  (with-open [gm0 (tu/->mono-vec "gm0" types/int-type (map int [0 1 0]))
              k0 (tu/->mono-vec "k" types/bigint-type [1 2 3])

              gm1 (tu/->mono-vec "gm1" types/int-type (map int [1 2 0]))
              k1 (tu/->mono-vec "k" types/bigint-type [4 5 6])]
    (let [agg-spec (-> (group-by/->aggregate-factory :array-agg "k" "vs")
                       (.build tu/*allocator*))]
      (try
        (.aggregate agg-spec (iv/->direct-vec k0) gm0)
        (.aggregate agg-spec (iv/->direct-vec k1) gm1)
        (t/is (= [[1 3 6] [2 4] [5]] (tu/<-column (.finish agg-spec))))
        (finally
          (util/try-close agg-spec))))))

(t/deftest test-bool-aggs
  (with-open [in-cursor (tu/->cursor (Schema. [(types/->field "k" types/varchar-type false)
                                               (types/->field "v" types/bool-type true)])
                                     [[{:k "t", :v true} {:k "f", :v false} {:k "n", :v nil}
                                       {:k "t", :v true} {:k "f", :v false} {:k "n", :v nil}
                                       {:k "tn", :v true} {:k "tn", :v nil} {:k "tn", :v true}
                                       {:k "fn", :v false} {:k "fn", :v nil} {:k "fn", :v false}
                                       {:k "tf", :v true} {:k "tf", :v false} {:k "tf", :v true}
                                       {:k "tfn", :v true} {:k "tfn", :v false} {:k "tfn", :v nil}]])
              group-by-cursor (group-by/->group-by-cursor tu/*allocator* in-cursor
                                                          ["k"]
                                                          [(group-by/->aggregate-factory :all "v" "all-vs")
                                                           (group-by/->aggregate-factory :any "v" "any-vs")])]
    (t/is (= #{{:k "t", :all-vs true, :any-vs true}
               {:k "f", :all-vs false, :any-vs false}
               {:k "n", :all-vs nil, :any-vs nil}
               {:k "fn", :all-vs false, :any-vs nil}
               {:k "tn", :all-vs nil, :any-vs true}
               {:k "tf", :all-vs false, :any-vs true}
               {:k "tfn", :all-vs false, :any-vs true}}
             (set (first (tu/<-cursor group-by-cursor)))))))

(t/deftest test-distinct
  (with-open [in-cursor (tu/->cursor (Schema. [(types/->field "k" types/keyword-type false)
                                               (types/->field "v" types/bigint-type true)])
                                     [[{:k :a, :v 10}
                                       {:k :b, :v 12}
                                       {:k :b, :v 15}
                                       {:k :b, :v 15}
                                       {:k :b, :v 10}]
                                      [{:k :a, :v 12}
                                       {:k :a, :v 10}]])
              group-by-cursor (group-by/->group-by-cursor tu/*allocator* in-cursor
                                                          ["k"]
                                                          [(group-by/->aggregate-factory :count "v" "cnt")
                                                           (group-by/->aggregate-factory :count-distinct "v" "cnt-distinct")
                                                           (group-by/->aggregate-factory :sum "v" "sum")
                                                           (group-by/->aggregate-factory :sum-distinct "v" "sum-distinct")
                                                           (group-by/->aggregate-factory :avg "v" "avg")
                                                           (group-by/->aggregate-factory :avg-distinct "v" "avg-distinct")
                                                           (group-by/->aggregate-factory :array-agg "v" "array-agg")
                                                           (group-by/->aggregate-factory :array-agg-distinct "v" "array-agg-distinct")])]
    (t/is (= #{{:k :a,
                :cnt 3, :cnt-distinct 2,
                :sum 32, :sum-distinct 22,
                :avg 10.666666666666666, :avg-distinct 11.0
                :array-agg [10 12 10], :array-agg-distinct [10 12]}
               {:k :b,
                :cnt 4, :cnt-distinct 3,
                :sum 52, :sum-distinct 37,
                :avg 13.0, :avg-distinct 12.333333333333334
                :array-agg [12 15 15 10], :array-agg-distinct [12 15 10]}}
             (set (first (tu/<-cursor group-by-cursor)))))))