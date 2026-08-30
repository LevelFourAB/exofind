# Find out why a result ranked where it did

This guide shows you how to inspect the relevance score of a document for a search query, trace score calculations back to query clauses, inspect ranking signal contributions, identify why a document is missing, and explain sub-document hits. Use this guide when you need to defend or tune the result ordering in your catalogue.

## Prerequisites

Before you begin, ensure that you have:

- An index with a declared primary key.
- An API token with the `search` permission.
- The primary key of the document you want to explain.

## Steps

1. Send your search query to the explain endpoint:

   Save your search request body to a file named `search.json`:

   ```json
   {
     "query": [
       { "type": "text", "text": "waterproof boot", "fields": { "name": 3 } },
       { "type": "boost", "weight": 2, "clauses": [
         { "field": "category", "match": { "value": "boots" } }
       ] }
     ],
     "filters": [
       { "field": "inStock", "match": { "value": true } }
     ]
   }
   ```

   Run `curl` to post the search request to the explain action, passing the document's primary key in the `key` query parameter:

   ```sh
   curl -X POST \
     "$EXOFIND/v1alpha1/indexes/products/search/actions/explain?key=9781234567890" \
     -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" \
     --data @search.json
   ```

   The endpoint compiles the same clauses, locale, and index settings as the search endpoint. Parameters such as `limit`, `offset`, `sort`, `facets`, and `highlight` are ignored. If your search specifies a field sort, the endpoint still calculates and explains the relevance score.

2. Map score contributions back to your request clauses:

   Inspect the `detail` object in the JSON response to see how each clause contributed to the total score:

   ```json
   {
     "matched": true,
     "score": 7.42,
     "detail": {
       "matched": true,
       "score": 7.42,
       "description": "sum of:",
       "children": [
         {
           "matched": true,
           "score": 5.10,
           "description": "weight(name:waterproof) ...",
           "clause": "query[0]",
           "clauseType": "text",
           "field": "name",
           "usage": "matching",
           "children": []
         }
       ]
     }
   }
   ```

   Each node in `children` includes:
   - `clause`: The path to the clause in your request body (for example, `query[0]`, `query[1].clauses[0]`, or `filters[0]`).
   - `clauseType`: The clause type from your request, such as `text`, `boost`, `field`, `knn`, or `fuse`.
   - `field`: The field name defined in your index definition.
   - `score`: The numeric score contributed by this clause to the parent step.

3. Identify why a document is missing from results:

   If a document does not appear in search results, send the search body with the document's `key` to the explain endpoint.

   When a document does not match the search, the response returns `matched: false` and `score: 0`:

   ```json
   {
     "matched": false,
     "score": 0,
     "detail": {
       "matched": false,
       "score": 0,
       "description": "sum of:",
       "children": [
         {
           "matched": true,
           "score": 3.2,
           "clause": "query[0]"
         },
         {
           "matched": false,
           "score": 0,
           "clause": "filters[0]",
           "clauseType": "field",
           "field": "inStock"
         }
       ]
     }
   }
   ```

   Check the `matched` boolean on each child step:
   - Steps with `"matched": true` satisfied the condition.
   - Steps with `"matched": false` failed the condition and caused the document to be excluded.

4. Inspect ranking signal contributions:

   If your search or index includes ranking signals, locate the `signals` child node in the `detail` tree:

   ```json
   {
     "matched": true,
     "score": 1.30,
     "description": "signals, product of:",
     "children": [
       {
         "matched": true,
         "score": 1.24,
         "description": "signal popularity (saturation, pivot 10.0, weight 1.0) reads 412.0",
         "children": []
       }
     ]
   }
   ```

   Each signal step reports the signal field name, its mathematical shape and weight, and the raw value read from the document. If a document has no value for a signal field, the signal reports a multiplier score of `1`.

5. Explain a sub-document or value hit:

   When your search targets sub-documents using `"hits": { "path": "<field>" }`, pass the zero-based index of the sub-document in the `index` query parameter:

   ```sh
   curl -X POST \
     "$EXOFIND/v1alpha1/indexes/products/search/actions/explain?key=9781234567890&index=1" \
     -H "Authorization: Bearer $KEY" \
     -H "Content-Type: application/json" \
     --data @search.json
   ```

   The `key` parameter identifies the parent document, and the `index` parameter selects the specific sub-document value to explain.

## Confirming the result

Inspect the response from the explain endpoint to confirm the scoring breakdown:

- `matched`: `true` if the document matches the query and filters; `false` if it was excluded.
- `score`: The total relevance score calculated for the hit.
- `detail`: The hierarchical score tree containing descriptions, scores, and `clause` pointers.
- `relaxed`: Present if query terms were dropped during search relaxation. Contains the dropped words and the actual query text evaluated.

## Related

- [Search API](../reference/search-api.md) - Request bodies, clauses, matchers, and parameters.
- [Relevance](../explanation/relevance.md) - How scoring and ranking signals determine result order.
- [Searching an index](search-an-index.md) - Constructing search requests and filter clauses.
- [Using sub-documents](use-sub-documents.md) - Querying and scoring individual object values.
- [Errors](../reference/errors.md) - Error codes returned by index actions.
