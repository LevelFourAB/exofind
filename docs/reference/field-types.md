# Field types

Every field in an [index definition](admin-api.md) has a `type` that defines
what data it can hold and which configuration options it supports. Fields are
structured as a tagged union:

```json
{
  "type": "string",
  "filter": {},
  "matching": { "highlight": {} }
}
```

Field usages are opt-in. Adding an empty configuration object enables a usage
with engine defaults. The engine stores only explicitly configured properties,
preserving default values across engine updates.

## Properties on every type

The following properties apply to all field types:

| Property | Type | Default | Description |
|---|---|---|---|
| `primaryKey` | boolean | `false` | Marks the field as the unique document identifier. Documents with matching primary keys overwrite existing documents. An index can have at most one primary key. Primary key fields must be `required` and cannot be `multiple`, locale-specific, or wildcard fields. |
| `required` | boolean | `false` | When `true`, the engine rejects documents that lack a value for this field. |
| `multiple` | boolean | `false` | When `true`, the field accepts multiple values in a single document. If `false`, the engine rejects documents containing multiple values for the field. |
| `stored` | boolean | `false` | When `true`, the engine stores field values to return in search results. This setting applies only when [Document source](#document-source) is set to `"source": "none"`. |
| `locales` | object | None | Configures locale-specific field values. See [Localize fields](../how-to/localize-fields.md). Sub-properties: `defaultLocale` (fallback locale for non-localized values), `locales` (list of supported locales), and `fallback` (set to `"disabled"` to exclude the field from [Locale fallback](#locale-fallback)). |
| `filter` | object | None | Enables filtering search results by exact field value. |
| `sort` | object | None | Enables sorting search results by field value. Sub-properties: `collation` (`"locale"` by default, or `"binary"` for byte order; strings only) and `missing` (`"last"` by default, or `"first"` to place documents without values first in ascending order). |
| `facet` | object | None | Enables value count aggregations. On numeric and timestamp fields, enables [range buckets](search-api.md#range-buckets). See [Facets](search-api.md#facets). |

## `string`

Represents text data.

| Property | Type | Default | Description |
|---|---|---|---|
| `keyword` | object | None | Configures exact-match normalization for filtering. Sub-properties: `caseFolding` (boolean, default `true`, allowing filters on `Fiction` to match `fiction`). |
| `matching` | object | None | Enables full-text search with analyzed terms. |
| `autocomplete` | object | None | Enables prefix matching for as-you-type search queries. |
| `hierarchy` | object | None | Enables path hierarchy matching (for example, `Men/Shoes/Running`). Sub-properties: `separator` (string, default `"/"`). Facets on hierarchy fields return nested counts per level (see [Counting down a tree](search-api.md#counting-down-a-tree)), and the [`under`](search-api.md#matchers) matcher filters to a level and all sub-levels. Changing `separator` requires reindexing existing documents. |

The `matching` and `autocomplete` objects support the following configuration properties:

| Property | Type | Default | Description |
|---|---|---|---|
| `analyzer` | string | Locale-derived | Specifies the text analyzer name. See [Analysis](analysis.md). If omitted, the engine generates an analyzer based on the field usage and locale. |
| `weight` | number | `1` | Relative score weight of hits in this field when querying across multiple fields. |
| `highlight` | object | None | Enables highlighted snippet extraction in search responses. See [Highlighting](search-api.md#highlighting). Text is stored for highlighting regardless of the `stored` property. Highlighting targets `matching` when defined; `highlight` on `autocomplete` takes effect only when `matching` is omitted. |
| `typoTolerance` | object | None | Enables typo tolerance. Sub-properties: `minLengthOneTypo` (integer, default `5`), `minLengthTwoTypos` (integer, default `9`), `prefixLength` (integer, default `1`), and `numbers` (object, default omitted). In `autocomplete`, two typos are permitted only when `minLengthTwoTypos` is explicitly set. Digit-only words require exact matches unless `numbers: {}` is set. Mixed alphanumeric words follow standard length thresholds. |
| `decompound` | string | Locale-derived | Controls compound word splitting. See [Compound words](analysis.md#compound-words). Set to `"none"` to disable splitting. Supported only when using engine-generated analyzers. |
| `exact` | object | None | Boosts documents where the query matches the full field value. Sub-properties: `boost` (number, default `2`). Adjusts ranking only without modifying hit counts or facet distributions. Exact matching applies analyzer normalization before comparison. |
| `lengthNormalization` | string | `"moderate"` | Controls field length penalty in ranking. Options: `"none"` (no penalty), `"moderate"` (standard prose normalization), and `"strong"` (full penalty for short fields such as titles). Changes take effect at search time without reindexing. |

## `boolean`

Represents boolean values (`true` or `false`). Boolean fields support `filter`.

## `int32`, `int64`, `float`, `double`

Represents numeric values of the specified width. Enabling `filter` supports both exact matches and range queries with the `range` matcher.

| Property | Type | Default | Description |
|---|---|---|---|
| `validation` | object | None | Sets allowed numeric bounds. Sub-properties: `min` and `max`. Documents containing values outside these bounds are rejected. |

## `timestamp`

Represents an instant in time formatted as an ISO 8601 date-time string with a timezone offset (for example, `Z` or `+02:00`).

Timestamps are stored and compared at millisecond precision. Values representing the same instant (such as `2024-05-01T12:00:00+02:00` and `2024-05-01T10:00:00Z`) are identical for filtering and sorting. Search results return the original string format provided during ingestion. Documents containing timestamps without timezone offsets are rejected.

## `geo_point`

Represents a geographic location defined by WGS 84 `latitude` and `longitude` coordinates.

- `filter`: Enables distance-based filtering with the `distance` matcher.
- `sort`: Enables sorting documents by distance from a target origin, ordered nearest first.

## `vector`

Represents an array of floating-point numbers searched by similarity using the `knn` search clause. Vector fields do not support `filter`, `sort`, `facet`, or `locales`. Vectors must be supplied in document payloads. See [Search by vector](../how-to/search-by-vector.md).

| Property | Type | Default | Description |
|---|---|---|---|
| `dimensions` | integer | None | Number of vector dimensions. Required. Cannot be modified after indexing documents. |
| `similarity` | string | `"cosine"` | Vector distance metric: `"cosine"`, `"dot_product"`, or `"euclidean"`. `"dot_product"` requires unit-length normalized vectors. |
| `hnsw` | object | None | Hierarchical Navigable Small World index configuration. Sub-properties: `m` (number of bi-directional links per node) and `efConstruction` (size of dynamic candidate list evaluated during index construction). |
| `quantization` | string | `"none"` | Vector compression method: `"none"`, `"int8"`, or `"int4"`. |

## `object`

Represents structured object values containing nested field definitions. Nested fields are referenced by dot notation (for example, `variants.price`) and support any non-object type.

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

| Property | Type | Default | Description |
|---|---|---|---|
| `fields` | object | None | Map of child field names to field definitions. Child fields cannot use the `object` type. |
| `mode` | string | None | Storage mode for multiple objects. Required when `multiple: true`. Must be omitted when `multiple: false`. Values: `"flattened"` or `"nested"`. |

### Object modes

- `flattened`: Indexes child fields directly into the parent document structure under their dot-notation paths. Values across different child fields are indexed independently, and object boundaries are not preserved.
- `nested`: Retains each object instance as an isolated sub-document. Queried using the [`nested` clause](search-api.md#nested). Supports retrieving matched object instances (see [Matched values](search-api.md#matched-values)) or returning matched sub-documents as independent hits (see [What a hit stands for](search-api.md#what-a-hit-stands-for)). See [Use sub-documents](../how-to/use-sub-documents.md).

Single object fields (`multiple: false`) are always indexed as flattened objects.

### Constraints and restrictions

- **Supported child field options:** `filter`, `matching`, `autocomplete`, `facet`, `validation`, `required`, and `multiple`. Setting `required: true` on a child field requires that field in every object instance.
- **Sorting on child fields:** Supported in single objects and in `nested` mode (see [Ordering by a value inside an object](search-api.md#ordering-by-a-value-inside-an-object)). Rejected in `flattened` mode with `index:field:object:flattened_sort`.
- **Unsupported options:** `primaryKey`, `highlight`, `locales`, `stored`, wildcard field names, and nested `object` types cannot be used inside object fields.
- **Top-level object options:** An `object` field cannot configure `filter`, `sort`, `facet`, `locales`, or `stored`, and cannot use wildcard characters in its name.
- **Document source requirement:** Object fields are returned in search results only when full documents are preserved in [Document source](#document-source).

### Errors

| Error code | Condition |
|---|---|
| `index:field:object:mode_required` | `multiple: true` is set on an `object` field without specifying `mode`. |
| `index:field:object:mode_without_multiple` | `mode` is specified on an `object` field where `multiple` is `false` or omitted. |
| `index:field:object:flattened_sort` | `sort` is configured on a child field inside a `flattened` object field. |

### Feature requirements

- `nested` objects require the `type.object` feature flag, and `type.object.usages` when configuring child usages beyond `filter`.
- `flattened` objects require the `type.object.flattened` feature flag.

## Wildcard fields

Field names can contain `*` to define dynamic field schemas (for example, `metadata.*`).

- The `*` wildcard matches exactly one path segment (`metadata.*` matches `metadata.color`, but not `metadata.a.b`).
- Explicit field definitions take precedence over wildcard definitions.
- When multiple wildcard definitions match a field name, the pattern with the longest literal prefix takes precedence (for example, `a.b*` takes precedence over `a.*` for `a.bc`). When literal prefixes have equal length, the shorter pattern takes precedence.

## Document source

By default, an index stores the complete source payload for each document.

To disable source document storage, set `"source": "none"` in the index definition. When source storage is disabled:

- Search results return only fields configured with `stored: true`.
- Modifying `"source"` applies to subsequently indexed documents without modifying existing indexed data.

### Errors

| Error code | Condition |
|---|---|
| `index:query:usage_not_enabled` | A query requests a specific field that has `stored: false` when `"source": "none"`. |
| `index:query:source_not_kept` | A query requests an `object` field when `"source": "none"`. |

Queries that do not specify field lists return all stored fields available in the document.

## Ranking

The `ranking` configuration sets tie-breaking rules and signal score multipliers. See [Relevance](../explanation/relevance.md).

```json
"ranking": {
  "tieBreakers": [
    { "field": "name", "direction": "ascending" }
  ]
}
```

### Tie breakers

Tie breakers define secondary sort criteria when search scores are equal:

- Evaluated in sequence after query-level sort criteria or relevance scoring.
- Target fields must have `sort` enabled.
- `direction`: `"descending"` (default) or `"ascending"`.

### Signals

Signals multiply relevance scores using values from sortable fields:

```json
"ranking": {
  "signals": [
    { "field": "purchases", "saturation": { "pivot": 50 } },
    { "field": "published", "decay": { "halfLife": 604800 }, "weight": 0.5 }
  ]
}
```

A signal computes a value between `0` and `1` and multiplies the relevance score by `1 + weight * shape`:

- Missing field values contribute `0` to the calculation.
- A signal can increase a document score by at most `weight`.
- `weight`: number, default `1`.

| Shape | Applicable types | Description |
|---|---|---|
| `saturation` | Numeric types (`int32`, `int64`, `float`, `double`) | Computes `value / (value + pivot)`. Reaches `0.5` at `pivot`. Values below `0` evaluate to `0`. `pivot` is required and must be greater than `0`. |
| `decay` | `timestamp` | Halves the multiplier every `halfLife` seconds of age. Values dated at or after the current time evaluate to `1`. `halfLife` is required and must be greater than `0`. |

Signals are evaluated at search time without reindexing. Signals apply only when sorting by relevance. Query-level signals override index-level signals (see [Search API signals](search-api.md#signals)).

## Locale fallback

Configures fallback locale resolution when a document lacks a value for a requested locale:

```json
"localeFallback": { "chain": ["da", "en"] }
```

- `chain`: Ordered list of locale codes. If omitted, fields fall back to their configured `defaultLocale`.
- Each locale entry in `chain` must be supported by at least one field in the index.
- Fallback values are written during document indexing and analyzed using the target fallback locale.
- Locale-specific fields participate in fallback unless configured with `"locales": { "fallback": "disabled" }`.
- Modifying `localeFallback` applies to newly indexed documents without rewriting existing data.
- See [Localize fields](../how-to/localize-fields.md).
