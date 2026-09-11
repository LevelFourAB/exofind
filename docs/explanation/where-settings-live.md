# Where a setting lives

This document explains where settings live across index definitions, search settings, and search requests, why Exofind separates configuration into these three locations, and how fast changes take effect across nodes.

## The three places

A setting that changes how an index answers lives in one of three places:

- **The index definition.** It says what the index holds and how a document is written into it: field types, usages, analysis chains, index-time synonym sets, and the default ranking. The definition travels with the index data, so a change to it goes through the node that writes the index, and other nodes see it on their next pull, within `EXOFIND_INDEXES_REFRESH_INTERVAL` (default `30s`).
- **The search settings.** They say how searches are answered: the ranking, query-time synonym sets, typo exclusions, and per-field search capabilities. They are stored as one object per index, so writing them creates no generation, changes no index definition, and does not move the definition version. A modifying request runs on the node that holds the index, and a `GET` is served by whichever node receives it. A change is in force on the holding node at once and reaches every other node within `EXOFIND_SETTINGS_REFRESH_INTERVAL` (default `10s`).
- **The search request.** It carries what belongs to one search: the clauses, field weight overrides, `fuse`, `rescore`, `sort`, and its own ranking signals. It reaches that search and nothing else.

## Why the split exists

The three change at different rates and are decided by different people. A field type is a schema decision. Tuning a weight is a daily one. A ranking signal about the person searching belongs to one request. A deployment that held all three in the definition would send every weight change through the writing node and make every caller wait a pull interval for it.

## Which one wins

While search settings hold a `ranking`, it replaces the definition's `ranking` completely. An empty `ranking` object turns ranking off. Deleting the search settings returns the index to the definition's ranking.

A search request's `signals` are added on top of whichever ranking is in force, and `signalsMode` set to `replace` drops that layer for the search. See [Relevance](relevance.md) for how these ranking layers interact.

## The speeds a change travels at

Changes take effect at different speeds depending on where the setting lives and how the engine processes it:

- **That search**: anything in the search request.
- **Within the settings refresh interval**: anything in the search settings. It is immediate on the node that took the change.
- **Within the index refresh interval**: a definition change that the engine accepts on a generation holding documents, such as `ranking`, `weight`, `typoTolerance`, `lengthNormalization`, sort `missing` placement, and the validation rules `required`, `min`, and `max`.
- **Documents indexed after the change**: anything written into the index as a document is indexed, such as an analysis chain, an index-time synonym set, and `exact`.
- **A new generation**: a definition change the engine refuses on a generation that holds documents.

## What the split costs

Separating settings across these locations introduces specific operational trade-offs:

- Nodes re-read on their own interval, so two nodes can answer one query differently for up to one refresh interval.
- Search settings belong to the index name and not to a generation, so promoting a generation keeps the tuning. Where a newer generation lacks a field the settings name, a search skips that entry instead of failing, so a promotion never waits on the settings being rewritten first.
- A node whose version lacks a capability the stored settings use sets the settings aside and searches with the definition alone. It reports this as `unsupportedFeatures` when the settings are read.

## Where a search capability is turned on

Search settings hold a `fields` object, keyed by field name, that opts one field into reading values out of the search text (`interpret`), into suggestions (`suggest`), and into ordered value labels. Each capability stays off until its configuration object is present, and an empty object turns it on with engine defaults.

## Where each setting is decided

The following table summarizes where you configure each setting and when changes take effect:

| What you change | Where it lives | When it takes effect |
| :--- | :--- | :--- |
| Field types, usages, and the primary key | Index definition | A new generation, where the change is one the engine refuses on a generation that holds documents |
| Analysis chains, index-time synonym sets, and `exact` | Index definition | Documents indexed after the change |
| `weight`, `typoTolerance`, `lengthNormalization`, and sort `missing` | Index definition | Next search on a node, once the change reaches it |
| Tie breakers and ranking signals | Index definition, replaced whole by search settings while those exist | Next search on the holding node, elsewhere within the settings refresh interval |
| Query-time synonym sets, typo exclusions, field interpretation, suggestions, and value labels | Search settings | Next search on the holding node, elsewhere within the settings refresh interval |
| Clauses, field weight overrides, `fuse`, `rescore`, `sort`, and per-request ranking signals | Search request | That search |

## Related

- [Search settings](../reference/admin-api.md#search-settings) - Reference for the search settings endpoint.
- [Field types](../reference/field-types.md) - Reference for what the index definition holds.
- [Search API](../reference/search-api.md) - Reference for what a search request holds.
- [Configuration](../reference/configuration.md) - Reference for refresh intervals.
- [Relevance](relevance.md) - Explanation of how the ranking layers meet.
- [Text analysis](analysis.md) - Explanation of why an analysis change needs a new generation.
- [Generations](generations.md) - Explanation of why a definition travels with the index data.
- [Rolling out a definition change](../how-to/roll-out-a-definition-change.md) - How-to guide for making a definition change.
