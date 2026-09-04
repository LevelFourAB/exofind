# Reading colours and brands in the search box

This guide shows you how to configure search settings to read facet field values from search input as filters. Use this guide when building a search interface that extracts values like `red nike shoes` into colour and brand filters while searching `shoes` as text. The engine extracts the values, filters the matching fields, ranks filter matches first, and returns the parsed filters in the response.

The engine reads stored facet values from the answering generation. Enabling value interpretation requires no reindex and is configured in the search settings of the index.

## Prerequisites

Before you begin, ensure you have:

- A configured index with at least one `string` field that has both `filter` and `facet` enabled and no `hierarchy`. For more information, see [Defining an index](define-an-index.md).
- Credentials with the `indexes.read` permission to read settings, the `settings.write` permission to change them, and the `search` permission to send search queries.

## Steps

1. Check that the fields hold a dictionary:

   Ensure the target fields are `string` fields configured with both `filter` and `facet` and without `hierarchy`:

   ```http
   PUT /v1alpha1/admin/indexes/products
   Content-Type: application/json

   {
     "fields": {
       "id": { "type": "string", "role": "id" },
       "name": { "type": "string", "role": "title" },
       "colour": { "type": "string", "filter": {}, "facet": {} },
       "brand": { "type": "string", "filter": {}, "facet": {} },
       "price": { "type": "double", "filter": {}, "unit": "SEK" }
     }
   }
   ```

   A field that lacks `filter` or `facet` requires an updated index definition and a reindex before the engine can read its values. For more information, see [Rolling out a definition change](roll-out-a-definition-change.md).

2. Opt the fields in through the search settings:

   Send a `PUT` request to update the search settings for the index. Include any existing settings, such as `ranking` or `synonyms`, because `PUT` replaces the entire settings object:

   ```http
   PUT /v1alpha1/admin/indexes/products/settings
   Content-Type: application/json

   {
     "fields": {
       "colour": { "interpret": {} },
       "brand": { "interpret": {} }
     }
   }
   ```

   The server validates the fields against the active generation. Naming a field that does not exist returns `400 Bad Request` with `index:settings:fields:unknown_field`. Naming a field that is not a `string` field with `filter` and `facet`, or that has `hierarchy`, returns `index:settings:fields:interpret_unsupported`.

   Settings take effect immediately on the answering node and within `EXOFIND_SETTINGS_REFRESH_INTERVAL` (default: `10s`) on other nodes.

3. Send a search request with user matching mode:

   To interpret search text, use a `text` clause with `"match": "user"`:

   ```http
   POST /v1alpha1/indexes/products/search
   Content-Type: application/json

   {
     "query": [
       { "type": "text", "text": "red nike shoes under 500", "match": "user" }
     ]
   }
   ```

   The engine reads `red` as the value `Red` for `colour`, `nike` as the value `Nike` for `brand`, and `under 500` as a price bound when the price field declares a currency unit. The query searches the remaining word `shoes` as text.

   Interpretation does not exclude text matches. The query still searches the original words as text, but boosts documents that satisfy the filter so they rank first.

4. Inspect the read filters in the search response:

   When the engine extracts values from query text, the response includes an `interpreted` object:

   ```json
   {
     "hits": [ ... ],
     "interpreted": {
       "filters": [
         {
           "field": "colour",
           "match": { "value": "Red" },
           "words": ["red"]
         },
         {
           "field": "brand",
           "match": { "value": "Nike" },
           "words": ["nike"]
         },
         {
           "field": "price",
           "match": { "type": "range", "lt": 500 },
           "words": ["under", "500"]
         }
       ],
       "text": "shoes"
     }
   }
   ```

   The `match` property returns the value in its stored casing and spelling, so `RED` in the query matches `Red`. Use the returned `filters` array to display interactive chips in your search interface. You can pass each `match` object directly into a `filters` array if the user keeps the refinement for subsequent queries.

5. Disable reading when querying exact text:

   To search the entire query input as text without extracting filters, set `"interpret": "off"` on the `text` clause. This setting disables number interpretation as well:

   ```json
   {
     "query": [
       {
         "type": "text",
         "text": "red nike shoes",
         "match": "user",
         "interpret": "off"
       }
     ]
   }
   ```

   You can also wrap terms in quotation marks (such as `"red"`). Quoted phrases and negative exclusions (`-word`) are never interpreted as filters.

6. Turn a field off later with PATCH:

   To update a single field without replacing the entire settings object, send a `PATCH` request. Set `interpret` to `{}` to enable reading or `null` to disable it:

   ```http
   PATCH /v1alpha1/admin/indexes/products/settings
   Content-Type: application/json

   {
     "fields.brand.interpret": null
   }
   ```

7. Declare labels for values stored in another language:

   The engine matches typed words against stored values, so a term like `röd` does not match the stored value `Red`. To interpret words in the user's language, declare values with labels per locale in the field settings:

   ```http
   PATCH /v1alpha1/admin/indexes/products/settings
   Content-Type: application/json

   {
     "fields.colour.values": [
       { "value": "Red", "labels": { "sv": "Röd", "de": "Rot" } },
       { "value": "Blue", "labels": { "sv": "Blå", "de": "Blau" } }
     ]
   }
   ```

   A search with `"locale": "sv"` then reads `röd` as the value `Red`, and the `match` object in `interpreted.filters` returns `Red`. Facets on `colour` also return these labels in the `label` property, so the filter chip and the facet list display matching text. For information about accepted fields and error responses, see [Declared values](../reference/admin-api.md#declared-values).

## Confirming the result

Inspect the JSON response from the search endpoint to verify query interpretation:

- `interpreted.filters`: Contains each extracted filter, including the target `field`, the `match` object with the stored value, and the original `words`.
- `interpreted.text`: Contains the remaining text after removing interpreted terms. If all terms were converted to filters, this value is an empty string.
- `hits`: Returns documents that satisfy the extracted filter ranked first, followed by documents that match the terms as text.

If the response omits `interpreted`, no filters were extracted. Check the following causes:

- **The field is not opted in**: Retrieve the settings with `GET /v1alpha1/admin/indexes/products/settings` and verify that `fields.<name>.interpret` is present.
- **The word is not a value**: A term is extracted only when it matches an indexed value exactly after case and diacritic normalization. Terms are not stemmed; for example, `shoes` does not match `Shoe`. Multi-word values match contiguous terms up to three words.
- **The word is a label of another locale**: The engine reads declared labels in the search locale, or in the field's default locale when the search locale has no label. Send the user's `locale` with the search request, and verify that the label is keyed by a tag that the locale resolves to.
- **The clause is not in user mode**: Only `text` clauses with `"match": "user"` support interpretation.
- **A newer generation dropped the usage**: Search settings outlive generations. If the active generation lacks the field or configures it without `filter` or `facet`, the engine treats the terms as text instead of returning an error. The node logs skipped fields once per settings version.
- **Unsupported features on the node**: If a node does not support the `interpret_values` capability, it bypasses the settings and evaluates queries against the index definition alone. Verify whether `unsupportedFeatures` in the settings response includes `interpret_values`.

## Related

- [Reading the values of a field](../reference/search-api.md#reading-the-values-of-a-field) - How a span of words is matched and the `interpreted` object.
- [Field settings](../reference/admin-api.md#field-settings) - The `fields` object of the search settings.
- [Reading numbers in the search box](read-numbers-in-the-search-box.md) - Reading a price or a size typed next to a unit.
- [Searching an index](search-an-index.md) - The search box, filters, facets, and ordering.
