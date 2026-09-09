(ns kami_sabiotoshi.murakumo-test
  "Contract tests for the manifest-migration-scaffold cljc actor boundary
  (kami_sabiotoshi.murakumo).

  These introspect `cell-specs` rather than hardcoding this actor's 13 cell
  names, so they keep holding when the manifest adds or drops a cell -- the
  suite is about the boundary's contract, not about today's catalogue.

  The shape is shared with kotoba-lang/actor-bunken's suite, which covers the
  same generated scaffold; ported rather than rewritten, and extended here with
  the `safe-rkey` cases that repo does not have.

  Two things this suite deliberately asserts, both from CLAUDE.md's 8 questions:

    * BOTH directions of the gate. `cell-plan` is the actor's fail-closed
      boundary: unattested gates must yield :blocked with NO effects. A suite
      that only proved the :ready path would stay green if the gate were
      deleted. `cell-plan-blocks-when-gates-missing` and
      `cell-plan-ready-when-gates-satisfied` are that pair.
    * The REASON, not just the verdict. The blocked test asserts that
      :missing-gates equals the spec's :required-gates, so a plan that came
      back blocked for some unrelated reason does not count as the gate
      having discriminated."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kami_sabiotoshi.murakumo :as m]))

(def full-attestations
  (into {}
        (map (fn [gate] [gate (str "attested-" (name gate))]))
        (distinct (mapcat :required-gates (vals m/cell-specs)))))

(deftest cell-specs-is-not-empty
  ;; Evidence floor: every other test below loops over cell-specs, so an empty
  ;; map would make all of them vacuously pass. Nothing here may report a pass
  ;; without having had something to look at.
  (is (pos? (count m/cell-specs)))
  (is (every? seq (map :required-gates (vals m/cell-specs))))
  (is (every? seq (map :collections (vals m/cell-specs)))))

(deftest gate-value-handles-map-and-set-attestations
  (testing "map attestations, keyword key"
    (is (= "yes" (m/gate-value {:g "yes"} :g))))
  (testing "map attestations, string key fallback"
    (is (= "yes" (m/gate-value {"g" "yes"} :g))))
  (testing "set attestations, keyword member"
    (is (= :g (m/gate-value #{:g} :g))))
  (testing "set attestations, string member fallback"
    (is (= "g" (m/gate-value #{"g"} :g))))
  (testing "missing gate returns nil"
    (is (nil? (m/gate-value {} :g)))))

(deftest missing-gates-computes-the-diff
  (let [some-spec (first (vals m/cell-specs))
        all-gates (:required-gates some-spec)]
    (testing "no attestations -> every required gate is missing"
      (is (= all-gates (m/missing-gates some-spec {}))))
    (testing "all attested -> nothing missing"
      (is (empty? (m/missing-gates some-spec full-attestations))))
    (testing "partially attested -> only the unattested gate is missing"
      (let [partial (dissoc full-attestations (first all-gates))]
        (is (= [(first all-gates)] (m/missing-gates some-spec partial)))))))

(deftest safe-rkey-normalises-and-never-returns-blank
  (testing "strips the did:web: prefix"
    (is (= "kami-sabiotoshi.etzhayyim.com" (m/safe-rkey "did:web:kami-sabiotoshi.etzhayyim.com"))))
  (testing "keeps the unreserved set intact"
    (is (= "aZ0._~-" (m/safe-rkey "aZ0._~-"))))
  (testing "replaces every disallowed character"
    (is (= "a-b-c-d" (m/safe-rkey "a/b:c d"))))
  (testing "boundary: input that normalises to empty becomes \"unknown\", not \"\""
    ;; The line itself -- str/blank? on the cleaned string. An empty rkey would
    ;; be written to the MST as a record key, so the fallback is load-bearing.
    (is (= "unknown" (m/safe-rkey "")))
    (is (= "unknown" (m/safe-rkey "did:web:"))))
  (testing "boundary: whitespace-only is disallowed chars, so it is NOT blank after cleaning"
    ;; " " -> "-", which is not blank; documents that the blank? branch is
    ;; reached only by genuinely empty input, not by whitespace.
    (is (= "-" (m/safe-rkey " "))))
  (testing "non-string input is coerced, not thrown on"
    (is (string? (m/safe-rkey 42)))
    (is (= "unknown" (m/safe-rkey nil)))))

(deftest put-record-effect-shape
  (let [effect (m/put-record-effect "com.example.coll" "rk-1" {:a 1})]
    (is (= :mst/put-record (:op effect)))
    (is (= m/actor-did (:actor effect)))
    (is (= "com.example.coll" (:collection effect)))
    (is (= "rk-1" (:rkey effect)))
    (is (= {:a 1} (:record effect)))))

(deftest collections-are-namespaced-under-this-actor
  (doseq [[cell-key spec] m/cell-specs]
    (doseq [coll (:collections spec)]
      (is (str/starts-with? coll "com.etzhayyim.kami-sabiotoshi.")
          (str cell-key ": collection stays inside this actor's boundary")))))

(deftest records-for-produces-one-record-per-collection
  (doseq [[cell-key spec] m/cell-specs]
    (let [recs (m/records-for spec {:request-id (str "req-" (name cell-key))})]
      (is (= (count (:collections spec)) (count recs))
          (str cell-key ": one record per declared collection"))
      (doseq [{:keys [collection record rkey]} recs]
        (is (contains? (set (:collections spec)) collection))
        (is (= m/actor-did (:actorDid record)))
        (is (true? (:scaffold record)))
        (is (= (:legacy-cell spec) (:legacyCell record)))
        (is (string? rkey))
        (is (not (str/blank? rkey)))))))

(deftest records-for-honors-explicit-record-override
  (let [[_ spec] (first (filter (fn [[_ s]] (= 1 (count (:collections s))))
                                m/cell-specs))]
    (is (some? spec) "this actor declares at least one single-collection cell")
    (let [recs (m/records-for spec {:record {:rkey "custom-rk" :note "override"}})]
      (is (= "custom-rk" (:rkey (first recs))))
      (is (= "override" (:note (:record (first recs))))))))

(deftest cell-plan-blocks-when-gates-missing
  (doseq [cell-key (keys m/cell-specs)]
    (let [plan (m/cell-plan cell-key {})]
      (is (= :blocked (:status plan)) (str cell-key ": unattested must not be ready"))
      (is (empty? (:effects plan)) (str cell-key ": a blocked plan emits no effects"))
      (is (nil? (:records plan)) (str cell-key ": a blocked plan plans no records"))
      ;; the reason, not just the verdict
      (is (= (get-in m/cell-specs [cell-key :required-gates]) (:missing-gates plan))
          (str cell-key ": blocked for exactly the gates it names")))))

(deftest cell-plan-ready-when-gates-satisfied
  (doseq [cell-key (keys m/cell-specs)]
    (let [plan (m/cell-plan cell-key {:attestations full-attestations :request-id "req-1"})]
      (is (= :ready (:status plan)) (str cell-key ": fully attested must be ready"))
      (is (empty? (:missing-gates plan)))
      (is (= (count (get-in m/cell-specs [cell-key :collections]))
             (count (:effects plan)))
          (str cell-key ": one effect per collection"))
      (is (every? #(= :mst/put-record (:op %)) (:effects plan))))))

(deftest cell-plan-blocks-when-even-one-gate-is-missing
  ;; Boundary between the two tests above: the gate is an ALL, not an ANY.
  (let [cell-key (first (keys m/cell-specs))
        gates (get-in m/cell-specs [cell-key :required-gates])
        one-short (dissoc full-attestations (last gates))
        plan (m/cell-plan cell-key {:attestations one-short :request-id "req-1"})]
    (is (= :blocked (:status plan)))
    (is (= [(last gates)] (:missing-gates plan)))
    (is (empty? (:effects plan)))))

(deftest cell-plan-throws-on-unknown-cell
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (m/cell-plan :totally-not-a-real-cell {}))))

(deftest all-cell-plans-covers-every-cell
  (let [plans (m/all-cell-plans {:attestations full-attestations :request-id "req-1"})]
    (is (= (set (keys m/cell-specs)) (set (keys plans))))
    (is (every? #(= :ready (:status %)) (vals plans))))
  (testing "and blocks every cell when nothing is attested"
    (let [plans (m/all-cell-plans {})]
      (is (= (set (keys m/cell-specs)) (set (keys plans))))
      (is (every? #(= :blocked (:status %)) (vals plans))))))
