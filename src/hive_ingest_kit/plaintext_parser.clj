(ns hive-ingest-kit.plaintext-parser
  "IWebsiteParser implementations for text/plain responses.

   PlainTextParser preserves line structure verbatim. RfcTextParser strips
   RFC page furniture and extracts masthead fields, section headings and
   RFC cross-references."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-html.page :refer [IWebsiteParser]]
            [hive-ingest-kit.rfc-structure :as rfc]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defrecord PlainTextParser []
  IWebsiteParser
  (parse-website-page [_ body {:keys [url]}]
    (if (str/blank? body)
      (r/err :source/empty-body {:url url})
      (r/try-effect* :source/parse-failed
                     {:url           url
                      :canonical-url nil
                      :title         (or (some-> (first (remove str/blank? (str/split-lines body)))
                                                 str/trim
                                                 not-empty)
                                         url)
                      :description   nil
                      :content       (str/trim body)
                      :headings      []
                      :links         []}))))

(defrecord RfcTextParser []
  IWebsiteParser
  (parse-website-page [_ body {:keys [url]}]
    (if (str/blank? body)
      (r/err :source/empty-body {:url url})
      (r/try-effect* :source/parse-failed
                     (let [content  (rfc/depaginate body)
                           lines    (str/split-lines content)
                           masthead (or (first (rfc/preamble-blocks lines)) [])
                           front    (merge (rfc/masthead-fields masthead)
                                           (rfc/masthead-authors masthead))
                           number   (:rfc/number front)]
                       (merge front
                              {:url           url
                               :canonical-url (when number
                                                (str "https://www.rfc-editor.org/info/rfc" number))
                               :title         (or (rfc/title lines) url)
                               :description   (rfc/abstract lines)
                               :content       content
                               :headings      (rfc/headings content)
                               :links         (rfc/references content number)
                               :supersedes    (rfc/superseded (:rfc/obsoletes front))}))))))

(defn plain-text-parser [] (->PlainTextParser))
(defn rfc-text-parser [] (->RfcTextParser))
