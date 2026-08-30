# Localizing fields

Configure and query localized fields to analyze, sort, and search field values according to specific language and regional rules.

## Prerequisites

Before you begin, ensure that you have:
- An index schema where you want to localize one or more string fields.
- Locale tags supported by the engine. For a list of supported tags, see the [locale reference](../reference/locales.md).

## Localizing a field

Follow these steps to declare locales on your fields, optionally configure fallback behavior for untranslated documents, and run localized searches.

1. **Declare locales in the field definition**: In your index schema, add a `locales` object to each field that requires language-specific values. Define `defaultLocale` and list all other supported tags in the `locales` array:

   ```json
   "name": {
     "type": "string",
     "locales": { "defaultLocale": "en", "locales": ["sv", "de"] },
     "matching": {}
   }
   ```

   - The `defaultLocale` applies to values that do not specify a locale.
   - The `locales` array defines which language variants the field accepts. If you submit a value with an undeclared locale, the engine rejects it with `index:update:locale_not_declared`.
   - Every locale tag must be valid according to the [locale reference](../reference/locales.md). If you use an unsupported tag, the schema fails validation.
   - A field inside an `object` field declares `locales` the same way. Each object value resolves its locales on its own, and fills its missing locales from its own given values.

2. **Configure fallback for untranslated documents (optional)**: By default, searching with a specific locale matches only documents translated into that locale. If you want untranslated documents to appear in search results, define a `localeFallback` chain in your schema:

   ```json
   {
     "localeFallback": { "chain": ["da", "en"] },
     "fields": {
       "name": {
         "type": "string",
         "locales": { "defaultLocale": "en", "locales": ["da", "no"] },
         "matching": {},
         "sort": {},
         "facet": {}
       }
     }
   }
   ```

   - The engine evaluates the `chain` in order. If a document lacks a translation in the search locale, the engine fills the value from the first available locale in the chain. For example, a document with both `da` and `en` values fills the missing `no` variant from `da`.
   - If you omit the `chain` property (`"localeFallback": {}`), each field falls back to its own `defaultLocale`.
   - If you specify a locale in `chain` that no field in the index uses, the engine rejects the configuration.
   - If a specific field should not generate fallback copies (such as large description fields), disable fallback on that field:

   ```json
   "description": {
     "type": "string",
     "locales": {
       "defaultLocale": "en",
       "locales": ["da", "no"],
       "fallback": "disabled"
     },
     "matching": {}
   }
   ```

3. **Query using a specific locale**: When sending a search request, include the `locale` parameter to read localized fields in that language:

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

- **Storage costs**: Fallback values are generated and stored during indexing for each missing locale. Fully translated fields require no extra storage. Fallback indexing duplicates the inverted index, doc values, term vectors, and stored values for the localized field, but does not duplicate the source document.
- **Re-indexing on changes**: Fallback values are generated at index time. Adding, changing, or removing a `localeFallback` chain affects only documents indexed after the change. To apply new fallback rules to existing documents, re-index them.

### Multiple values per locale

Localized fields support one value per locale without declaring `multiple`. To store multiple values within the same locale, declare `multiple` on the field.

## Related

- [Locales](../reference/locales.md) - The languages with rules, and what each one gets.
- [Analysis](../reference/analysis.md) - Chains, compound words, and collation.
- [Defining an index](define-an-index.md) - Declaring a field as localized.
- [Updating parts of documents](update-parts-of-documents.md) - Changing one locale without resending the rest.
- [Searching an index](search-an-index.md) - Choosing the locale a search runs in.
- [Customizing text analysis](customize-analysis.md) - Overriding the chain a locale gives a field.
