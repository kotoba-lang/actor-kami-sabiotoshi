# actor-kami-sabiotoshi

Governed resident actor for **Sabi-Otoshi!!** — the KAMI high-pressure wash
game where players blast rust off vintage items to restore them
(`sabiotoshi.kami.etzhayyim.com`). It owns the item catalog (`RustyItem`),
rust-zone scoring (`RustZone`), and the governed social pipelines around them.

Boundary (from `README.edn`): **no KAMI Engine ownership and no Tamaki
world-model authority.** Rendering lives in the `kami-engine-*` family; this
repo only plans records for the actor's own collections.

- Canonical repository: `kotoba-lang/actor-kami-sabiotoshi`
- Historical alias: `etzhayyim/com-etzhayyim-kami-sabiotoshi`
  (migrated from `etzhayyimcojp/20-actors`, 2026-05-21 — see `NOTICE`)
- Actor DID: `did:web:kami-sabiotoshi.etzhayyim.com` (`.well-known/did.json`)
- Family / role: `:kami` / `:governed-executable-actor`, execution `:resident`

## What is in here

| Path | Purpose |
|---|---|
| `actor-manifest.jsonld` | The actor manifest: DID, capabilities (`graph.query`, `graph.write`, `agent.chat`, `derive:social`), graph labels `RustyItem` / `RustZone`, and the xrpc-triggered pipelines (`com.etzhayyim.apps.kami.sabiotoshi.listItems`, `createItem`, `scoreZone`, …). |
| `src/kami_sabiotoshi/murakumo.cljk` | Pure actor boundary (`kami_sabiotoshi.murakumo`). `cell-specs` declares one cell per legacy pipeline; `cell-plan` turns a cell + attestations into either `{:status :blocked :effects []}` or `{:status :ready :effects [...]}` where every effect is an `:mst/put-record` into a `com.etzhayyim.kami-sabiotoshi.*` collection. |
| `test/kami_sabiotoshi/murakumo_test.cljk` | Contract suite for that boundary (see below). |
| `scripts/run-tests.cljk` | Runner for the suite. |
| `CLAUDE.md` | Agent instructions plus the historical KAMI project context (Guest Mode, world templates, KAMI Worlds) this actor was carved out of. |
| `cljk-origin.edn` | Which extension each `.cljk` file had before the 2026-09-11 rename. |

## The gate

Every cell requires the same `common-gates`
(`:council-charter-attestation`, `:no-platform-held-key-baseline`,
`:no-probing-baseline`, `:murakumo-only-inference-baseline`,
`:did-primary-baseline`, `:append-only-gate-baseline`,
`:kotoba-only-substrate-baseline`). `cell-plan` is fail-closed: a single
missing attestation yields `:blocked` with **no effects** and `:missing-gates`
naming exactly which gates were absent. Attestations may be a map (keyword or
string keys) or a set.

```clojure
(require '[kami_sabiotoshi.murakumo :as m])

(m/cell-plan :scorezone {})                      ; => {:status :blocked :effects [] :missing-gates [...] ...}
(m/cell-plan :scorezone {:attestations {...all gates...} :request-id "req-1"})
;; => {:status :ready :effects [{:op :mst/put-record :collection "com.etzhayyim.kami-sabiotoshi.scorezone" ...}]}
(m/all-cell-plans input)                         ; every cell, keyed by cell name
```

Record keys go through `safe-rkey`, which strips `did:web:`, replaces anything
outside `[A-Za-z0-9._~-]`, and returns `"unknown"` rather than an empty key.

## Tests

```
kbb --backend sci scripts/run-tests.cljk
```

The suite asserts both directions of the gate (blocked without attestations,
ready with all of them, blocked again when exactly one is removed) and pins the
*reason* (`:missing-gates` equals the spec's `:required-gates`), not just the
verdict. It introspects `cell-specs` instead of hardcoding cell names, with an
evidence-floor test that fails if `cell-specs` is empty.

Exit codes: `0` all tests passed · `1` a test failed · `2` the suite could not
run (zero tests collected, or `clojure.string` unreachable). `clojure.string`
resolves from the sibling west project `kotoba-lang/text`
(`$KOTOBA_TEXT_SRC`, `../text/src`, or `<superproject>/orgs/kotoba-lang/text/src`);
without it the runner prints `REFUSED` and exits 2 rather than reporting a pass.

## License

Apache License 2.0 with the etzhayyim Charter Compliance Rider v3.1 — see `NOTICE`.
