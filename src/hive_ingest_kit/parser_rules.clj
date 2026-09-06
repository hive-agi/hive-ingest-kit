(ns hive-ingest-kit.parser-rules
  "Ordered rule chain selecting a parse profile for a fetched response.

   A profile is data: {:profile/id :profile/format :profile/content-kind
   :profile/parser}. Adding a response shape means appending a rule, not
   editing the folder. The port is hive-spi.ingest.ports/IParserRule; rules a
   provider registered through hive-spi.ingest.registry run before the
   built-in chain."
  (:require [clojure.string :as str]
            [hive-html.page :as page]
            [hive-ingest-kit.plaintext-parser :as plaintext]
            [hive-spi.ingest.model :as model]
            [hive-spi.ingest.ports :as ports]
            [hive-spi.ingest.registry :as registry]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Response predicates
;; =============================================================================

(defn- mime-type
  "The bare MIME type of a Content-Type header, lowercased."
  [content-type]
  (some-> content-type str/lower-case (str/split #";") first str/trim))

(defn plain-text?
  "True when the response is text/plain, or, absent a Content-Type, the URL
   carries a plaintext suffix."
  [{:keys [url content-type]}]
  (if-let [mime (mime-type content-type)]
    (= "text/plain" mime)
    (boolean (re-find #"(?i)\.(txt|text)(\?.*)?$" (str url)))))

(defn rfc-document?
  "True when the response body or URL identifies an IETF RFC."
  [{:keys [url body]}]
  (boolean
   (or (re-find #"(?i)rfc-?editor\.org/rfc/rfc\d+" (str url))
       (re-find #"(?im)^Request for Comments:\s*\d+" (subs (str body) 0 (min 4000 (count (str body))))))))

(defn article-document?
  "True when the response advertises itself as a long-form article: an OpenGraph
   og:type of `article`, or an <article> element. Both live in the first pages of
   markup, so the scan is bounded rather than reading the whole body."
  [{:keys [body]}]
  (let [head (subs (str body) 0 (min 12000 (count (str body))))]
    (boolean
     (or (re-find #"(?i)og:type[^>]*['\"]article['\"]" head)
         (re-find #"(?i)['\"]article['\"][^>]*og:type" head)
         (re-find #"(?i)<article[\s>]" head)))))

;; =============================================================================
;; Rules
;; =============================================================================

(defrecord RfcTextRule []
  ports/IParserRule
  (rule-id [_] :parser-rule/rfc-text)
  (rule-applies? [_ ctx] (and (plain-text? ctx) (rfc-document? ctx)))
  (rule-profile [this _]
    {:profile/id           (ports/rule-id this)
     :profile/format       (model/document-format :format/text)
     :profile/content-kind :content/spec
     :profile/parser       (plaintext/rfc-text-parser)}))

(defrecord PlainTextRule []
  ports/IParserRule
  (rule-id [_] :parser-rule/plain-text)
  (rule-applies? [_ ctx] (plain-text? ctx))
  (rule-profile [this _]
    {:profile/id           (ports/rule-id this)
     :profile/format       (model/document-format :format/text)
     :profile/content-kind :content/reference
     :profile/parser       (plaintext/plain-text-parser)}))

(defrecord ArticleRule []
  ports/IParserRule
  (rule-id [_] :parser-rule/article)
  (rule-applies? [_ ctx] (article-document? ctx))
  (rule-profile [this _]
    {:profile/id           (ports/rule-id this)
     :profile/format       (model/document-format :format/html)
     :profile/content-kind :content/article
     :profile/parser       (page/default-website-parser)}))

(defrecord HtmlRule []
  ports/IParserRule
  (rule-id [_] :parser-rule/html)
  (rule-applies? [_ _] true)
  (rule-profile [this _]
    {:profile/id           (ports/rule-id this)
     :profile/format       (model/document-format :format/html)
     :profile/content-kind :content/tool-docs
     :profile/parser       (page/default-website-parser)}))

(def default-rules
  "Rules in precedence order. The last rule matches unconditionally."
  [(->RfcTextRule)
   (->PlainTextRule)
   (->ArticleRule)
   (->HtmlRule)])

;; =============================================================================
;; Selection
;; =============================================================================

(defn select-profile
  "Return the profile of the first rule claiming ctx.

   Rules a provider registered are consulted BEFORE the built-in chain, so a
   corpus can claim a response shape the chain would otherwise hand to a
   generic parser. Falls back to the HTML profile when no rule matches."
  ([ctx] (select-profile ctx (concat (registry/registered-rules)
                                     default-rules)))
  ([ctx rules]
   (or (some (fn [rule]
               (when (ports/rule-applies? rule ctx)
                 (ports/rule-profile rule ctx)))
             rules)
       (ports/rule-profile (->HtmlRule) ctx))))
