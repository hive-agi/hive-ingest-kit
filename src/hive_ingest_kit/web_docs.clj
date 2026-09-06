(ns hive-ingest-kit.web-docs
  "A fetched or read body as a Document, and the `web-docs` ISource over HTTP.

   `body->document` is the seam every transport shares: the rule chain, the
   parser and the document shape do not care whether the bytes arrived over
   HTTP or off a local mirror, so a new transport is a new caller here."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-html.page :as page]
            [hive-ingest-kit.parser-rules :as rules]
            [hive-spi.ingest.model :as model]
            [hive-spi.ingest.ports :refer [ISource ISourceHealth]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; HTTP + extraction
;; =============================================================================

(defn- resolve-http-get
  "clj-http.client/get when it is on the classpath, else nil. Absence is an
   answer here, never a throw: source-health reports it as :degraded."
  []
  (try
    (when-let [v (requiring-resolve 'clj-http.client/get)]
      @v)
    (catch Throwable _ nil)))

(defprotocol IWebsiteFetcher
  (fetch-html [this url]))

(defrecord HttpWebsiteFetcher [http-get-fn]
  IWebsiteFetcher
  (fetch-html [_ url]
    (r/try-effect* :source/fetch-failed
                   (http-get-fn url {:as                 :string
                                     :socket-timeout     10000
                                     :connection-timeout 10000
                                     :headers            {"User-Agent" "hive-ingest-kit/0.1"}}))))

(defn- response-content-type
  "The Content-Type header of a response, whatever case or key type it uses."
  [resp]
  (let [headers (:headers resp)]
    (or (get headers "content-type")
        (get headers "Content-Type")
        (get headers :content-type))))

(defn- url-slug
  "Last path segment of a URL with any file extension stripped, or nil."
  [url]
  (some-> (str url)
          (str/replace #"[?#].*$" "")
          (str/replace #"/+$" "")
          (as-> s (re-find #"[^/]+$" s))
          (str/replace #"\.[A-Za-z0-9]{1,5}$" "")
          not-empty))

(def ^:private rfc-metadata-keys
  [:rfc/number :rfc/category :rfc/obsoletes :rfc/updates :rfc/authors :rfc/date])

(defn- page->document
  "Build a Document value object from a parsed page. Returns Result<Document>."
  [url page opts]
  (let [{:keys [content title description canonical-url headings links supersedes
                authors og-type published]} page]
    (model/make-document
     {:id      (str (java.util.UUID/randomUUID))
      :source  url
      :format  (:profile/format page)
      :content content
      :metadata (merge {:tool-name     (or (:tool-name opts)
                                           (when-let [n (:rfc/number page)] (str "RFC " n))
                                           (url-slug url)
                                           title)
                        :url           url
                        :canonical-url canonical-url
                        :title         title
                        :description   description
                        :headings      headings
                        :links         links
                        :authors       authors
                        :og-type       og-type
                        :published     published
                        :supersedes    supersedes
                        :content-kind  (:profile/content-kind page)
                        :source-type   "web-docs"}
                       (select-keys page rfc-metadata-keys))})))

(defn body->document
  "A fetched or read BODY as a Document, via the profile its shape selects.

   ctx is {:url :content-type}. An explicit :parser overrides the profile's;
   the profile still supplies :profile/format and :profile/content-kind.
   Returns Result<Document>."
  [body {:keys [url content-type parser] :as _ctx} opts]
  (let [rule-ctx {:url url :content-type content-type :body body}
        profile  (rules/select-profile rule-ctx)
        parser   (or parser (:profile/parser profile))]
    (r/let-ok [page (page/parse-website-page parser body rule-ctx)]
      (page->document url
                      (assoc page
                             :profile/format       (:profile/format profile)
                             :profile/content-kind (:profile/content-kind profile))
                      opts))))

(defn- fetch-url
  "Fetch URL and build its Document. Returns Result<Document>."
  [url fetcher parser-override opts]
  (r/let-ok [resp (fetch-html fetcher url)]
    (body->document (:body resp)
                    {:url          url
                     :content-type (response-content-type resp)
                     :parser       parser-override}
                    opts)))

;; =============================================================================
;; WebDocSource
;; =============================================================================

(defrecord WebDocSource [urls parser]
  ISource
  (source-id [_] "web-docs")

  (fetch-documents [_ opts]
    (let [target-urls (or (:urls opts) urls)
          target-urls (if (pos-int? (:limit opts))
                        (take (:limit opts) target-urls)
                        target-urls)
          http-get    (or (:http-get-fn opts) (resolve-http-get))
          parser      (or (:website-parser opts) parser)]
      (cond
        (empty? target-urls)
        (r/err :source/invalid-config {:reason "no URLs specified"})

        (nil? http-get)
        (r/err :source/unavailable {:reason "clj-http not available"})

        :else
        (let [fetcher (->HttpWebsiteFetcher http-get)]
          (r/ok
           (into []
                 (keep (fn [url]
                         (let [result (fetch-url url fetcher parser opts)]
                           (when (r/ok? result) (:ok result)))))
                 target-urls))))))

  ISourceHealth
  (source-health [_]
    (if (resolve-http-get)
      {:status :ok :details {:urls (count urls)}}
      {:status :degraded :details {:reason "clj-http not available"}})))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn web-doc-source
  "Create a WebDocSource for URLS.

   With no parser the response's shape selects one; an explicit parser is
   used for every URL. fetch-documents opts: :urls, :limit, :http-get-fn,
   :website-parser, :tool-name."
  ([] (web-doc-source [] nil))
  ([urls] (web-doc-source urls nil))
  ([urls parser] (->WebDocSource (vec urls) parser)))
