# Analysis

How the text of a usage - a string field's `matching` or `autocomplete` -
is turned into terms. An analyzer is given as exactly one of `preset`,
`custom` and `named`:

```json
"analyzer": { "preset": "full_text" }
"analyzer": { "custom": { "filters": [ { "normalize": {} } ] } }
"analyzer": { "named": "prose" }
```

Left out entirely, the engine builds analysis from the locale of the value
and the usage, which is the right choice for most fields.

A chain describes the indexing side; the engine derives the querying side
from it. Components that pick words by locale - stopwords and stemming -
follow the locale of the value being analyzed unless they name one, which is
what lets one chain serve a field whose values come in several locales.

## Presets

A preset names a chain the engine expands. It is expanded before it is
stored, so reading a definition back shows the chain it became - what a
preset means can then never shift under an index that already exists.

| Preset | Meaning |
|--------|---------|
| `preserve_terms` | Tokenizes and normalizes but keeps every word whole. For names, codes and SKUs. |
| `full_text` | Also drops stopwords, splits compounds and stems. For prose. |

## Named chains

`named` refers to a chain defined once in the `resources` of the index, for
chains shared between fields. A name pointing at nothing is refused at
validation.

## Custom chains

```json
"custom": {
  "charFilters": [ { "mapping": { "mappings": { "-": "" } } } ],
  "tokenizer": { "whitespace": {} },
  "filters": [ { "normalize": {} } ]
}
```

- `charFilters` run over the raw text before tokenization, in order.
- `tokenizer` splits the text into tokens. Left out, the engine picks for
  the locale of the value - Unicode segmentation for most locales, the
  locale's own segmentation for Chinese, Japanese and Korean. Naming `icu`
  outright pins Unicode segmentation instead.
- `filters` run over the tokens, in order.

Each component is written as an object with exactly one key selecting the
kind: `{ "whitespace": {} }`.

### Tokenizers

| Tokenizer | Meaning |
|-----------|---------|
| `icu` | Segment on the rules of Unicode. The engine default. |
| `whitespace` | Split on whitespace only. |
| `keyword` | Keep the whole value as one token. |
| `letter` | Split on anything that is not a letter. |

### Char filters

| Filter | Options | Meaning |
|--------|---------|---------|
| `htmlStrip` | | Strip HTML and XML markup, keeping the text between tags. |
| `mapping` | `mappings` | Replace occurrences of each key with its value. |
| `patternReplace` | `pattern`, `replacement` | Replace everything a regular expression matches. |

### Token filters

| Filter | Options | Meaning |
|--------|---------|---------|
| `normalize` | `caseFolding` (default true) | Unicode normalization, so the different ways of writing the same character compare as one. Folding case as part of it is what makes analysis case-insensitive. |
| `stopwords` | `locale`, `words`, `named` - at most one | Drop words that appear too often to tell documents apart. An empty object means the words of the locale of the value being analyzed; `locale` pins a locale's list, `words` gives exactly these words, `named` uses a list from the resources of the index. |
| `stemming` | `locale` | Reduce words to a shared root, so a search for one form finds the others. Absent means the locale of the value being analyzed. |
| `asciiFolding` | `preserveOriginal` (default false) | Fold characters outside ASCII to their closest ASCII equivalent, optionally keeping the unfolded token alongside. |
| `edgeNgram` | `minGram` (1), `maxGram` (20) | Index every prefix of a token between the given lengths, for matching a partially typed word. |
| `ngram` | `minGram`, `maxGram` | Index every substring of a token between the given lengths. |
| `synonyms` | `named` | Widen tokens with the words that mean the same thing, from a synonym set in the resources of the index. Applied when a value is indexed, not when it is searched. |
| `decompound` | `locale` | Split compound words into their parts, keeping the whole word alongside them - see [Compound words](#compound-words). Absent means the locale of the value being analyzed. Applied when a value is indexed, not when it is searched. |

## Resources

Things shared between fields are named once under `resources` on the index
definition and referred to by name:

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

- `analyzers` - chains by name, used with `"analyzer": { "named": "..." }`.
  A preset is expanded the same way it is on a field.
- `stopwords` - word lists by name, used with
  `{ "stopwords": { "named": "..." } }`.
- `synonyms` - synonym sets by name, used with
  `{ "synonyms": { "named": "..." } }`.

Every reference is resolved at validation, so a name pointing at nothing
never reaches indexing.

### Synonym rules

A rule is exactly one of:

- `equivalent` - terms that all mean the same thing, each matching every
  other. A term of several words matches them in sequence.
- `mapping` - one way: a value containing one of `from` also answers
  searches for any of `to`, but not the other way around.

Synonyms are applied when a value is indexed rather than when it is
searched, so changing a set only affects documents indexed from there on,
like every other analysis change.

## Compound words

The languages that write compounds as one word - `regnjakke`, `Winterjacke`
- lose searches for their parts unless the parts are indexed too, so the
engine-built `matching` chain splits compounds for every locale it has data
for: `da`, `de`, `nl`, `no`, `nb`, `nn` and `sv`. A word is split where the
language's hyphenation rules allow and a part is only kept when the
language's word list knows it; the whole word always stays alongside the
parts.

Splitting happens when a value is indexed and not when it is searched:
searching `jakke` finds `regnjakke` through the indexed part, while
searching `regnjakke` matches the whole word only, so a compound query does
not flood with every document holding a part. A changed data set therefore
only affects documents indexed from there on, like every other analysis
change.

`"decompound": "none"` on the usage turns it off where the parts would
mislead - fields matched on names and brands, or where a synonym set
already maps compounds to their parts. A custom chain is never split unless
it says so itself, with a `decompound` component.

The data lives outside the application in a directory named by
`EXOFIND_DECOMPOUND_DIRECTORY` - see
[configuration](configuration.md) - with the attribution of its sources
alongside the data. A node without the data for a locale indexes its
compounds whole; the required features of a definition record which
locales' data it needs, so such a node refuses the index rather than
quietly answering part searches with nothing.

## Locales

A locale hands analysis what Lucene ships for the language - stopwords, a
stemmer, a tokenizer for languages whose words Unicode segmentation cannot
find, and normalization for what Unicode case folding alone gets wrong, such
as the Turkish dotless ı or Greek accents. The supported tags are `ar`,
`bg`, `bn`, `ca`, `cs`, `da`, `de`, `el`, `en`, `es`, `et`, `eu`, `fa`,
`fi`, `fr`, `ga`, `gl`, `hi`, `hu`, `hy`, `id`, `it`, `ja`, `ko`, `lt`,
`lv`, `nb`, `nl`, `nn`, `no`, `pl`, `pt`, `ro`, `ru`, `sr`, `sv`, `ta`,
`te`, `th`, `tr`, `uk` and `zh`. A definition naming any other tag is
refused at validation.
