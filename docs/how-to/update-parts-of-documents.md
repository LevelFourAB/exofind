# Updating parts of documents

This guide shows you how to update specific fields, nested object values, and locale variants in existing documents without resending the entire document.

The single rule that governs all partial updates is that the path replaces exactly what it names and leaves everything around it unchanged. How deeply you name decides how much you replace.

## Prerequisites

Before you update parts of documents, ensure you have:

- An index definition that declares a primary key.
- An index configured to store document sources (`source` is not set to `none`).
- The `documents.write` permission for the index.

## Steps

1. Replace a whole field:

   To replace a field or clear its value, send a `POST` request to `/v1alpha1/indexes/{name}/documents/actions/update` with the document primary key and the field name. Setting a field value to `null` empties that field.

   ```http
   POST /v1alpha1/indexes/products/documents/actions/update
   Content-Type: application/json

   {
     "documents": [
       {
         "id": "1",
         "price": 34.50,
         "discount": null
       }
     ]
   }
   ```

2. Change a field inside a specific nested object:

   To update a field inside one object in a list of objects, use a selector path formatted as `field[match_field=match_value].inner_field`.

   ```http
   POST /v1alpha1/indexes/products/documents/actions/update
   Content-Type: application/json

   {
     "documents": [
       {
         "id": "1",
         "variants[sku=V-2].price": 29.0
       }
     ]
   }
   ```

   Paths use selectors instead of array positions so that updates do not depend on array order or break when items are added, removed, or reordered. A caller that sends only what changed does not know the positions in the first place.

   Selectors compare the text form of a value, so a value held as the number `2` matches the selector `2`. If a selector value contains a closing bracket, escape it with a backslash, as in `variants[sku=a\]b]`. JSON escapes the backslash itself, so that key is written `"variants[sku=a\\]b]"` in the request body.

3. Replace or remove an entire nested object:

   To replace all fields of a specific nested object in a list, name the object using a selector without specifying an inner field. To remove the matched object from the list, set the selector path to `null`.

   ```http
   POST /v1alpha1/indexes/products/documents/actions/update
   Content-Type: application/json

   {
     "documents": [
       {
         "id": "1",
         "variants[sku=V-2]": { "sku": "V-2", "price": 29.0 },
         "variants[sku=V-3]": null
       }
     ]
   }
   ```

   Replacing an object whole removes any fields omitted in the new object value. The replaced object retains its original position in the list.

4. Add a value to a multi-value field:

   To append an item to a field declared as `multiple` without replacing existing entries, use empty brackets `[]`.

   ```http
   POST /v1alpha1/indexes/products/documents/actions/update
   Content-Type: application/json

   {
     "documents": [
       {
         "id": "1",
         "variants[]": { "sku": "V-4", "price": 40.0, "color": "red" }
       }
     ]
   }
   ```

   Added values are placed at the end of the existing list.

5. Update or remove a locale variant:

   To change the value of a single language in a locale-specific field, specify the BCP 47 tag in brackets. To remove a language variant, set the path to `null`.

   ```http
   POST /v1alpha1/indexes/products/documents/actions/update
   Content-Type: application/json

   {
     "documents": [
       { "id": "1", "title[sv]": "Blåbärssylt II" },
       { "id": "2", "title[sv]": null }
     ]
   }
   ```

   Locale tags resolve against the variants declared in the field definition, so `title[nb-NO]` changes a field that holds `no`. A tag the field holds no variant for returns `request:update:locale_unknown`.

6. Update a field inside a single object:

   For fields that hold a single (non-multiple) object rather than an array of objects, use dot notation without a selector.

   ```http
   POST /v1alpha1/indexes/products/documents/actions/update
   Content-Type: application/json

   {
     "documents": [
       {
         "id": "1",
         "dimensions.width": 12.0
       }
     ]
   }
   ```

   This updates the specified inner field while leaving the remaining fields of the object intact.

7. Batch multiple updates with NDJSON:

   To stream large update batches, use newline-delimited JSON (`application/x-ndjson`) with one JSON object per line. To prevent the request from failing if some document keys do not exist, append `?missing=skip`.

   ```http
   POST /v1alpha1/indexes/products/documents/actions/update?missing=skip
   Content-Type: application/x-ndjson

   {"id": "1", "price": 34.50}
   {"id": "2", "variants[sku=W-1].price": 15.00}
   {"id": "999", "price": 10.00}
   ```

   When `missing=skip` is enabled, the server updates existing documents, skips missing documents, and lists the skipped keys in the response:

   ```json
   {
     "updated": 2,
     "missing": ["999"]
   }
   ```

## Confirming the result

To verify your updates, read the documents back with `GET /v1alpha1/indexes/{name}/documents`, which answers in primary key order. A change is searchable only once the index commits. For more information, see [Make a write visible to search](make-writes-visible.md).

```http
GET /v1alpha1/indexes/products/documents?limit=1
```

The response returns the first document in full, with your changes applied:

```json
{
  "documents": [
    {
      "id": "1",
      "title": { "sv": "Blåbärssylt II", "en": "Blueberry jam" },
      "price": 34.50,
      "dimensions": { "width": 12.0, "height": 4.0 },
      "variants": [
        { "sku": "V-1", "price": 10.0, "color": "blue" },
        { "sku": "V-2", "price": 29.0 },
        { "sku": "V-4", "price": 40.0, "color": "red" }
      ]
    }
  ]
}
```

## Limits

- **Full document rewrites:** Partial updates save network payload size and client-side bookkeeping, but they do not reduce index write cost. The underlying index rewrites the entire Lucene document block when applying updates. For more information, see [How sub-documents are stored](../explanation/document-blocks.md).
- **Selector matches:** A selector that matches no value returns the error `request:update:no_match` and does not create a new object. If a selector matches multiple objects, the update applies to all matched objects.
- **Document source requirement:** The index must store document sources. If `source` is set to `none`, update requests fail with `index:source:not_kept`.

## Troubleshooting

The update endpoint validates paths and returns specific error codes when a path cannot be applied:

| Error code | Cause | Action |
| --- | --- | --- |
| `request:update:no_match` | The selector matched no value in the document. | Ensure the document contains an object with the specified field and value before updating, or append a new item using `[]`. |
| `request:update:value_required` | A dot path reached into a list of objects without a selector. | Add a selector to identify which object in the list to update (for example, `variants[sku=V-2].price`). |
| `request:update:path_invalid` | The path string is malformed or has unclosed brackets. | Check the syntax of brackets, dots, and backslash escape characters in the path. |
| `request:update:path_unknown_field` | The top-level field name does not exist in the index definition. | Verify the field name against the index schema. |
| `request:update:selector_not_supported` | A selector was used on a field that does not hold objects or locale variants. | Remove the selector and update the field value directly. |
| `request:update:match_not_an_object` | A selector matched against inner fields of a non-object field. | Use selectors only on fields containing objects. |
| `request:update:locale_unknown` | The locale tag in brackets is not defined for the field. | Check the allowed locales in the index schema definition. |
| `request:update:add_not_multiple` | The `[]` syntax was used on a field not declared as `multiple`. | Remove `[]` to replace the single field value. |
| `request:update:add_reaches_inside` | A path attempted to set a sub-field on an appended item (for example, `variants[].price`). | Provide the complete object when appending with `[]`. |
| `request:update:not_an_object` | A dot path reached into a field that does not contain objects. | Check the field type definition in the index schema. |
| `index:source:not_kept` | The index does not store document sources (`source: none`). | Reindex with source storage enabled to use partial updates. |

## Related

- [Documents API](../reference/documents-api.md) - Path syntax, request schemas, response formats, and error codes.
- [Indexing documents](index-documents.md) - Send whole documents, load a dataset, and remove documents.
- [Using sub-documents](use-sub-documents.md) - Hold a list of values that are documents of their own, and search inside them.
- [Localizing fields](localize-fields.md) - Hold values in several languages and search them by locale.
- [How sub-documents are stored](../explanation/document-blocks.md) - Why a change to one sub-document rewrites the whole document.
