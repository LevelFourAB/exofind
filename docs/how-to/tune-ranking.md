# Tuning ranking

Adjust how search results are ordered by relevance without reindexing your
data. Use this guide when your index is populated and serving queries, but you
want to alter result ordering by applying query boosts, document signals, tie
breakers, or second-pass rescoring.

Signals, boosts, tie breakers, and rescoring apply only when results are ordered
by relevance. An explicit `sort` parameter overrides relevance ordering.

## Prerequisites

Before you begin, ensure you have:

- An index that contains documents.
- `sort` enabled on all fields that carry a signal or tie breaker. Enabling
  `sort` on an existing field is a definition change that requires a reindex.
  See [Rolling out a definition change](roll-out-a-definition-change.md).
- An API key with the `search` and `indexes.read` permissions.
- The `settings.write` permission on your API key to store ranking
  configurations in search settings.

## Steps

1. Inspect current score calculations:

   Find out what the current result order comes from by querying the explain
   endpoint for a specific hit. See
   [Find out why a result ranked where it did](explain-a-result.md).

   ```shell
   curl -X POST \
     "$EXOFIND/v1alpha1/indexes/products/search/actions/explain?key=item-101" \
     -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" \
     -d '{
       "query": [
         { "type": "text", "text": "running shoes", "fields": { "name": null } }
       ]
     }'
   ```

   Inspect the `detail` tree in the response to review match scores and signal
   contributions for each clause.

2. Lift documents with a boost clause:

   Add a `boost` clause to your search request to promote matching documents
   without filtering out non-matching documents:

   ```json
   {
     "query": [
       { "type": "text", "text": "running shoes", "fields": { "name": null } },
       {
         "type": "boost",
         "weight": 1.5,
         "clauses": [
           { "field": "featured", "match": { "value": true } }
         ]
       }
     ]
   }
   ```

   Set `weight` greater than `1` to increase the score of matching items, or
   between `0` and `1` to decrease their score.

3. Rank by document values with signals:

   Use signals to adjust relevance scores based on document values. Configure
   `saturation` on numeric fields (such as sales or purchases) or `decay` on
   timestamp fields (such as publication dates), along with an optional
   `weight` multiplier (default `1`):

   Choose one of the following two options depending on your goal:

   1. **Store signals in search settings**: Store ranking signals in search
      settings so every caller receives them by default. Fetch the current
      settings version, then send a `PUT` request with an `If-Match` header:

      ```shell
      curl -X PUT \
        "$EXOFIND/v1alpha1/admin/indexes/products/settings" \
        -H "Authorization: Bearer $KEY" \
        -H "Content-Type: application/json" \
        -H "If-Match: 9f2c1a0b3d4e5f60" \
        -d '{
          "ranking": {
            "signals": [
              { "field": "purchases", "saturation": { "pivot": 50 }, "weight": 1.0 },
              { "field": "published", "decay": { "halfLife": 604800 }, "weight": 0.5 }
            ]
          }
        }'
      ```

      While the search settings carry a ranking, it replaces the definition's
      ranking completely, tie breakers included, so any tie breaker the
      definition declares must be carried over into the settings. Search
      settings take effect immediately on the holding node and within
      `EXOFIND_SETTINGS_REFRESH_INTERVAL` (default 10 seconds) on other nodes.

   2. **Send signals in the search request**: Pass `signals` directly in a
      search query using `signalsMode`. Set `"signalsMode": "replace"` to try a
      complete ranking before storing it, or `"signalsMode": "add"` (default) to
      layer per-request signals (such as user affinity) on top of the index's
      stored ranking:

      ```json
      {
        "query": [
          { "type": "text", "text": "running shoes" }
        ],
        "signals": [
          { "field": "brandAffinity", "saturation": { "pivot": 5 }, "weight": 1.2 }
        ],
        "signalsMode": "add"
      }
      ```

4. Break score ties with tie breakers:

   Define `tieBreakers` in the `ranking` configuration to resolve ordering when
   documents share identical relevance scores. Set the target field and sort
   `direction` (`"ascending"` or `"descending"`):

   ```json
   {
     "ranking": {
       "tieBreakers": [
         { "field": "popularity", "direction": "descending" },
         { "field": "id", "direction": "ascending" }
       ]
     }
   }
   ```

   Exofind evaluates tie breakers in sequence until the tie between two
   documents is resolved.

5. Rescore top results in a second pass:

   Add a `rescore` block to the search request to apply expensive scoring rules
   or user personalization only to the best first-pass results:

   ```json
   {
     "query": [
       { "type": "text", "text": "running shoes" }
     ],
     "rescore": {
       "window": 200,
       "boost": [
         { "field": "brand", "match": { "value": "aurora" } }
       ],
       "signals": [
         { "field": "purchases", "saturation": { "pivot": 50 } }
       ],
       "weight": 0.5
     }
   }
   ```

   The second pass scores only the window results, so it is where per-request
   work too expensive for every match belongs. Paging inside the window counts
   results using offsets, while cursors past the window continue in the order
   relevance originally ranked them without second-pass scoring.

6. Check the outcome with explain:

   Call the explain endpoint again to verify how your ranking adjustments affect
   document scores.

   **Note:** The explain endpoint ignores `rescore` and explains only the
   first-pass relevance score.

## Confirming the result

Execute a search query without explicit sorts to verify that documents return
in the expected order:

```shell
curl -X POST \
  "$EXOFIND/v1alpha1/indexes/products/search" \
  -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "query": [
      { "type": "text", "text": "running shoes" }
    ]
  }'
```

Inspect the returned `hits` array to verify that boosted items and high-signal
documents rank higher in the relevance order.

## Related

- [Relevance](../explanation/relevance.md) - How scoring and ranking layers
  interact.
- [Find out why a result ranked where it did](explain-a-result.md) - Reading a
  hit's score back as the clauses and fields you wrote.
- [Searching an index](search-an-index.md) - The search request the boosts,
  signals, and rescoring sit on.
- [Changing synonyms without reindexing](change-synonyms-without-reindexing.md) -
  The other search setting that changes results without a new generation.
- [Rolling out a definition change](roll-out-a-definition-change.md) - Enabling
  `sort` on a field that a signal or a tie breaker needs.
- [Search API](../reference/search-api.md) - Reference for search query
  clauses, signals, and rescoring parameters.
- [Field types](../reference/field-types.md) - Schema options for sortable
  fields, tie breakers, and signals.
- [Admin API](../reference/admin-api.md) - Managing index search settings and
  ranking configurations.
