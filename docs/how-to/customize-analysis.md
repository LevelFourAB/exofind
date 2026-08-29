# Customizing text analysis

This guide shows you how to configure custom text analysis for string fields. Use this guide when a field requires analysis behavior that differs from the default engine analysis.

## Prerequisites

Before customizing text analysis, ensure you have:
- A schema with string fields configured for `matching` or `autocomplete`.

## Steps

1. Configure an analyzer on your field's `matching` or `autocomplete` setting:
   - **Preset analyzer**: To use a predefined analyzer chain, specify a preset:
     - `preserve_terms`: Tokenizes and normalizes, but keeps every word whole. Use this for names, codes, and SKUs, where stemming `running` to `run` is undesirable.
     - `full_text`: Drops stopwords, splits compounds, and stems words. Use this for prose.
     ```json
     "sku": {
       "type": "string",
       "matching": {
         "analyzer": { "preset": "preserve_terms" }
       }
     }
     ```
   - **Custom analyzer pipeline**: When neither preset fits, define a custom pipeline with `charFilters`, a `tokenizer`, and `filters`:
     ```json
     "matching": {
       "analyzer": {
         "custom": {
           "charFilters": [ { "mapping": { "mappings": { "-": "" } } } ],
           "tokenizer": { "whitespace": {} },
           "filters": [ { "normalize": {} } ]
         }
       }
     }
     ```
     If you omit the tokenizer, the engine selects the tokenizer for the locale of each value. The pipeline describes indexing, and the engine derives query analysis from it. For component details, see the [analysis reference](../reference/analysis.md).
   - **Shared analyzer**: To share an analyzer across multiple fields, define the chain under `resources.analyzers` and reference it by name:
     ```json
     "resources": {
       "analyzers": {
         "prose": { "preset": "full_text" }
       }
     },
     "fields": {
       "description": { "type": "string", "matching": { "analyzer": { "named": "prose" } } },
       "review":      { "type": "string", "matching": { "analyzer": { "named": "prose" } } }
     }
     ```

2. Add custom stopword or synonym filters (optional):
   - **Stopwords**: To share a stopword list across fields, define it under `resources.stopwords` and reference it in a filter:
     ```json
     "resources": {
       "stopwords": { "brands": ["acme"] }
     }
     ```
     ```json
     "filters": [ { "stopwords": { "named": "brands" } } ]
     ```
     To inline a stopword list for a single field, use `words`. To use the stopword list for the locale of the value being analyzed, supply an empty `{ "stopwords": {} }`.
   - **Synonyms**: Define synonym rules under `resources.synonyms`:
     ```json
     "resources": {
       "synonyms": {
         "cars": {
           "rules": [
             { "equivalent": ["car", "automobile"] },
             { "mapping": { "from": ["ny"], "to": ["new york"] } }
           ]
         }
       }
     }
     ```
     A field uses the set only when a `synonyms` filter appears in a custom chain on its `matching` or `autocomplete` usage, because a preset accepts no extra filters:
     ```json
     "description": {
       "type": "string",
       "matching": {
         "analyzer": {
           "custom": {
             "filters": [
               { "normalize": {} },
               { "synonyms": { "named": "cars" } },
               { "stemming": {} }
             ]
           }
         }
       }
     }
     ```
     An `equivalent` rule makes each listed word match the others. A `mapping` rule is one-way, so a value containing `ny` matches searches for `new york` but not the reverse. Write the rule terms in the form the tokens have where the filter sits, which is lowercase after `normalize` and whole words before `stemming`. For the full placement rules, see [applying a synonym set to a field](../reference/analysis.md#applying-a-synonym-set-to-a-field). Synonym sets in `resources` apply when values are indexed. For synonyms on search text, see [change synonyms without reindexing](./change-synonyms-without-reindexing.md).

3. Reindex existing documents:
   Analysis configurations and synonym rules in `resources` apply when values are indexed rather than when queried. Reindex existing documents so that they use the updated analysis settings. To apply synonyms to search text without reindexing, see [change synonyms without reindexing](./change-synonyms-without-reindexing.md).

## Confirming the configuration

Read back the schema definition from the engine to verify the configuration.

**Note:** The engine expands preset definitions into their full chains before storing them. Reading back the definition shows the expanded chain that was stored.
