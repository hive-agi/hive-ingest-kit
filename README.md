# hive-ingest-kit

Shared helpers for corpus providers written against
[`hive-spi.ingest`](https://github.com/hive-agi/hive-spi). The kit depends on
the SPI and on [`hive-html`](https://github.com/hive-agi/hive-html); it never
depends on a pipeline. A provider that uses it stays buildable, testable and
publishable without access to any closed host.

## What is in it

| Namespace                        | What it gives a provider                                                                 |
|----------------------------------|------------------------------------------------------------------------------------------|
| `hive-ingest-kit.web-docs`       | `body->document`: a fetched or read body as a `Document`, via the profile its shape selects. Also the `web-docs` `ISource` over HTTP. |
| `hive-ingest-kit.parser-rules`   | The ordered rule chain that turns `{:url :content-type :body}` into a parse profile. Rules registered through `hive-spi.ingest.registry` run first. |
| `hive-ingest-kit.plaintext-parser` | The `IWebsiteParser` for `text/plain`: verbatim plaintext. |

RFC plaintext (masthead, headings, cross-references) is parsed by
[`hive-ingestor-rfc`](https://github.com/hive-agi/hive-ingestor-rfc), which
registers its own parse rule; `body->document` carries its `:rfc/*` keys into
the document metadata.

## The seam every transport shares

```clojure
(require '[hive-ingest-kit.web-docs :as web-docs])

(web-docs/body->document body
                         {:url "https://www.rfc-editor.org/rfc/rfc2616.txt"
                          :content-type "text/plain"}
                         {})
;; => {:ok {:document/id ... :document/source ... :document/format ...
;;          :document/content ... :document/metadata {...}}}
```

Whether the bytes came over HTTP, off an rsync mirror or out of a crawler, the
rule chain, the parser and the document shape are the same. A new transport is
a new caller of this function, not a second pipeline.

## Claiming a response shape

A profile is data: `{:profile/id :profile/format :profile/content-kind
:profile/parser}`. To claim a shape the stock chain would hand to a generic
parser, implement `hive-spi.ingest.ports/IParserRule` and register it:

```clojure
(require '[hive-spi.ingest.registry :as registry])

(registry/register-parser-rule! "my.provider" :my/rule {:rule my-rule :priority 50})
```

## License

MIT.
