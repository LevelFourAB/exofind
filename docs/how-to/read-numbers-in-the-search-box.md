# Reading numbers in the search box

This guide shows you how to configure number fields to read quantities and comparative phrases from search input as filters. Use this guide when building a search interface that extracts values like `shoes under 100 kr` or `phone 256gb` into numeric filters. The engine extracts the quantities, filters the matching fields, ranks filter matches first, and returns the parsed filters in the response.

## Prerequisites

Before you begin, ensure you have:

- A configured index with at least one `int32`, `int64`, `float`, or `double` field that has `filter` enabled. For more information, see [Defining an index](define-an-index.md).
- Credentials with the `indexes.write` permission to update the index definition and the `search` permission to send search queries.

## Steps

1. Declare the unit on the number field:

   Add the `unit` property to the field in the index definition. Set the value to an ISO 4217 currency code (such as `SEK`, `EUR`, or `USD`), a CLDR unit identifier (such as `gigabyte` or `kilogram`), or custom text (such as `mAh`) that matches case-insensitively:

   ```http
   PUT /v1alpha1/admin/indexes/products
   Content-Type: application/json

   {
     "fields": {
       "id": { "type": "string", "role": "id" },
       "name": { "type": "string", "role": "title" },
       "price": { "type": "double", "filter": {}, "unit": "SEK" },
       "storage": { "type": "int32", "filter": {}, "unit": "gigabyte" }
     }
   }
   ```

   Adding, updating, or removing a `unit` property takes effect immediately on subsequent searches and does not require a reindex. A definition with a unit requires the `field.unit` engine feature.

2. Send a search request with user matching mode:

   To interpret search text, use a `text` clause with `"match": "user"`:

   ```http
   POST /v1alpha1/indexes/products/search

   {
     "query": [
       { "type": "text", "text": "shoes under 100 kr", "match": "user" }
     ]
   }
   ```

   The engine extracts the number and its associated unit or comparative phrase as a filter on matching fields. If a number is typed with a comparative word but no unit (such as `under 100`), the engine applies the filter to the single field configured with a currency unit. Standalone numbers without units or comparative phrases (such as `size 44`) remain text terms.

   Interpretation does not exclude text matches. The query still searches the original words as text, but boosts documents that satisfy the filter so they rank first.

3. Inspect the interpreted filters in the search response:

   When the engine extracts filters from query text, the response includes an `interpreted` object:

   ```json
   {
     "hits": [ ... ],
     "interpreted": {
       "filters": [
         {
           "field": "price",
           "match": { "type": "range", "lt": 100 },
           "words": ["under", "100", "kr"]
         }
       ],
       "text": "shoes"
     }
   }
   ```

   Use the returned `filters` array to display interactive chips in your search interface. Each filter provides the target `field`, the extracted `words`, and a `match` object that you can pass directly into a `filters` array if the user keeps the refinement for subsequent queries.

4. Disable interpretation when querying exact text:

   To search the entire query input as text without extracting filters, set `"interpret": "off"` on the `text` clause:

   ```json
   {
     "query": [
       {
         "type": "text",
         "text": "shoes under 100 kr",
         "match": "user",
         "interpret": "off"
       }
     ]
   }
   ```

   You can also wrap terms in quotation marks (such as `"under 100"`). Quoted phrases and negative exclusions (`-word`) are never interpreted as filters.

5. Target the pricelist of the current customer:

   When an index stores prices across multiple pricelists, configure the `interpret` object with explicit `fields` targets to direct numeric queries to the customer's pricelist with an optional fallback:

   ```json
   {
     "fields": {
       "id": { "type": "string", "primaryKey": true },
       "name": { "type": "string", "matching": {} },
       "prices": {
         "type": "object",
         "multiple": true,
         "mode": "nested",
         "key": "list",
         "fields": {
           "list": { "type": "string", "filter": {}, "required": true },
           "amount": { "type": "double", "filter": {}, "sort": {}, "unit": "SEK" }
         }
       }
     }
   }
   ```

   Given the following documents:

   ```json
   {"id": "1", "name": "Rain jacket", "prices": [{"list": "cust-17", "amount": 89.0}, {"list": "store", "amount": 129.0}]}
   {"id": "2", "name": "Rain boots",  "prices": [{"list": "store", "amount": 79.0}]}
   {"id": "3", "name": "Rain hat",    "prices": [{"list": "cust-17", "amount": 149.0}, {"list": "store", "amount": 99.0}]}
   ```

   Send a search request targeting the customer's pricelist:

   ```json
   {
     "query": [
       {
         "type": "text", "match": "user", "text": "rain under 100",
         "interpret": {
           "fields": [
             {
               "field": "prices.amount",
               "when": [ { "field": "prices.list", "match": { "value": "cust-17" } } ],
               "fallback": [
                 {
                   "field": "prices.amount",
                   "when": [ { "field": "prices.list", "match": { "value": "store" } } ]
                 }
               ]
             }
           ]
         }
       }
     ]
   }
   ```

   Each target object accepts the following properties:

   - `field`: The number field, named as in the index definition or using its dotted path for a nested field. Naming a field that declares no `unit` or is not a number field returns `index:query:interpret:no_unit`.
   - `when`: Optional clauses that must hold where the number is read. For nested lists, the clauses must hold in the same value of the list as the number.
   - `fallback`: Optional fallback targets read in order for documents with no value on earlier targets. Every target in a fallback chain must declare the same unit, or the index returns `index:query:interpret:fallback_unit`.

   In this example, the rain jacket matches on the customer price of 89, the rain boots match through the fallback on the store price of 79, and the rain hat does not match because its customer price of 149 exists and is not below 100.

   The response echoes `when` and `fallback` on each filter so the interface can send the target back.

   **Note:** A field declares one unit. Pricelists in several currencies need one amount field per currency (such as `prices.sek` and `prices.eur`), and the request targets the field of the customer's currency.

6. Set the search locale for comparative words:

   Comparative words such as `under`, `högst`, or `mehr als` depend on the search locale. Specify `locale` in the search request to evaluate comparative words for that language:

   ```json
   {
     "query": [
       { "type": "text", "text": "skor högst 100 kr", "match": "user" }
     ],
     "locale": "sv"
   }
   ```

   If omitted, the search defaults to `en`. Locales without a comparative word list still interpret numbers typed with explicit units, but do not interpret comparative bounds without units.

7. Read the search box of a catalogue with variants:

   To search a catalogue where a product can match on its own fields or on variant fields, send the typed text in two places within an `or` query. Place one `text` clause on the product, and place a second `text` clause inside a `nested` clause on the variants. Because both clauses hold the same text, the engine reads both clauses:

   ```json
   {
     "query": [
       {
         "type": "or",
         "clauses": [
           {
             "type": "text", "text": "running shoes under 100", "match": "user",
             "fields": { "name": null }
           },
           {
             "type": "nested", "path": "variants",
             "clauses": [
               {
                 "type": "text", "text": "running shoes under 100", "match": "user",
                 "fields": { "variants.title": null, "variants.number": null }
               }
             ]
           }
         ]
       }
     ]
   }
   ```

   With a `unit` declared on `variants.price`, the first clause reads `under 100` as a product with any variant below 100. The clause inside `nested` reads it as the variant whose text matched being below 100. A product found by a variant number is found only when that variant is the cheap one.

   A number field declared on the product, such as a weight, is read by the first clause only because a clause inside `nested` sees one variant at a time.

## Confirming the result

Inspect the JSON response from the search endpoint to verify query interpretation:

- `interpreted.filters`: Contains each extracted filter, including the target `field`, the `match` criteria, and the original `words`.
- `interpreted.text`: Contains the remaining text after removing interpreted terms. If all terms were converted to filters, this value is an empty string.
- `hits`: Returns documents that satisfy the extracted filter ranked first, followed by documents that match the terms as text.

If the response omits `interpreted`, no filters were extracted. Verify that the target field declares a `unit`, the query clause sets `"match": "user"`, and the search query contains a recognized unit or a comparative phrase supported by the search `locale`.

## Related

- [Reading numbers and units](../reference/search-api.md#reading-numbers-and-units) - Every shape that is read, how a unit is matched, and the `interpreted` object.
- [Field types](../reference/field-types.md#int32-int64-float-double) - The `unit` property of a number field.
- [Locales](../reference/locales.md#comparative-words) - The comparative words of each locale.
- [Searching an index](search-an-index.md) - The search box, filters, facets, and ordering.
