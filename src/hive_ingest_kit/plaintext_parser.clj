(ns hive-ingest-kit.plaintext-parser
  "IWebsiteParser implementation for text/plain responses.

   PlainTextParser preserves line structure verbatim. The RFC text parser
   lives in hive-ingestor-rfc, which registers its own parse rule."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-html.page :refer [IWebsiteParser]]))

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

(defn plain-text-parser [] (->PlainTextParser))
