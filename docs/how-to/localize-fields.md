# Localizing fields

Configure and query localized fields to analyze, sort, and search field values according to specific language and regional rules.

## Prerequisites

Before you begin, ensure that you have:
- An index schema where you want to localize one or more string fields.
- Locale tags supported by the engine. For a list of supported tags, see the [locale reference](../reference/locales.md).

## Localizing a field

Follow these steps to declare the locales of the index, opt fields in, optionally configure fallback behavior for untranslated documents, and run localized searches.

1. **Declare the locales of the index**: Add a `locales` object beside `fields`. Set `defaultLocale` to the default locale tag, and list the additional locales in `supported`:

   ```json
   {
     "locales": { "defaultLocale": "en", "supported": ["sv", "de"] },
     "fields": {
       "name": { "type": "string", "locales": {}, "matching": {} },
       "sku": { "type": "string", "filter": {} }
     }
   }
   ```

   - Set `"locales": {}` on a field to opt in. The field then supports every locale declared on the index.
   - Leave `locales` unset to keep a field unlocalized. Unlocalized fields store a single value, such as a SKU or an identifier.
   - Values without a locale tag use the `defaultLocale`.
   - The engine validates document values against the declared locales. If a document carries a value in an undeclared locale, the engine rejects the document with `index:update:locale_not_declared`.
   - Every locale tag must be valid according to the [locale reference](../reference/locales.md). Unsupported tags fail schema validation.
   - The engine expands the index declaration onto individual fields before storing the schema. Reading the definition back returns `defaultLocale` and `locales` on each field.
   - To add a locale later, add the tag to `supported`. Because the engine rejects changes to `supported` on a generation with documents, roll out the change through [a new generation](roll-out-a-definition-change.md).

2. **Narrow a field to fewer locales (optional)**: To restrict a field to a subset of the index locales, list the allowed tags in `only`:

   ```json
   "description": {
     "type": "string",
     "locales": { "only": ["en"] },
     "matching": {}
   }
   ```

   - Every tag in `only` must be declared in the index `locales` object.
   - The `only` list must include the field default locale. To use a default other than the index default, set `defaultLocale` on the field:

   ```json
   "legalNotice": {
     "type": "string",
     "locales": { "defaultLocale": "sv", "only": ["sv"] }
   }
   ```

   - Narrowing a field reduces the number of fallback copies generated for untranslated documents.
   - Sub-fields inside an `object` field opt in and narrow the same way. Each object value resolves locales independently and fills missing values from its own data.

3. **Declare locales on individual fields (alternative)**: If an index does not declare index-level locales, define them on each field instead. Set `defaultLocale` and list the other tags in the `locales` array:

   ```json
   "name": {
     "type": "string",
     "locales": { "defaultLocale": "en", "locales": ["sv", "de"] },
     "matching": {}
   }
   ```

   - Use this format when fields in the same index require unrelated sets of languages.
   - Do not combine index-level declarations and per-field lists. An index that declares root `locales` rejects per-field locale lists with `index:field:locales:list_with_declaration`.

4. **Configure fallback for untranslated documents (optional)**: By default, localized searches match only documents translated into the search locale. To include untranslated documents in search results, define a `localeFallback` chain:

   ```json
   {
     "locales": { "defaultLocale": "en", "supported": ["da", "no"] },
     "localeFallback": { "chain": ["da", "en"] },
     "fields": {
       "name": {
         "type": "string",
         "locales": {},
         "matching": {},
         "sort": {},
         "facet": {}
       }
     }
   }
   ```

   - The engine evaluates `chain` in order. If a document lacks a value for the search locale, the engine copies the value from the first available locale in the chain. For example, a document with `da` and `en` values fills a missing `no` variant from `da`.
   - If you omit `chain` (`"localeFallback": {}`), each field falls back to its own `defaultLocale`.
   - If `chain` specifies a locale that no field uses, the engine rejects the schema.
   - To prevent a field from generating fallback copies, set `"fallback": "disabled"` on that field:

   ```json
   "description": {
     "type": "string",
     "locales": { "fallback": "disabled" },
     "matching": {}
   }
   ```

5. **Query using a specific locale**: When sending a search request, include the `locale` parameter to read localized fields in that language:

   ```json
   {
     "query": [ { "type": "text", "text": "äpple" } ],
     "locale": "sv"
   }
   ```

   - The engine matches the request tag to the closest declared locale. For example, `sv-SE` matches `sv`, and `nb-NO` matches `no`. The same matching rules apply when indexing values.
   - If a field does not contain the requested locale variant, it falls back to its `defaultLocale`.
   - A search query evaluates one locale at a time. If you need field values across multiple locales, send a separate search request for each locale.

## Confirming the result

To verify that localization works, inspect the search response. The localized field returns an object containing the matched variant tag and its value:

```json
"document": {
  "id": "1",
  "name": { "sv": "röda löparskor" }
}
```

Verify the following details in your search responses:
- The field key in the document object reflects the matched schema variant tag (for example, a value indexed under `sv-SE` returns under `sv`).
- If a document has no value in the requested or fallback locale, the engine omits the field from the search hit.
- If a document value was populated through `localeFallback`, it returns under the requested locale key (for example, an English name returned under `"sv"`).

## Reference

### Locale analysis and collation rules

Declaring a locale applies the following analysis and sorting behaviors:

- **Segmentation**: Text in languages without whitespace separation (such as Chinese, Japanese, and Korean) uses language-specific segmentation without additional analyzer configuration.
- **Normalization**: Normalization applies language-specific rules beyond standard Unicode case folding, including Turkish dotless ı, Greek accents, and elided articles.
- **Compound words**: Languages that combine compound words (such as Danish, Dutch, German, Norwegian, and Swedish) index individual parts alongside the full compound word. For example, a search for `jakke` matches `regnjakke`. Japanese and Korean apply compound decomposition through segmentation. For more information, see [compound words](../reference/analysis.md#compound-words).
- **Collation**: Sorting with `collation: "locale"` (the default) orders terms by the rules of the locale, so characters such as `å` sort where a reader of that locale expects rather than after `z`.

### Fallback index storage and updates

- **Storage costs**: The engine generates and stores fallback values at index time for each missing locale. Fully translated fields require no extra storage. Fallback indexing duplicates the inverted index, doc values, term vectors, and stored values for the localized field, but does not duplicate the source document.
- **Narrowed fields**: A field narrowed with `only` generates fallback copies only for its supported locales, rather than every locale declared on the index. Narrow fields that contain large text values to reduce index storage.
- **Reindexing on changes**: Fallback values are generated at index time. Adding, changing, or removing a `localeFallback` chain affects only documents indexed after the change. To apply new fallback rules to existing documents, reindex them.

### Multiple values per locale

Localized fields support one value per locale without declaring `multiple`. To store multiple values within the same locale, declare `multiple` on the field.

## Related

- [Locales](../reference/locales.md) - The languages with rules, and what each one gets.
- [Analysis](../reference/analysis.md) - Chains, compound words, and collation.
- [Defining an index](define-an-index.md) - Declaring a field as localized.
- [Updating parts of documents](update-parts-of-documents.md) - Changing one locale without resending the rest.
- [Searching an index](search-an-index.md) - Choosing the locale a search runs in.
- [Customizing text analysis](customize-analysis.md) - Overriding the chain a locale gives a field.
