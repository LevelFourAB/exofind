# Relevance

This document explains how Exofind calculates relevance scores and how its ranking layers interact when ordering search results. When a search query does not specify a sort order, Exofind orders results by relevance: how well each document matches the query. Ranking is calculated in separate layers that evaluate match quality, field location, intrinsic document signals, and tie breaking.

For setting names and accepted values, see [Field types](../reference/field-types.md#ranking) and the [Search API](../reference/search-api.md#signals).

## What a match is worth

The foundation of relevance is standard Lucene text scoring:

- A rare word contributes more to the score than a common word.
- A word that appears repeatedly in a field value contributes more, with diminishing returns.
- A field value's length counts against its score. The same query words matching a short value indicate a better match than those matching inside a long value.

Exofind lets a field definition configure length normalization through `lengthNormalization`, because the importance of value length depends on what the field contains. A field containing names needs full normalization, because extra words distinguish the target item from a merely related item. A field containing prose needs much less normalization. A field containing all document content needs no normalization, because a fuller value there is not a worse answer.

Exofind stores the field length when indexing a document. The field definition controls only how much of that length Exofind reads at query time. Therefore, changing `lengthNormalization` reorders results on subsequent searches without requiring reindexing.

## Where the match was found

A search can query several fields at once, and a match in a title carries more weight than a match in a footnote. The `weight` setting under a field's `matching` configuration controls how much a match in that field contributes to the score. A search request can override these field weights by mapping each field to a number in its `text` clause.

The `combine` setting controls how Exofind combines matches across fields. The default behavior is per-word combination: Exofind looks for each word across all fields and counts it in whichever field matches best. This default fits a general search box where a query like `red nike shoes` describes attributes across color, brand, and name fields. For fields that represent parallel versions of the same content, such as a title and a body, set `combine` to `field` so that a single field must satisfy the search query.

Combining words across fields works only when the fields tokenize text into the same words. When fields analyze text differently—for example, if one decompounds words while another drops stopwords—Exofind combines the fields that share the same tokenization into groups. Exofind then ranks the document by the best-matching group.

## Matching the whole value

The `exact` setting on a field boosts a document when a search matches the entire field value, rather than merely matching the words within it. For example, this boost ranks a product named `iphone 15` above a case designed for `iphone 15`.

The `exact` setting acts as a score boost, not a filter. It applies only to documents that the search query already matched, preserving hit counts and facet counts. This design ensures that ranking adjustments do not alter result counts. Because Exofind writes `exact` data during indexing, enabling `exact` applies only to documents indexed after the setting is enabled.

## Conditions that lift rather than narrow

A `boost` clause ranks matching documents higher without excluding non-matching documents. For example, you can rank featured products above standard products, or in-stock items above out-of-stock items. While filters remove non-matching results, boost clauses reorder them.

Boost clauses contribute to the relevance score of a match. For this reason, [highlighting](../reference/search-api.md#highlighting) evaluates boost clauses rather than filter clauses. A document does not receive highlighting for a category filter that matches it.

## What the document is worth on its own

Text scoring evaluates only the search match. The `signals` configuration provides the other half of relevance: intrinsic document values, such as sales volume or publication date. Exofind transforms a signal value into a number between 0 and 1, and multiplies it into the score as `1 + weight * shape`.

The transformation shape prevents signals from distorting search quality:

- A document with no value for a signal receives a shape value of 0. It is not penalized or multiplied away, so adding a signal does not bury existing documents that lack the value.
- A signal can boost a document score by at most its `weight`, regardless of how large the underlying value is. A high-selling item cannot outrank a document with a significantly better text match.

The choice of shape depends on what the signal measures. An unbounded count saturates toward 1, while an age signal decays by halving over time. Because Exofind evaluates signals at query time rather than storing them in index structures, you can adjust signal configurations in the index definition or pass custom signals in a search request without reindexing documents.

## What breaks the remaining ties

Signals produce continuous scores, but ties can still occur. For example, a search that only applies filters matches all returned documents equally. The index's `tieBreakers` setting defines fallback ordering rules. Exofind appends these tie breakers after the requested sort order and evaluates them in sequence until one resolves the tie between two documents. Tie breakers resolve ordering within ties without changing the primary sort order.

## Updating ranking with search settings

Signals and tie breakers are defined in the index definition. Because the definition travels with the index data, updating it requires the writer node, and other nodes receive changes only on their next pull. To update query-time settings faster, an index can use [search settings](../reference/admin-api.md#search-settings). A search settings request can be sent to any node - it runs on the index's holder - and all nodes re-read the settings on their own refresh interval.

When search settings define a `ranking` configuration, it completely replaces the `ranking` configuration from the index definition. The index definition provides the default ranking, and deleting search settings restores this default. Signals in a search request override both search settings and the index definition. The precedence order is search request, then search settings, then index definition.

Tuning proceeds in small steps - one weight, one pivot - so search settings also accept a change that names only the part it moves, described the same way a change to part of a document is. See [Changing part of the search settings](../reference/admin-api.md#changing-part-of-the-search-settings).

Search settings also carry [synonym sets](../reference/admin-api.md#synonyms) that widen what a search asks for. Synonyms are a relevance decision because the words a rule adds are counted together with the word that was typed as one term. Consequently, a document found through a rare synonym is not scored by how rare the synonym is. The set's boost weighs an added word against the typed one.

The two sides have different costs. An index-time set reaches only documents indexed after it, whereas a query-time set reaches everything already in the index. Putting the same rule on both sides counts it twice.

Search settings attach to the index name rather than to a specific index generation, so promoting a generation preserves your ranking tuning. This approach involves two trade-offs:

- Nodes update independently, so two nodes can rank the same query differently for up to one refresh interval.
- If a newer generation lacks a field configured in search settings, searches skip that ranking entry rather than failing.

## When relevance is not the order

When a search request specifies an explicit `sort` parameter, Exofind orders results by that sort. Exofind does not calculate relevance scores or evaluate signals for explicit sorts. If an application provides a "sort by price" option, it must provide a way to switch back to relevance ordering by requesting a `score` sort. Exofind still appends tie breakers to explicit sort orders to resolve ties.

## Vector scores are on their own scale

A [`knn`](../reference/search-api.md#knn) clause scores documents based on vector distance, which uses a different scale from text matching. In a hybrid search combining both scoring methods with an `or` clause, Exofind adds the vector and text scores together without normalizing them. Because the appropriate balance depends on the specific embedding model and text data, you must measure your search results and tune the balance using a `boost` clause.

## Relaxing changes the result set, not the ranking

When a `text` search matches no documents, query relaxation can drop terms instead of returning an empty result set. The response reports which terms were dropped. Dropped terms still contribute to ranking: documents that contain a dropped term rank higher among the returned results. Relaxation alters which documents are eligible to be ranked, not the ranking mechanics themselves.

## Where each part is decided

The following table summarizes where you configure each ranking component and when changes take effect:

| Component | Configuration location | Takes effect |
| :--- | :--- | :--- |
| Length normalization | Index definition | Next search |
| Field weights | Index definition (overridable per search) | Next search |
| Whole-value match (`exact`) | Index definition | Newly indexed documents |
| Boost clauses | Search request | Next search |
| Signals | Index definition, replaceable by search settings (overridable per search) | Next search on the node serving it, within the settings refresh interval elsewhere |
| Tie breakers | Index definition, replaceable by search settings | Next search on the node serving it, within the settings refresh interval elsewhere |
| Index-time synonyms | Index definition | Newly indexed documents |
| Query-time synonyms | Search settings | Next search on the node serving it, within the settings refresh interval elsewhere |

Exofind evaluates most ranking components at query time, making ranking adjustments fast to test: update the definition, the search settings, or the search query and compare results immediately. Only `exact` requires reindexing documents. Changes to text analysis (how text is tokenized into words) require reindexing into a new index generation rather than modifying ranking configuration.

## Related

- [Field types](../reference/field-types.md#ranking) - Reference for `ranking`, `signals`, and field-level settings.
- [Search API](../reference/search-api.md) - Reference for `text`, `boost`, `signals`, and `sort` parameters.
- [Search an index](../how-to/search-an-index.md) - How-to guide for constructing search queries.
