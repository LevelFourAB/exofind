# Field types

Every field in an [index definition](admin-api.md) has a `type` that selects
what it can hold and which options exist on it. Fields are written as a
tagged union:

```json
{
  "type": "string",
  "filter": {},
  "matching": { "highlight": {} }
}
```

Every way of using a field is opt-in, and including its configuration is
what enables it - an empty object being the defaults of the engine. Only
what is sent is stored, so a value left out can be told apart from one set
to its default, and defaults stay owned by the engine.

## Properties on every type

| Property | Meaning |
|----------|---------|
| `primaryKey` | This field identifies the document; documents with the same primary key replace each other. At most one per index. A primary key has to be `required`, cannot be `multiple`, locale specific or a wildcard field. |
| `required` | A document without a value for this field is refused. |
| `multiple` | The field can hold several values in the same document. A document giving several values to a field without it is refused. |
| `stored` | The value is kept so it can be returned in results. Only meaningful when the index does not keep [full documents](#document-source) anyway. |
| `locales` | Values are locale specific - see [Localize fields](../how-to/localize-fields.md). `defaultLocale` is assumed for values carrying no locale; `locales` lists the other locales the field holds values in; `fallback` is `disabled` to keep this field out of the index's [locale fallback](#locale-fallback). |
| `filter` | Enables narrowing results to documents holding a given value. |
| `sort` | Enables ordering results by the field. `collation` is `locale` (default, order by the rules of the locale) or `binary` (order by bytes); only meaningful for strings. `missing` places documents without a value `first` or `last` (default) when ascending. |
| `facet` | Enables counting how many documents share each value - and, on numbers and timestamps, counting into [range buckets](search-api.md#range-buckets). See [Facets](search-api.md#facets). |

## `string`

Text. The ways of searching that depend on analysis live here:

| Property | Meaning |
|----------|---------|
| `keyword` | How values are normalized before exact comparison, which is what filtering does. `caseFolding` defaults to true, so filtering on `Fiction` also finds `fiction`. |
| `matching` | Enables searching with a query, the text analyzed into terms. |
| `autocomplete` | Enables matching what a user has typed so far, by prefixes of the text. |
| `hierarchy` | Enables reading values as paths through a tree, such as `Men/Shoes/Running`. `separator` (`/`) is what stands between one level and the next. A facet on the field then counts a level at a time and answers the counts nested - see [Counting down a tree](search-api.md#counting-down-a-tree) - and the [`under`](search-api.md#matchers) matcher narrows to a level and everything below it. The separator is part of how values were written, so changing it on an index holding documents needs those documents indexed again. |

`matching` and `autocomplete` share one shape:

| Property | Meaning |
|----------|---------|
| `analyzer` | How the text is analyzed - see [Analysis](analysis.md). Left out, the engine builds analysis from the locale of the value and the usage. |
| `weight` | How much a hit in this field counts relative to hits in other fields when text is searched across several. Defaults to 1. |
| `highlight` | Enables answering searches with highlighted fragments of the text, see the [search API](search-api.md#highlighting). Stores the text alongside what is indexed, whatever the field's `stored` says. A search highlights the usage its text search targets - `matching` when the field has it, `autocomplete` only when it does not - so `highlight` on `autocomplete` only takes effect on a field without `matching`. |
| `typoTolerance` | Enables matching words despite typing mistakes, in both usages - under `autocomplete` the mistake may sit in the word being typed, so `stockhlm` still completes to Stockholm. `minLengthOneTypo` (5) is the shortest word that may contain one typo, `minLengthTwoTypos` (9) two, and `prefixLength` (1) how many leading characters have to match exactly. Under `autocomplete` a word carries two typos only where `minLengthTwoTypos` is given: the prefixes that field writes make a second mistake cost several times the first, on every long word rather than only the misspelled ones, and it finds little. A word of digits alone is matched exactly however long it is - a number one digit off is a different number rather than a misspelling, so `2024` does not find `2025` and an article number does not find its neighbour; `numbers: {}` fuzzes them too. A word mixing digits with anything else follows the length rules like any other word. |
| `decompound` | Whether the engine-built chain splits compound words, see [Analysis](analysis.md#compound-words). Absent means the engine decides by the locale of the value; `"none"` never splits, for fields where the parts of a name would mislead. Only usable when the engine builds the chain - a chain given through `analyzer` says itself whether it splits. |
| `exact` | Enables ranking a value the search matched whole above one that merely holds the same words, so a search for `iphone 15` puts the product named that above the case listed for it. `boost` (2) is how much a whole-value match adds, on the scale of what a hit in the field counts. Only ranking changes: the lift reaches documents the words had already found, never new ones, so hit counts and facets stay as they were. The value is compared after the normalization of the field's own chain - case folding, Unicode forms and accent folding where the chain asks for it - and whole means whole, so a search whose last word is still being typed reaches it only once that word is finished. |
| `lengthNormalization` | How much the length of a value counts against it, which is what ranks the same words covering a short value above them sitting inside a long one. `"none"` leaves length out, for a field holding everything a document is about, where a fuller value is not a worse answer; `"moderate"` (the default) counts it the way it reads for prose; `"strong"` counts it fully, for a field holding names. Read where the search runs, so changing it reorders results without reindexing. |

## `boolean`

True or false. Nothing to analyze, so the only way to search it is `filter`.

## `int32`, `int64`, `float`, `double`

Numbers of the named width. For a number `filter` also means ranges - the
`range` matcher walks values between two bounds rather than only equal ones.

| Property | Meaning |
|----------|---------|
| `validation` | The values the field accepts, as `min` and `max`. A document with a value outside the bounds is refused. |

## `timestamp`

A point in time. A value is an ISO 8601 date and time with an offset - `Z`
or one like `+02:00` - filtered and ordered as the instant it names, at
millisecond precision. The offset only says where the clock was read, so
`2024-05-01T12:00:00+02:00` and `2024-05-01T10:00:00Z` are the same value;
what was given is what results return. A value without an offset is refused,
as it names no instant at all.

## `geo_point`

A point on the earth, as a WGS 84 `latitude` and `longitude`. Searched by
nearness rather than by value: `filter` enables the `distance` matcher, and
`sort` enables ordering by distance from an origin, nearest first.

## `vector`

A vector of floats, searched by similarity with the `knn` clause rather than
by value - so `filter`, `sort` and `facet` mean nothing for it and are
refused, as is `locales`. The vectors arrive with the documents; the engine
never produces them. [Search by vector](../how-to/search-by-vector.md) walks
defining one and searching it.

| Property | Meaning |
|----------|---------|
| `dimensions` | How many components every vector has. Required, and fixed once documents have been indexed. |
| `similarity` | How near two vectors are judged to be: `cosine` (default), `dot_product` or `euclidean`. Dot product only orders sensibly when every vector is unit length - a promise the caller makes about the model the vectors come from. |
| `hnsw` | Parameters of the HNSW graph the vectors are searched through: `m` neighbours per node, `efConstruction` candidates considered while building. Both trade indexing time and space for recall. |
| `quantization` | How much precision stored vectors give up for space: `none` (default), `int8` or `int4`. |

## `object`

A field whose values are objects, described by `fields` the same way the
index describes its documents. The fields inside go by the dotted path
through the object, such as `variants.price`, and can hold any non-object
type. Declared `multiple`, the field holds a list of values:

```json
{
  "type": "object",
  "multiple": true,
  "mode": "nested",
  "fields": {
    "color": { "type": "string", "filter": {}, "required": true },
    "price": { "type": "double", "filter": {} }
  }
}
```

How the values relate to the document is `mode`:

- `flattened` folds the fields of every value into the document itself. They
  are ordinary fields of the index under their dotted path - filtered,
  matched, counted and covered by a text search that names no fields - and
  which value a field came from is not kept, so conditions on two fields may
  be satisfied by two different values.
- `nested` keeps every value as one unit, matched through the [`nested`
  clause](search-api.md#nested) of the search API, so a search can ask that
  several conditions hold inside the same value - a variant that is both red
  and under 20, not a product that is red in one variant and cheap in
  another. [Use sub-documents](../how-to/use-sub-documents.md) walks
  defining one, indexing values and searching them.

A field holding a single value is one unit whichever way it is kept, so the
mode is required exactly when the field is `multiple`: a list leaving it out
is refused with `index:field:object:mode_required`, and a single object
naming one with `index:field:object:mode_without_multiple`. A single object
is always flattened - the shape for a group of fields that belong together,
such as a `dimensions` holding a width and a height.

The fields inside may use `filter`, `matching`, `autocomplete`, `facet`,
`validation`, `required` and `multiple` in either mode. `required` means
required in every value. `sort` works where a value can stand for the
document - inside a single object, or inside a `nested` list where [the
ordering reads the values the search
matched](search-api.md#ordering-by-a-value-inside-an-object); inside a
`flattened` list it is refused with `index:field:object:flattened_sort`,
because the values are independent and no one of them stands for the
document. Refused in both modes are the usages that only mean something for
a document of the index - `primaryKey` and `highlight`, which reads the text
back out of the document a fragment is shown for - as are `locales`,
`stored`, wildcard names and objects inside objects. The object itself takes
no `filter`, `sort`, `facet`, `locales` or `stored` either, and its name
cannot contain a wildcard.

An index with `nested` objects needs the `type.object` feature, and
`type.object.usages` besides when anything beyond `filter` is used inside
one; an index with flattened objects needs `type.object.flattened`. A node
without the feature refuses the index rather than writing the values in a
shape the definition does not mean.

Values come back in results through the [document source](#document-source),
so an index with `"source": "none"` does not return them.

## Wildcard fields

A field name can contain `*` to define several fields at once, such as
`metadata.*`. The `*` stands for exactly one name segment - `metadata.*`
covers `metadata.color` but not `metadata.a.b`.

A name an exact field defines always resolves to that field. Among wildcard
patterns that both cover a name, the one with the longer literal prefix
wins, so with `a.*` and `a.b*` defined the name `a.bc` gets the settings of
`a.b*`. When the prefixes are the same length the shorter pattern wins. This
precedence is part of the contract and safe to build on.

## Document source

An index keeps a copy of every document as it was given, so results come
back holding the values that were indexed - of the types they were indexed
as, and all of them where a field has several. It is also what a document
can be indexed again from when the definition changes and the values are no
longer available from wherever they first came from.

Setting `"source": "none"` on the definition turns that off, which is worth
doing when documents are large and results only need to identify them. What
comes back is then whatever the fields ask to be `stored`. Changing the
setting does not rewrite the documents already indexed - it decides what is
written from there on, and both kinds keep reading.

A search naming a field the definition has left no way to answer for is
refused rather than answered with the field missing: `stored` was never asked
for (`index:query:usage_not_enabled`), or the field is an object, which holds
no value of its own to store (`index:query:source_not_kept`). Objects are
therefore filtered, sorted and counted but never returned by an index keeping
no copy. A search naming no fields at all is not refused - it brings back
whatever each document happens to hold.

## Ranking

How these fit together with the text scoring and with a search's own clauses
is in [Relevance](../explanation/relevance.md).

An index can declare how ties in the order of results are broken:

```json
"ranking": {
  "tieBreakers": [
    { "field": "name", "direction": "ascending" }
  ]
}
```

The tie breakers are appended after whatever ordering a search asks for -
its own sort, or relevance when it gives none - so they decide the order
within ties without ever disturbing the order the search asked for. They
are applied in order until one of them tells two documents apart. Each field
has to be defined for sorting, and `direction` defaults to `descending`, the
way recency and popularity read.

### Signals

Tie breakers only reach documents that scored the same. `signals` is the
graded half: a value the documents carry, multiplied into their relevance so
that a popular or a recent document ranks above an equally good match that is
neither.

```json
"ranking": {
  "signals": [
    { "field": "purchases", "saturation": { "pivot": 50 } },
    { "field": "published", "decay": { "halfLife": 604800 }, "weight": 0.5 }
  ]
}
```

A signal reads the value from a field defined for sorting, shapes it into a
number between zero and one, and multiplies the score by `1 + weight * shape`.
Two properties follow from that shape and are the reason for it: a document
holding no value contributes nothing rather than being multiplied away, and a
signal can lift a document by at most its `weight` however far its value runs,
so it never drowns out how well the document matched. `weight` defaults to 1.

Exactly one shape has to be given, and which one a field can answer for
follows from its type:

| Shape | For | Meaning |
|-------|-----|---------|
| `saturation` | numbers | `value / (value + pivot)` - half at `pivot`, approaching but never reaching one above it. The shape for a count with no ceiling. A value below zero counts as zero. `pivot` has to be given and above zero. |
| `decay` | timestamps | Halves every `halfLife` seconds of age. A value dated now or later counts full. `halfLife` has to be given and above zero. |

Signals are read where the search runs rather than written into the documents,
so changing them takes effect without reindexing. They only mean something
where relevance is the ordering: a search that gives a `sort` of its own is
ordered by that field and reads no score. A search can also bring signals of
its own, which replace these - see the [search API](search-api.md#signals).

## Locale fallback

An index can fill the locales a document holds no value in, so a search
naming a locale finds the documents that were never translated into it
rather than missing them:

```json
"localeFallback": { "chain": ["da", "en"] }
```

The chain is tried in order for each locale a document left empty, and a
field skips the entries it holds no values in. Leaving `chain` out sends
every field to its own `defaultLocale`. Each entry has to be a supported
locale that some field of the index holds values in.

The value is written when the document is indexed, analyzed and collated as
the locale it fills, and is invisible in results. Every locale specific
field takes part unless it sets `"locales": { "fallback": "disabled" }`.
Changing the chain decides what is written from there on rather than
rewriting the documents already indexed.

See [Localize fields](../how-to/localize-fields.md) for what it costs and
when to turn it off per field.
