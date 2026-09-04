# Suggesting what to search for while it is typed

This guide shows you how to configure search settings and use the suggest endpoint to suggest field values in real time as a user types into a search box. Use this guide when building an autocomplete search box that suggests brands, categories, or other field values, marks the typed portion of the text, and updates suggestions on every keystroke.

The engine reads stored facet values from the answering generation. Enabling suggestions requires no reindex and is configured in the search settings of the index.

## Prerequisites

Before you begin, ensure you have:

- A configured index with at least one `string` field that has `facet` enabled and no `hierarchy`.
- Credentials with the `indexes.read` permission to read settings, the `settings.write` permission to change them, and the `search` permission to send suggest requests.

## Steps

1. Check that the fields hold a dictionary:

   Ensure the target fields are `string` fields configured with `facet` and without `hierarchy`. Configure `filter` as well if you plan to let users search a picked suggestion as a filter chip:

   ```http
   PUT /v1alpha1/admin/indexes/products
   Content-Type: application/json

   {
     "fields": {
       "id": { "type": "string", "role": "id" },
       "name": { "type": "string", "role": "title" },
       "brand": { "type": "string", "filter": {}, "facet": {} },
       "category": { "type": "string", "filter": {}, "facet": {} }
     }
   }
   ```

2. Opt the fields in through the search settings:

   Send a `PUT` request to update the search settings for the index. Include any existing settings, such as `ranking` or `synonyms`, because `PUT` replaces the entire settings object:

   ```http
   PUT /v1alpha1/admin/indexes/products/settings
   Content-Type: application/json

   {
     "fields": {
       "brand": { "suggest": {} },
       "category": { "suggest": {} }
     }
   }
   ```

   To update a single field without replacing the entire settings object, send a `PATCH` request:

   ```http
   PATCH /v1alpha1/admin/indexes/products/settings
   Content-Type: application/json

   {
     "fields.brand.suggest": {}
   }
   ```

   To disable suggestions for a field, set the path to `null`.

   The server validates the fields against the active generation. Storing settings for a field that does not exist returns `index:settings:fields:unknown_field`. Storing settings for a field that is not a `string` with `facet`, or that has `hierarchy`, returns HTTP 400 `index:settings:fields:suggest_unsupported`.

3. Ask for suggestions from the search box on every keystroke:

   On each keystroke in the client search box, send a `POST` request to `/v1alpha1/indexes/{name}/suggest`. Debounce keystroke events on the client to avoid sending unnecessary requests while the user types quickly. You can scope suggestions using the `filters` property:

   ```http
   POST /v1alpha1/indexes/products/suggest
   Content-Type: application/json

   {
     "text": "adi",
     "filters": [
       { "field": "category", "match": { "value": "Shoes" } }
     ],
     "limit": 5
   }
   ```

   The engine returns matching values ordered by document count descending:

   ```json
   {
     "suggestions": [
       { "text": "adidas", "typed": 3, "field": "brand", "value": "adidas", "count": 87 },
       { "text": "Adidas Originals", "typed": 3, "field": "brand", "value": "Adidas Originals", "count": 12 }
     ],
     "tookMs": 0.412
   }
   ```

4. Render the suggestions:

   Use the properties of each object in `suggestions` to display the list:

   - Use `typed` to mark the first characters of `text` apart from the rest, so the part already typed reads differently from the part that completes it.
   - Show `text` as the engine answers it. It holds the label the search settings declare for the value in the locale of the request, where there is one.
   - Show a suggestion with `corrected: true` apart from the others. It was found one mistake away from what was typed, so `typed` is `0` and nothing of it can be marked.

5. Search what was picked:

   When the user selects a suggestion, execute a search query using the chosen item.

   To search the suggestion as text, pass `text` in a `text` clause:

   ```json
   {
     "query": [
       { "type": "text", "text": "adidas" }
     ]
   }
   ```

   To filter on the selected field and value instead, the way a ticked filter does, pass a `field` clause under `filters`. The suggestion's `field` and `value` are what the clause takes:

   ```json
   {
     "filters": [
       { "field": "brand", "match": { "value": "adidas" } }
     ]
   }
   ```

   A field must have `filter` enabled for this; see step 1.

6. Configure typos, limits, locales, and labels:

   Adjust the suggest request parameters to fit your search requirements:

   - **Typos**: By default, `typos` is `"auto"`, which tolerates one mistake for queries of 5 or more characters when fewer exact matches than `limit` exist. Set `"typos": "off"` to require exact prefix matches only.
   - **Limit**: Set `limit` to an integer between 1 and 100 (default: 5) to control the maximum number of suggestions returned.
   - **Locale and labels**: Pass a BCP-47 tag in `locale` to pick the labels declared under `fields.<name>.values` in the search settings. The engine compares the typed text with the labels of that locale as well as with the stored values, and answers the label in `text` and `label`. Without `locale`, the labels of each field's default locale are used. See [Declared values](../reference/admin-api.md#declared-values).

7. Monitor and operate suggestions:

   Keep the following operational behaviors in mind:

   - **Timeouts**: The timeout for suggest requests is governed by `EXOFIND_SUGGEST_TIMEOUT` (property `exofind.suggest.timeout`, default `2s`). When a request exceeds this duration, the server returns HTTP 503 `search:timeout`.
   - **Metrics**: Track suggest request duration and outcomes with the `exofind.suggest` timer metric, which records `outcome` (`success` or `error`) and optionally `index`.
   - **Warming**: After each index reopen, background warm threads configured by `EXOFIND_SEARCH_WARM_THREADS` build the folded dictionary for each suggested field. If warming is set to `0`, the first request after a reopen builds the folded dictionary.
   - **Memory cost**: Each suggested field keeps a folded dictionary per segment, at about the folded bytes of its distinct values plus 8 bytes per value, counted in `exofind.facet.state.bytes`. Opt in the fields a shopper types towards, such as brand and category. A free-text field costs its whole vocabulary per segment.

## Confirming the result

Verify that suggestions are returned correctly by sending a request with a partial term:

```http
POST /v1alpha1/indexes/products/suggest
Content-Type: application/json

{
  "text": "adi"
}
```

Check the response:

- `suggestions`: Contains matching values from opted-in fields, with `typed` indicating the length of the matched prefix and `count` reflecting the number of matching documents.
- `tookMs`: Shows the execution duration in milliseconds.

If `suggestions` is empty, check the following causes:

- **No fields opted in**: Verify with `GET /v1alpha1/admin/indexes/products/settings` that `fields.<name>.suggest` is present for the target fields. An index with no suggested fields returns an empty list with HTTP 200.
- **Prefix does not match start of value**: The engine matches the start of the entire value, not individual words inside the value. For example, `air` does not match `Nike Air Max`.
- **Generation mismatch**: If a newly promoted generation omits the field or lacks `facet`, the engine skips the field. Check the node logs for skipped field warnings.
- **Unsupported node capability**: If a node lacks the `suggest_values` capability, it ignores the settings and returns no suggestions. Check the `unsupportedFeatures` list in the settings response.

## Related

- [Suggesting what to search for](../reference/search-api.md#suggesting-what-to-search-for) - Suggest request fields, matching rules, and response structure.
- [Field settings](../reference/admin-api.md#field-settings) - Configuring `suggest` and declared values on fields.
- [Reading colours and brands in the search box](read-field-values-in-the-search-box.md) - Interpreting facet values directly from full search query strings.
- [Errors](../reference/errors.md) - Error codes returned by search and admin APIs.
