(ns hive-ingest-kit.parser-rules-test
  "Parse-profile selection.

   Covers the classification table, rule ORDER, the OCP extension contract
   (a new response shape is a new rule), the registry precedence (a rule a
   provider registered runs first) and the LSP contract (every rule yields a
   profile whose parser satisfies IWebsiteParser)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-dsl.result :as r]
            [hive-html.page :as page]
            [hive-ingest-kit.parser-rules :as rules]
            [hive-spi.ingest.ports :as ports]
            [hive-spi.ingest.registry :as registry]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private owner "test.owner")

(use-fixtures :each (fn [f]
                      (registry/retract-all! owner)
                      (f)
                      (registry/retract-all! owner)))

(def ^:private rfc-body
  "Network Working Group                                          A. Author\nRequest for Comments: 9999                                  Example Corp\n\n                    Example Protocol\n")

(def ^:private article-html
  (slurp (io/resource "hive_html/fixtures/article.html")))

;; =============================================================================
;; Classification table
;; =============================================================================

(deftest profile-classification
  (testing "text/plain RFC bodies route to the RFC profile"
    (doseq [ctx [{:url "https://www.rfc-editor.org/rfc/rfc2616.txt"
                  :content-type "text/plain;charset=utf-8"
                  :body rfc-body}
                 {:url "https://example.com/spec" ; no suffix, body identifies it
                  :content-type "text/plain"
                  :body rfc-body}]]
      (let [profile (rules/select-profile ctx)]
        (is (= :parser-rule/rfc-text (:profile/id profile)) (pr-str ctx))
        (is (= :format/text (:adt/variant (:profile/format profile))))
        (is (= :content/spec (:profile/content-kind profile))))))

  (testing "other text/plain bodies route to the plaintext profile"
    (let [profile (rules/select-profile {:url "https://example.com/notes.txt"
                                         :content-type "text/plain"
                                         :body "just some notes"})]
      (is (= :parser-rule/plain-text (:profile/id profile)))
      (is (= :format/text (:adt/variant (:profile/format profile))))
      (is (= :content/reference (:profile/content-kind profile)))))

  (testing "html falls through to the html profile"
    (doseq [ctx [{:url "https://docs.example.com/nmap"
                  :content-type "text/html; charset=utf-8"
                  :body "<html><body>hi</body></html>"}
                 {:url "https://docs.example.com/nmap" ; no Content-Type at all
                  :body "<html><body>hi</body></html>"}]]
      (let [profile (rules/select-profile ctx)]
        (is (= :parser-rule/html (:profile/id profile)) (pr-str ctx))
        (is (= :format/html (:adt/variant (:profile/format profile))))
        (is (= :content/tool-docs (:profile/content-kind profile)))))))

(deftest content-type-outranks-the-url-suffix
  (testing "a .txt URL served as html is html"
    (is (= :parser-rule/html
           (:profile/id (rules/select-profile {:url "https://example.com/trap.txt"
                                               :content-type "text/html"
                                               :body "<html></html>"})))))
  (testing "the suffix decides only when no Content-Type is present"
    (is (= :parser-rule/plain-text
           (:profile/id (rules/select-profile {:url "https://example.com/notes.txt"
                                               :body "notes"}))))))

(deftest og-type-article-selects-the-article-profile
  (let [profile (rules/select-profile {:url          "https://martinfowler.com/articles/x"
                                       :content-type "text/html"
                                       :body         article-html})]
    (is (= :parser-rule/article (:profile/id profile)))
    (is (= :content/article (:profile/content-kind profile))))
  (testing "an <article> element classifies the same way"
    (is (= :content/article
           (:profile/content-kind
            (rules/select-profile {:url "https://example.com/post"
                                   :content-type "text/html"
                                   :body "<html><body><article><p>hi</p></article></body></html>"})))))
  (testing "an ordinary docs page is still tool-docs"
    (is (= :content/tool-docs
           (:profile/content-kind
            (rules/select-profile {:url "https://docs.example.com/nmap"
                                   :content-type "text/html"
                                   :body "<html><body><p>usage</p></body></html>"}))))))

;; =============================================================================
;; Rule order
;; =============================================================================

(deftest rfc-rule-precedes-the-generic-plaintext-rule
  (is (= [:parser-rule/rfc-text :parser-rule/plain-text
          :parser-rule/article :parser-rule/html]
         (mapv ports/rule-id rules/default-rules)))
  (testing "both plaintext rules claim an RFC; the RFC rule wins by order"
    (let [ctx {:url "https://www.rfc-editor.org/rfc/rfc2616.txt"
               :content-type "text/plain"
               :body rfc-body}]
      (is (every? #(ports/rule-applies? % ctx) (take 2 rules/default-rules)))
      (is (= :parser-rule/rfc-text (:profile/id (rules/select-profile ctx)))))))

(deftest the-last-rule-is-total
  (let [html-rule (last rules/default-rules)]
    (is (ports/rule-applies? html-rule {}))
    (is (ports/rule-applies? html-rule {:url nil :content-type nil :body nil}))))

;; =============================================================================
;; OCP: a new response shape is a new rule, not an edit
;; =============================================================================

(defrecord StubJsonRule []
  ports/IParserRule
  (rule-id [_] :parser-rule/stub-json)
  (rule-applies? [_ ctx] (= "application/json" (:content-type ctx)))
  (rule-profile [_ _] {:profile/id           :parser-rule/stub-json
                       :profile/format       nil
                       :profile/content-kind :content/reference
                       :profile/parser       nil}))

(deftest a-prepended-rule-extends-the-chain
  (let [chain (into [(->StubJsonRule)] rules/default-rules)
        ctx   {:url "https://api.example.com/spec" :content-type "application/json" :body "{}"}]
    (is (= :parser-rule/stub-json (:profile/id (rules/select-profile ctx chain))))
    (testing "unclaimed responses still fall through the stock chain"
      (is (= :parser-rule/html
             (:profile/id (rules/select-profile {:url "https://x.example/y"
                                                 :content-type "text/html"
                                                 :body "<html></html>"}
                                                chain)))))))

(deftest an-empty-chain-falls-back-to-html
  (is (= :parser-rule/html (:profile/id (rules/select-profile {:body "x"} [])))))

;; =============================================================================
;; Registry precedence: a provider's rule runs before the built-in chain
;; =============================================================================

(defrecord ClaimEverythingRule [profile]
  ports/IParserRule
  (rule-id [_] :test/claim-everything)
  (rule-applies? [_ _] true)
  (rule-profile [_ _] profile))

(deftest a-registered-rule-is-consulted-before-the-built-in-chain
  (let [profile {:profile/id :test/claimed}]
    (testing "without it, the built-in chain decides"
      (is (= :parser-rule/html (:profile/id (rules/select-profile {:url "https://x/y"})))))
    (registry/register-parser-rule! owner :test/claim-everything
                                    {:rule (->ClaimEverythingRule profile)})
    (testing "with it, the provider's rule wins"
      (is (= :test/claimed (:profile/id (rules/select-profile {:url "https://x/y"})))))
    (testing "and retracting hands the decision back"
      (registry/retract-all! owner)
      (is (= :parser-rule/html (:profile/id (rules/select-profile {:url "https://x/y"})))))))

;; =============================================================================
;; LSP: every stock profile's parser honours the port
;; =============================================================================

(deftest every-profile-parser-satisfies-the-port
  (doseq [rule rules/default-rules
          :let [ctx     {:url "https://example.com/doc" :body rfc-body}
                profile (ports/rule-profile rule ctx)
                parser  (:profile/parser profile)]]
    (is (satisfies? page/IWebsiteParser parser) (str (ports/rule-id rule)))
    (testing "parsing a body returns a Result carrying the page keys"
      (let [result (page/parse-website-page parser rfc-body ctx)]
        (is (r/ok? result) (str (ports/rule-id rule)))
        (is (every? #(contains? (:ok result) %)
                    [:url :title :content :headings :links])
            (str (ports/rule-id rule)))))
    (testing "a blank body is an error, not an exception"
      (is (r/err? (page/parse-website-page parser "" ctx))))))
