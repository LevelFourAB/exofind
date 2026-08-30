# Excluding words from typo tolerance

This guide shows you how to configure typo exclusions in an index's search settings. Use this guide when you want specific words, such as brand names and model codes, to match exactly as spelled inside fields that otherwise forgive typing mistakes.

## Choosing field-level tolerance or search-time exclusions

Exofind supports two ways to control spelling tolerance:

- **Field-level typo tolerance**: Configured using [`typoTolerance`](../reference/field-types.md) on a text usage in the index definition. Use this to turn off spelling tolerance entirely when no word in the field should be read fuzzily.
- **Search-time typo exclusions**: Defined under `typoExclusions` in the index's [search settings](../reference/admin-api.md#typo-exclusions). Use this when only some words in a field must keep their exact spelling while remaining words continue to tolerate typing mistakes.

Because Exofind evaluates typo tolerance at search time, neither change requires reindexing documents or creating a new generation. Storing search settings takes effect immediately on the answering node and within `EXOFIND_SETTINGS_REFRESH_INTERVAL` (default 10 seconds) on other nodes, without changing the index definition or its version.

## Prerequisites

Before configuring typo exclusions, ensure you have:

- An index containing string fields configured with `matching` or `autocomplete` usage and `typoTolerance` declared.
- Credentials with the `indexes.read` permission to read settings and the `settings.write` permission to change them.

## Steps

1. Read the current search settings:
   Send a `GET` request to inspect existing settings:
   ```text
   GET /v1alpha1/admin/indexes/products/settings
   ```
   If the index has no search settings, the endpoint returns `404 Not Found` with the error code `index:settings:not_found`. Treat the settings as empty.

2. Store a word list in search settings:
   Send a `PUT` request to write the settings. Include any existing settings, such as `ranking` or `synonyms`, because `PUT` replaces the entire settings object:
   ```text
   PUT /v1alpha1/admin/indexes/products/settings
   ```
   ```json
   {
     "typoExclusions": {
       "brands": {
         "words": ["canon", "leica"],
         "fields": ["name", "description"]
       }
     }
   }
   ```
   Configure the list with the following fields:
   - `words`: The words to match as spelled, written as somebody would type them.
   - `fields` (optional): The list of field names the exclusions apply to. If omitted, the list covers every field searched as text (any field with `matching` or `autocomplete` usage).

   The server validates the named fields against the active generation at write time. If a field does not exist or is not searched as text, the server returns `400 Bad Request` with `index:settings:typo_exclusions:unknown_field` or `index:settings:typo_exclusions:field_not_text`.

   The new settings take effect immediately on the answering node and within `EXOFIND_SETTINGS_REFRESH_INTERVAL` (default 10 seconds) on other nodes.

3. Verify that searches enforce exact spelling:
   Execute a search query containing an excluded word and confirm that results match only documents containing the exact spelling rather than fuzzy variations.

4. Add words later with PATCH:
   To add words or update fields without replacing the entire settings object, send a `PATCH` request:
   ```text
   PATCH /v1alpha1/admin/indexes/products/settings
   ```
   ```json
   {
     "typoExclusions.brands.words[]": "nikon"
   }
   ```
   Common paths for modifying typo exclusions include:
   - `typoExclusions.<name>.fields`: Replaces the target fields list.
   - `typoExclusions.<name>.words`: Replaces the entire list of words.
   - `typoExclusions.<name>.words[]`: Appends a single word to the list.

## Troubleshooting typo exclusions

If typo exclusions do not produce the expected search behavior, check the following causes:

- **The analysis chain removed or split terms**: Excluded words pass through the analysis chain of each target field. If the chain removes a word completely (such as a stopword), that word excludes nothing. If the chain produces multiple terms, the engine excludes each term.
- **Misspelled queries reaching listed words**: Typo exclusions are checked against the words a search was typed with. A search for `canonn` still finds a document holding `canon`, because the word that was typed is not on the list. A list decides how a listed word is looked up, not which words reach it.
- **Field removed in a newer generation**: Search settings outlive generations. If a newly promoted generation lacks a field named by the exclusion list, searches in that field forgive mistakes as declared in the index definition rather than failing.
- **Unsupported features on the node**: If a node runs a version that does not support the `typo_exclusions` capability, it sets the search settings object aside and searches using the index definition alone. Check if `unsupportedFeatures` in the settings response or `status.settingsUnsupportedFeatures` in the index status lists `typo_exclusions`.
- **Query disables typos entirely**: A search clause sent with `"typos": "off"` matches every word exactly as typed. Typo exclusion lists change nothing for queries where typo tolerance is already turned off.

## Related

- [Search API](../reference/search-api.md) - The `typos` option on a clause.
- [Changing synonyms without reindexing](change-synonyms-without-reindexing.md) - The other search setting that changes matching without a reindex.
- [Defining an index](define-an-index.md) - Declaring typo tolerance on a field.
- [Admin API](../reference/admin-api.md) - The search settings endpoints and their patch paths.
- [Find out why a result ranked where it did](explain-a-result.md) - Checking which words a clause matched on.
