# Analysis

Analysis converts the text of a string field's `matching` or `autocomplete` usage into terms. You specify an analyzer with exactly one of `preset`, `custom`, or `named`:

```json
"analyzer": { "preset": "full_text" }
"analyzer": { "custom": { "filters": [ { "normalize": {} } ] } }
"analyzer": { "named": "prose" }
```

If you omit the analyzer, the engine builds analysis from the usage and the locale of the value.

An analyzer chain describes the indexing process. The engine derives the query analyzer from the indexing chain. Components that select words by locale, such as stopwords and stemming, use the locale of the value being analyzed unless you specify a locale.

## Presets

A preset specifies a predefined analyzer chain. The engine expands the preset before storing the index definition.

| Preset | Description |
|---|---|
| `preserve_terms` | Tokenizes and normalizes text, but keeps each word whole. Used for names, codes, and SKUs. |
| `full_text` | Tokenizes and normalizes text, removes stopwords, splits compound words, and stems words. Used for prose. |

## Named chains

A named chain references an analyzer defined under `resources` in the index definition. You use named chains to share analyzer configurations across fields. Validation fails if the specified name does not exist under `resources`.

## Custom chains

A custom analyzer chain defines character filters, a tokenizer, and token filters:

```json
"custom": {
  "charFilters": [ { "mapping": { "mappings": { "-": "" } } } ],
  "tokenizer": { "whitespace": {} },
  "filters": [ { "normalize": {} } ]
}
```

A custom chain contains the following properties:

- `charFilters`: An array of character filters applied to the raw text before tokenization, in order.
- `tokenizer`: The tokenizer that splits text into tokens. If omitted, the engine chooses a tokenizer based on the locale of the value (Unicode segmentation for most locales; language-specific segmentation for Chinese, Japanese, and Korean). Setting `icu` specifies Unicode segmentation directly.
- `filters`: An array of token filters applied to tokens, in order.

Each component is an object with one key that specifies the component type, for example `{ "whitespace": {} }`.

### Tokenizers

The following tokenizers are available:

| Tokenizer | Description |
|---|---|
| `icu` | Segments text based on Unicode rules. This is the default tokenizer. |
| `whitespace` | Splits text on whitespace characters. |
| `keyword` | Retains the entire input value as a single token. |
| `letter` | Splits text on non-letter characters. |

### Char filters

The following character filters are available:

| Filter | Options | Description |
|---|---|---|
| `htmlStrip` | None | Strips HTML and XML markup and keeps text between tags. |
| `mapping` | `mappings` | Replaces occurrences of each key with its value. |
| `patternReplace` | `pattern`, `replacement` | Replaces substrings that match a regular expression. |

### Token filters

The following token filters are available:

| Filter | Options | Description |
|---|---|---|
| `normalize` | `caseFolding` (boolean, default: `true`) | Applies Unicode normalization and case folding to make analysis case-insensitive. |
| `stopwords` | `locale`, `words`, `named` (at most one) | Removes frequent words. If no options are specified, uses stopwords for the locale of the value. `locale` specifies a locale code, `words` specifies a list of words, and `named` specifies a stopword list from `resources`. |
| `stemming` | `locale` (string, optional) | Reduces words to a shared root. If omitted, uses the stemmer for the locale of the value. |
| `asciiFolding` | `preserveOriginal` (boolean, default: `false`) | Converts non-ASCII characters to ASCII equivalents. If set to `true`, preserves the original non-ASCII token alongside the folded token. |
| `edgeNgram` | `minGram` (integer, default: `1`), `maxGram` (integer, default: `20`) | Generates prefix n-grams for tokens within the specified character lengths. |
| `ngram` | `minGram` (integer), `maxGram` (integer) | Generates substring n-grams for tokens within the specified character lengths. |
| `synonyms` | `named` (string, required) | Expands tokens with synonyms from a synonym set defined in `resources`. Applied when a value is indexed. |
| `decompound` | `locale` (string, optional) | Splits compound words into parts and retains the original compound word. See [Compound words](#compound-words). If omitted, uses the dictionary for the locale of the value. Applied at index time. |

## Resources

You define shared analysis components under `resources` in the index definition:

```json
"resources": {
  "analyzers": { "prose": { "preset": "full_text" } },
  "stopwords": { "brands": ["acme"] },
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

The `resources` object contains the following fields:

- `analyzers`: Named analyzer chains referenced by `"analyzer": { "named": "..." }`. Presets expand upon definition.
- `stopwords`: Named stopword lists referenced by `{ "stopwords": { "named": "..." } }`.
- `synonyms`: Named synonym sets referenced by `{ "synonyms": { "named": "..." } }`.

Validation fails if an analyzer references a resource name that is not defined under `resources`.

### Synonym rules

A synonym rule specifies one of the following structures:

- `equivalent`: An array of equivalent terms. Each term matches all other terms in the array. Multi-word terms match words in sequence.
- `mapping`: A one-way mapping object containing `from` and `to` arrays. A value containing a term in `from` matches searches for terms in `to`, but terms in `to` do not match searches for `from`.

A synonym set in `resources` is applied when a value is indexed and so reaches only documents indexed after it. A set can instead be applied to the text of a search through the index's search settings, which reaches documents already indexed and needs no reindex. For more information, see [Synonyms](./admin-api.md#synonyms) in the admin API reference.

## Compound words

The engine-built `matching` chain automatically decompounds words for the following locales: `da`, `de`, `fi`, `is`, `nl`, `no`, `nb`, `nn`, and `sv`.

Decompounding splits a word where hyphenation rules allow and matches parts against the locale dictionary. The engine indexes both the constituent parts and the complete compound word.

Decompounding applies at index time. A query for an individual part matches the compound document, while a query for the complete compound matches only documents containing the compound word.

Japanese and Korean handle compound segmentation through their tokenizers rather than through decompounding dictionaries.

To disable automatic decompounding for a usage, set `"decompound": "none"`. This setting does not affect Japanese or Korean tokenizer segmentation. A custom analyzer chain does not split compound words unless you include the `decompound` token filter.

Decompounding dictionaries are stored on the filesystem in the directory specified by `EXOFIND_LOCALE_DATA_DIRECTORY`. For more information, see [configuration](configuration.md).

If a node lacks dictionary data for a locale required by an index definition, the node rejects the index definition during validation.

## Locales

Analysis uses Lucene language components for stopwords, stemming, tokenization, and locale-specific normalization (such as Turkish dotless ı or Greek accents).

Icelandic stems by looking each word up in a full form list instead, because its inflection is too irregular for a rule-based stemmer. The list is locale data, so a node only supports `is` when the data is installed. For more information, see [configuration](configuration.md#locale-data).

For supported locale tags and component configurations, see the [locale reference](locales.md). Validation fails if an index definition specifies an unsupported locale tag.
