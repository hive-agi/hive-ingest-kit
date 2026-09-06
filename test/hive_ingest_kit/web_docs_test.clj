(ns hive-ingest-kit.web-docs-test
  "The `web-docs` source and body->document, driven with an injected HTTP fn,
   and its conformance to the hive-spi.ingest TCK."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-html.page :as page]
            [hive-ingest-kit.web-docs :as web]
            [hive-spi.ingest.ports :as ports]
            [hive-spi.ingest.tck :as tck]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private sample-rfc
  (slurp (io/resource "hive_ingest_kit/fixtures/sample-rfc.txt")))

(def ^:private rfc-url "https://www.rfc-editor.org/rfc/rfc9999.txt")

(defn- stub-http-get
  "An http-get returning BODY under CONTENT-TYPE for every URL."
  [body content-type]
  (fn [_url _opts]
    {:status 200 :headers {"content-type" content-type} :body body}))

(defn- mock-http-get
  "An http-get serving two docs pages and 404 for anything else."
  [url _opts]
  (case url
    "https://docs.example.com/nmap"
    {:status 200
     :headers {"content-type" "text/html"}
     :body "<html><head><title>Nmap Reference</title></head><body><h1>Nmap</h1><p>Network scanner.</p></body></html>"}

    "https://docs.example.com/curl"
    {:status 200
     :headers {"content-type" "text/html"}
     :body "<html><head><title>Curl Manual</title></head><body><h1>Curl</h1><p>Transfer data with URLs.</p></body></html>"}

    (throw (ex-info "Not found" {:status 404 :url url}))))

(defn- fetch-one
  [url http-get]
  (let [result (ports/fetch-documents (web/web-doc-source [url]) {:http-get-fn http-get})]
    (is (r/ok? result))
    (first (:ok result))))

;; =============================================================================
;; Routing by response shape
;; =============================================================================

(deftest rfc-response-produces-a-spec-document
  (let [doc (fetch-one rfc-url (stub-http-get sample-rfc "text/plain;charset=utf-8"))
        md  (:document/metadata doc)]
    (testing "format and content kind come from the profile, not a hardcode"
      (is (= :format/text (:adt/variant (:document/format doc))))
      (is (= :content/spec (:content-kind md))))
    (testing "front matter reaches the document metadata"
      (is (= "Example Protocol for Testing" (:title md)))
      (is (= "RFC 9999" (:tool-name md)))
      (is (= "9999" (:rfc/number md)))
      (is (= "Standards Track" (:rfc/category md)))
      (is (= "9998" (:rfc/obsoletes md)))
      (is (= "https://www.rfc-editor.org/info/rfc9999" (:canonical-url md)))
      (is (str/starts-with? (:description md) "This protocol does nothing")))
    (testing "cross-references become links"
      (is (= ["RFC 1945" "RFC 2616"] (mapv :text (:links md)))))))

(deftest plaintext-line-structure-survives
  (let [doc (fetch-one rfc-url (stub-http-get sample-rfc "text/plain"))
        content (:document/content doc)]
    (testing "newlines and indentation are preserved"
      (is (> (count (str/split-lines content)) 20))
      (is (str/includes? content "       Indented syntax block.")))
    (testing "page furniture is stripped"
      (is (not (str/includes? content "[Page 1]"))))))

(deftest non-rfc-plaintext-is-a-reference-document
  (let [doc (fetch-one "https://example.com/notes.txt"
                       (stub-http-get "First line\n\nSecond line" "text/plain"))
        md  (:document/metadata doc)]
    (is (= :format/text (:adt/variant (:document/format doc))))
    (is (= :content/reference (:content-kind md)))
    (is (= "First line" (:title md)))
    (is (= "notes" (:tool-name md)))
    (is (= "First line\n\nSecond line" (:document/content doc)))))

(deftest html-responses-select-the-html-profile
  (let [doc (fetch-one "https://docs.example.com/nmap"
                       (stub-http-get "<html><head><title>Nmap Docs</title></head><body><h1>Nmap</h1><p>Scanner.</p></body></html>"
                                      "text/html; charset=utf-8"))
        md  (:document/metadata doc)]
    (is (= :format/html (:adt/variant (:document/format doc))))
    (is (= :content/tool-docs (:content-kind md)))
    (is (= "Nmap Docs" (:title md)))
    (is (= "nmap" (:tool-name md)) "the URL slug is the last path segment")))

(deftest an-explicit-parser-still-overrides-the-profile
  (let [parser (reify page/IWebsiteParser
                 (parse-website-page [_ _body {:keys [url]}]
                   (r/ok {:url url :title "Injected" :description nil
                          :canonical-url nil :content "Injected content"
                          :headings [] :links []})))
        result (ports/fetch-documents (web/web-doc-source [rfc-url] parser)
                                      {:http-get-fn (stub-http-get sample-rfc "text/plain")})
        doc    (first (:ok result))]
    (is (= "Injected content" (:document/content doc)))
    (is (= "Injected" (get-in doc [:document/metadata :title])))
    (testing "the profile still classifies the response"
      (is (= :content/spec (get-in doc [:document/metadata :content-kind]))))))

;; =============================================================================
;; body->document, the transport-free seam
;; =============================================================================

(deftest body-to-document-needs-no-transport
  (let [result (web/body->document sample-rfc {:url rfc-url :content-type "text/plain"} {})]
    (is (r/ok? result))
    (is (= rfc-url (:document/source (:ok result))))
    (is (= :content/spec (get-in (:ok result) [:document/metadata :content-kind]))))
  (testing "a blank body is an error, not an exception"
    (is (r/err? (web/body->document "" {:url "https://x/y" :content-type "text/plain"} {})))))

;; =============================================================================
;; The source over HTTP
;; =============================================================================

(deftest web-source-propagates-website-metadata
  (let [http-get (fn [_url _opts]
                   {:status 200
                    :body "<html><head><title>API Guide</title><meta name=\"description\" content=\"Reference docs\"><link rel=\"canonical\" href=\"https://docs.example.com/api\"></head><body><nav>Skip me</nav><main><h1>API</h1><p>Use tokens.</p><a href=\"/auth\">Auth guide</a></main></body></html>"})
        result (ports/fetch-documents (web/web-doc-source ["https://docs.example.com/api"])
                                      {:http-get-fn http-get})]
    (is (r/ok? result))
    (let [doc (first (:ok result))
          md  (:document/metadata doc)]
      (is (= "API Guide" (:title md)))
      (is (= "Reference docs" (:description md)))
      (is (= "https://docs.example.com/api" (:canonical-url md)))
      (is (= [{:level 1 :text "API"}] (:headings md)))
      (is (= [{:href "/auth" :text "Auth guide" :in-content? true}] (:links md)))
      (is (str/includes? (:document/content doc) "Use tokens."))
      (is (not (str/includes? (:document/content doc) "Skip me"))))))

(deftest web-source-id
  (is (= "web-docs" (ports/source-id (web/web-doc-source)))))

(deftest web-fetch-with-mock
  (let [source (web/web-doc-source ["https://docs.example.com/nmap"
                                    "https://docs.example.com/curl"])
        result (ports/fetch-documents source {:http-get-fn mock-http-get})]
    (is (r/ok? result))
    (let [docs (:ok result)]
      (is (= 2 (count docs)))
      (is (every? #(= :content/tool-docs (get-in % [:document/metadata :content-kind])) docs))
      (is (some #(str/includes? (:document/content %) "Nmap") docs))
      (is (some #(str/includes? (:document/content %) "Curl") docs)))))

(deftest web-fetch-skips-urls-that-fail
  (let [source (web/web-doc-source ["https://docs.example.com/nmap"
                                    "https://does-not-exist.example.com/x"])
        result (ports/fetch-documents source {:http-get-fn mock-http-get})]
    (is (r/ok? result))
    (is (= 1 (count (:ok result))))))

(deftest web-fetch-with-no-urls-is-an-error
  (is (r/err? (ports/fetch-documents (web/web-doc-source) {:http-get-fn mock-http-get}))))

(deftest web-fetch-honours-limit
  (let [source (web/web-doc-source ["https://docs.example.com/nmap"
                                    "https://docs.example.com/curl"])
        result (ports/fetch-documents source {:http-get-fn mock-http-get :limit 1})]
    (is (= 1 (count (:ok result))))))

(deftest web-health-reports-a-known-status
  (is (#{:ok :degraded} (:status (ports/source-health (web/web-doc-source))))))

;; =============================================================================
;; Conformance
;; =============================================================================

(deftest web-docs-conforms-to-the-ingest-tck
  (let [source   (web/web-doc-source ["https://docs.example.com/nmap"
                                      "https://docs.example.com/curl"])
        fixtures {:opts       {:http-get-fn mock-http-get}
                  :limit-opts {:http-get-fn mock-http-get :limit 1}}
        report   (tck/conform source fixtures)]
    (is (:ok report) (tck/explain source fixtures))
    (is (= #{:rung/descriptor :rung/behaviour} (:rungs-measured report)))
    (is (empty? (:skips report)) (pr-str (:skips report)))))
