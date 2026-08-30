# Changing synonyms without reindexing

This guide shows you how to configure query-time synonyms in an index's search settings. Use this guide when you want synonym rules to apply to existing documents immediately, without creating a new generation or reindexing your catalogue.

## Choosing index-time or query-time synonyms

Exofind supports two locations for defining synonym sets:

- **Index-time synonyms**: Defined in `resources.synonyms` in the index definition and referenced by an analysis filter. Use this for permanent rules, such as spelling variants that never change, where indexing pays the processing cost once. Changing an index-time set requires creating a new generation and reindexing existing documents.
- **Query-time synonyms**: Defined in `synonyms` in the index's search settings. Use this for merchandising sets, promotional terms, and seasonal campaigns where you need rules to take effect immediately on already indexed documents.

**Note:** Do not put the same rule in both the index definition and search settings. A rule applied during indexing and again when the search is processed counts twice in scoring.

## Prerequisites

Before configuring query-time synonyms, ensure you have:

- An index containing string fields configured with `matching` or `autocomplete` usage.
- Credentials with permission to read and update search settings on the index.

## Steps

1. Read the current search settings:
   Send a `GET` request to inspect existing settings and obtain the current version:
   ```text
   GET /v1alpha1/admin/indexes/products/settings
   ```
   If the index has no search settings, the endpoint returns `404 Not Found` with the error code `index:settings:not_found`. Treat the settings as empty.

2. Store a synonym set in search settings:
   Send a `PUT` request to write the settings. Include any existing search settings, such as `ranking`, because `PUT` replaces the entire settings object:
   ```text
   PUT /v1alpha1/admin/indexes/products/settings
   ```
   ```json
   {
     "synonyms": {
       "merch": {
         "rules": [
           { "equivalent": ["trainers", "sneakers"] },
           { "mapping": { "from": ["ny"], "to": ["new york"] } }
         ],
         "fields": ["name", "description"],
         "boost": 0.8
       }
     }
   }
   ```
   Configure the set with the following fields:
   - `rules`: The synonym rules. Use `equivalent` for interchangeable terms where each word matches the others, or `mapping` with `from` and `to` for one-way substitutions.
   - `fields` (optional): The list of fields the set applies to. If omitted, the set applies to every field searched as text (any field with `matching` or `autocomplete` usage).
   - `boost` (optional): A positive number determining how much a synonym term counts relative to the typed term. The default is `0.8`. Values below `1` rank documents containing the exact typed word higher than documents matching only through a synonym. A value of `1` treats synonyms and typed terms equally.

   The new settings take effect immediately on the answering node and within `EXOFIND_SETTINGS_REFRESH_INTERVAL` (default 10 seconds) on other nodes.

3. Verify that searches match the synonyms:
   Execute a search query using a term from your synonym rules and confirm that documents containing the mapped synonym appear in the results.

4. Tune the synonym set with PATCH:
   To modify specific rules, fields, or boost values without replacing the entire settings object, send a `PATCH` request:
   ```text
   PATCH /v1alpha1/admin/indexes/products/settings
   ```
   ```json
   {
     "synonyms.merch.boost": 1.0,
     "synonyms.merch.rules[]": {
       "equivalent": ["footwear", "shoes"]
     }
   }
   ```
   Common paths for modifying synonyms include:
   - `synonyms.<name>.boost`: Changes the boost value of the set.
   - `synonyms.<name>.fields`: Replaces the target fields list.
   - `synonyms.<name>.rules`: Replaces the entire list of rules.
   - `synonyms.<name>.rules[]`: Appends a new rule to the set.

## Troubleshooting synonym rules

If a synonym rule does not produce the expected search results, check the following causes:

- **The analysis chain removed the term**: Synonym terms pass through the target field's analysis chain. If the chain removes a term completely (such as a stopword or stripped punctuation), that term matches nothing, and the engine omits the rule from the set while leaving other rules active.
- **Partial word input in autocomplete fields**: On fields with `autocomplete` usage, synonym rules take effect only when a term is typed in full. Prefixes typed before the full word do not trigger synonym expansion.
- **Unsupported features on the node**: If a node runs a version that does not support `query_synonyms`, it sets the search settings object aside and searches using the index definition alone. Inspect the settings response or the index status to check if `unsupportedFeatures` or `settingsUnsupportedFeatures` lists `query_synonyms`.

## Related

- [Customize text analysis](customize-analysis.md) - Synonym sets that apply when values are indexed.
- [Exclude words from typo tolerance](exclude-words-from-typo-tolerance.md) - The other search setting that changes matching without a reindex.
- [Analysis](../reference/analysis.md) - Rule syntax and where a synonym filter sits in a chain.
- [Admin API](../reference/admin-api.md) - The search settings endpoints and their patch paths.
- [Relevance](../explanation/relevance.md) - Which layers a change reaches without reindexing.
