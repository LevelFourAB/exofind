# Admin API

The Admin API manages indexes under the `/v1alpha1/admin` path. Request and response bodies use JSON.

To index documents, use the [Documents API](documents-api.md). To manage API keys, see [Authentication](auth.md).

Every request requires credentials. For required permissions and information on why unauthorized indexes return `404 Not Found` instead of `403 Forbidden`, see [Authentication](auth.md).

## Endpoints

The Admin API provides the following endpoints:

```text
GET    /v1alpha1/admin/indexes                          # indexes the deployment holds
GET    /v1alpha1/admin/indexes/{name}                   # definition and status
PUT    /v1alpha1/admin/indexes/{name}                   # create or replace the definition
DELETE /v1alpha1/admin/indexes/{name}                   # remove an index or a generation
GET    /v1alpha1/admin/indexes/{name}/settings          # search settings as stored
PUT    /v1alpha1/admin/indexes/{name}/settings          # replace the search settings
PATCH  /v1alpha1/admin/indexes/{name}/settings          # change part of the search settings
DELETE /v1alpha1/admin/indexes/{name}/settings          # remove the search settings
POST   /v1alpha1/admin/indexes/{name}/actions/promote   # answer for this generation
POST   /v1alpha1/admin/indexes/{name}/actions/commit    # push pending changes
POST   /v1alpha1/admin/indexes/{name}/actions/pull      # fetch the latest state now
POST   /v1alpha1/admin/indexes/{target}/actions/reindex # start a job
GET    /v1alpha1/admin/indexes/{name}/actions/reindex   # job status
POST   /v1alpha1/admin/indexes/{name}/actions/reindex/cancel # stop a job

GET    /v1alpha1/admin/indexers                         # which node writes which index
GET    /v1alpha1/admin/reindexes                        # every job across the deployment

GET    /v1alpha1/admin/registry/audit                   # compare the registry with the storage
POST   /v1alpha1/admin/registry/actions/repair          # register what the storage holds
```

Each endpoint also has a generated page stating every field it accepts and returns, grouped as [Indexes](https://exofind.dev/api/operations/tags/indexes/), [Search settings](https://exofind.dev/api/operations/tags/search-settings/), [Reindexes](https://exofind.dev/api/operations/tags/reindexes/), [Indexers](https://exofind.dev/api/operations/tags/indexers/), and [Registry](https://exofind.dev/api/operations/tags/registry/).

Requests that modify an index (all endpoints except read requests and `pull`) run on the node that holds that index. The holder node can differ for each index. If another node receives the request, it forwards the request with the original credentials to the holder node and returns the holder's response.

When no node holds an index, the first candidate node that receives a write claims the index. If no candidate node is available to forward to, or if no candidate node sets `EXOFIND_NODE_ADDRESS`, the server returns `409 Conflict`. If a holder node does not respond, the server returns `502 Bad Gateway`.

## Names and generations

An index holds generations. Documents and definitions belong to a generation rather than directly to the index. One generation is live, which means the index serves queries from it. An index name without a generation specifier refers to the live generation.

The `{name}` parameter accepts two formats:

| Name format | Description |
|-------------|-------------|
| `products` | The index, serving from the live generation. |
| `products@2` | Generation `2` of `products`, whether live or not. |

The `@` character is reserved and cannot appear in an index name. A generation is only reachable through its parent index. The registry state shared across the deployment tracks which generations exist and which generation is live.

Updating an index definition in place does not reindex existing documents. If a definition change affects indexing—such as adding `matching`, changing an analysis chain, or editing a synonym set—create and populate a new generation, then promote it. For step-by-step instructions, see [Roll out a definition change](../how-to/roll-out-a-definition-change.md).

A `DELETE` request on an index deletes the index and all of its generations. A `DELETE` request on a generation deletes only that generation. Deleting the live generation fails with `index:generation:is_live` until you promote another generation. Deleting an index or generation removes it from the shared registry across the deployment; other nodes remove their local copies during their next registry read. What remote storage holds, the generations and for an index its search settings, is marked as deleted and removed by a background sweep once the mark is older than `EXOFIND_INDEXES_REMOVAL_GRACE`. An index or generation created again under the same name starts empty and does not pick old settings up. Within the grace period, a registry [repair](#repair) with `restore` brings it back. The request is served by the node writing the index and forwarded there when another node receives it. Both operations return `204 No Content`.

The `promote` action configures the index to serve from the specified generation. The change takes effect immediately on the receiving node and within `EXOFIND_INDEXES_REFRESH_INTERVAL` on all other nodes. To roll back a deployment, promote the previous generation.

## Index resource

A `GET` request on an index endpoint and every successful `PUT` request return an index resource:

```json
{
  "name": "products",
  "generation": "2",
  "live": true,
  "version": "9f2c1a0b3d4e5f60",
  "definition": { "...": "as stored" },
  "status": {
    "state": "USABLE",
    "readOnly": false,
    "indexer": { "node": "node-a-7f21", "address": "http://node-a:8080" },
    "luceneCompatibility": "CURRENT",
    "luceneCreatedMajor": 10
  },
  "generations": [
    { "name": "1", "live": false, "createdAt": "2026-08-01T09:14:22Z" },
    { "name": "2", "live": true, "createdAt": "2026-08-16T11:02:07Z" }
  ]
}
```

The resource contains the following fields:

- `name`: The name of the index.
- `generation`: The generation described in the response. When the request specifies only the index name, this is the live generation.
- `live`: A boolean indicating whether this generation is the live generation.
- `version`: An identifier for the definition, also returned in the `ETag` header. Pass this value in the `If-Match` header on `PUT` requests to prevent overwriting concurrent updates.
- `definition`: The active index definition. See [Field types](field-types.md). Presets are stored expanded; the response returns the expanded chain rather than the preset name.
- `status`: The observed state reported by the answering node. The API does not accept this object as input.
- `generations`: A list of all generations for the index, including name, live status, and creation timestamp (`createdAt`).

A `PUT` request that creates a resource returns `201 Created`. A `PUT` request that updates an existing resource returns `200 OK`. The request body must contain the full definition; any omitted settings are removed.

The target of a `PUT` request depends on the name format:

| `PUT` target | Create behavior | Update behavior |
|--------------|-----------------|-----------------|
| `products` | Creates the index with an initial generation named `1`. | Updates the definition of the live generation. |
| `products@2` | Creates generation `2` under an existing index. | Updates the definition of generation `2`. |

A newly created generation contains no documents and is not live; the index continues serving from the previous live generation. A `PUT products@2` request on a non-existent index returns `404 Not Found`.

When the target generation already holds documents, a `PUT` request is refused if the new definition changes how documents are indexed. Written but uncommitted documents count; deleted documents do not. Incompatible updates return `409 Conflict` with the error code `index:definition:incompatible`. The response includes one detail item per difference. Each detail carries a `path` naming the field that caused it, dotted for a field inside an object such as `variants.sku`, and one of the following error codes:

- `index:definition:usage_added`: A usage was enabled on an existing field.
- `index:definition:analysis_changed`: An analyzer chain, decompounding setting, stopword list, or synonym set changed for a text usage.
- `index:definition:setting_changed`: A field setting that determines how data was indexed changed.
- `index:definition:locale_fallback_changed`: The index locale fallback changed.
- `index:definition:source_added`: The index started to keep document sources.

On a generation that holds documents, refused changes include:

- Enabling `filter`, `sort`, `facet`, `matching`, `autocomplete`, `hierarchy`, `highlight`, `exact`, or `locales` on an existing field.
- Changing field `type`, `primaryKey`, or `multiple`.
- Changing the analyzer of a text usage (inline or via `analyzerRef`), changing `decompound`, or editing a stopword list or synonym set referenced by a field.
- Changing `keyword.caseFolding`, `sort.collation` on a string field, or `hierarchy.separator`.
- Changing a vector field's `dimensions`, `similarity`, `hnsw`, or `quantization`.
- Changing the `mode` of an object field between nested and flattened.
- Changing `locales.defaultLocale`, adding a locale to a field's `locales` list, or adding one to the `supported` list the index declares.
- Enabling or changing `localeFallback`.

Accepted changes on a generation that holds documents include:

- Adding or removing a field.
- Disabling a usage.
- Changing `stored`.
- Changing the index `source` mode.
- Changing `metadata`.
- Changing `ranking` (tie breakers and signals).
- Changing search-time settings: `weight`, `typoTolerance`, `lengthNormalization`, and sort `missing` placement.
- Changing document validation rules: `required`, `min`, and `max`.
- Setting an explicit default that matches the engine's default.

To force the update without reindexing, set the `allowStaleDocuments` query parameter to `true` (boolean, default `false`). Existing documents continue to serve queries as indexed until they are reindexed. The parameter has no effect on empty generations.

To apply an incompatible definition change to existing documents, reindex them into a new generation. See [Reindex into a new generation](../how-to/reindex-into-a-new-generation.md).

If an index definition contains settings from a newer API version that the current node does not recognize, reading the index returns `409 Conflict`. Updating such an index is rejected with `409 Conflict` and the error code `index:definition:unrepresentable`. To resolve these errors, send the request to a node running a version that supports the definition.

## Search settings

Search settings hold per-index configuration that affects how searches are answered, including ranking rules, synonym sets, words that are matched as they are spelled, and fields whose values are read out of the search text.

Search settings belong to the index name rather than to a generation. Promoting a generation preserves existing search settings. Search settings are stored as a separate object, so modifying them does not create a generation, does not change the index definition, and does not update the definition version. Requests that modify search settings run on the node that holds the index, like other modifying requests; `GET` requests are served by whichever node receives them.

A `GET` request returns the stored settings. If the index has no search settings and searches with its definition alone, the server returns `404 Not Found` with the error code `index:settings:not_found`:

```json
{
  "ranking": {
    "tieBreakers": [ { "field": "sales", "direction": "descending" } ],
    "signals": [ { "field": "sales", "saturation": { "pivot": 50 } } ]
  },
  "version": "9f2c1a0b3d4e5f60"
}
```

The response contains the following fields:

- `ranking`: The ranking searches run with instead of the definition's ranking, in the same shape as the definition's `ranking`. While present, it replaces the definition's ranking completely; an empty object turns ranking off. Supplying `signals` in a search request still replaces both. See [Relevance](../explanation/relevance.md).
- `synonyms`: Synonym sets applied to the text of a search, keyed by set name. See [Synonyms](#synonyms).
- `typoExclusions`: Words matched as they are spelled, keyed by list name. See [Typo exclusions](#typo-exclusions).
- `fields`: Settings that apply to one field, keyed by field name. See [Field settings](#field-settings).
- `version`: An identifier for the settings, also returned in the `ETag` header. Pass this value in the `If-Match` header on `PUT` and `PATCH` requests to prevent overwriting concurrent updates. A mismatch returns `412 Precondition Failed`.
- `unsupportedFeatures`: Present only when the answering node sets the settings aside because they use capabilities its version does not have. The node searches with the definition alone. Upgrade the node to put the settings in force.

A `PUT` request replaces the settings completely and returns them as stored. The server validates the ranking against the generation the index name answers from, using the same `index:ranking:*` error codes used to validate a definition's ranking. The server validates the fields named by `synonyms`, `typoExclusions`, and `fields` against the same generation.

A `DELETE` request removes the settings, returning the index to its definition's ranking, and returns `204 No Content`. Deleting settings that do not exist changes nothing and returns `204 No Content`.

A change takes effect for searches on the node that holds the index immediately and on every other node within `EXOFIND_SETTINGS_REFRESH_INTERVAL` (default 10 seconds). Until then, two nodes can rank the same query differently.

Search settings outlive generations. A generation promoted after the settings were written can lack a field used for ranking; searches then skip that entry rather than fail, so a promotion never depends on rewriting settings first.

### Synonyms

Search settings can configure query-time synonym sets under the `synonyms` field. Query-time synonym sets widen what a search asks for at query time, unlike index-time synonym sets defined in `resources.synonyms` of an index definition which widen document values during indexing.

Query-time synonym sets differ from index-time synonym sets in the following ways:

- **Target**: Query-time sets widen the search query. Index-time sets widen the indexed document values.
- **Scope**: Query-time sets apply to every document already in the index. Index-time sets apply only to documents indexed after the definition change.
- **Rollout**: Storing search settings takes effect immediately on the answering node and within `EXOFIND_SETTINGS_REFRESH_INTERVAL` (default 10 seconds) on other nodes, without requiring a new generation or reindexing.
- **Rules**: Do not put the same rule in both search settings and an index definition. A rule applied during indexing and again during search counts twice.

The `synonyms` field is an object keyed by set name:

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

A synonym set contains the following fields:

- `rules`: The rules of the set, using the same shape as `resources.synonyms` in an index definition: `equivalent` (a list of interchangeable terms) or `mapping` (one-way `from` and `to` terms).
- `fields`: An optional list of field names the set applies to, named as a search names them. If omitted, the set applies to every field searched as text (any field with a `matching` or an `autocomplete` usage).
- `boost`: A positive number specifying what a term added by the rules counts against the typed term. Default `0.8`. Values below `1` rank a document holding the typed term above one holding only a synonym. A value of `1` weighs synonyms and typed terms equally.

Rules apply after the field's analysis chain processes the text, and rule terms are analyzed through that same chain. Rules match the analyzed form of terms (such as stems produced by a stemmer). If an analysis chain leaves nothing of a term (such as a stopword or punctuation), that term matches nothing and the rule is omitted from the set while remaining rules still apply. Sets are evaluated per field and locale.

A rule can stand for several words, such as `ny` and `new york`:

- On a field with `matching` usage, the engine searches for the words in sequence, matching documents where the words appear next to each other.
- On a field with `autocomplete` usage, the engine requires all words in the reading to be present.
- In phrase searches, a phrase written with one side of a rule matches a document containing the other side.

The engine scores query-time synonyms as follows:

- Words added by a rule are counted together with the typed word as a single term, so a document matched through a rare synonym is not scored based on that synonym's rarity.
- `boost` weighs added terms against the typed term. Boost is not applied inside phrase searches.
- A whole-value match (`exact` setting of a text usage) is never widened.
- On an `autocomplete` usage, a rule takes effect only after the word is typed in full. Partial prefixes do not trigger whole-word synonym rules.

The server validates synonym sets against the generation the index name answers from at write time. Invalid settings return `400 Bad Request` with one of the following error codes:

- `index:settings:synonyms:unknown_field`: The set is applied to a field that does not exist in the index.
- `index:settings:synonyms:field_not_text`: The set is applied to a field that is not searched as text.
- `index:settings:synonyms:invalid_boost`: The `boost` value is not a positive number.
- `index:settings:synonyms:invalid_rule`: A rule is not exactly one kind (`equivalent` or `mapping`).

### Typo exclusions

Search settings can list words that are matched as they are spelled under the `typoExclusions` field. A word on a list is looked up as it was typed, however much typo tolerance the field it is searched in declares. Use a list for brand names and model codes that sit inside text you want typo tolerant otherwise.

Where typo tolerance is allowed remains part of the index definition, which declares it per field usage. See [Field types](field-types.md#string). A word list narrows that per word, and changing one takes effect without reindexing.

The `typoExclusions` field is an object keyed by list name:

```json
{
  "typoExclusions": {
    "brands": {
      "words": ["canon", "leica"],
      "fields": ["name"]
    }
  }
}
```

A word list contains the following fields:

- `words`: The words, as somebody would type them.
- `fields`: An optional list of field names the words are excluded in, named as a search names them. If omitted, the list covers every field searched as text (any field with a `matching` or an `autocomplete` usage).

The engine applies a word list as follows:

- Words are read through the analysis chain of each field they are excluded in, so a word matches the term that field wrote. A word the chain leaves nothing of, such as a stopword, excludes nothing, and a word the chain leaves several terms of excludes each of them.
- The list is read against the words a search was typed with. A word that is not listed keeps the tolerance of its field, including when a near reading of it lands on a listed word.
- A clause that turns typo tolerance off with `"typos": "off"` matches every word as it is typed, so a list changes nothing there.
- A generation promoted later can lack a field a list names. Searches then forgive mistakes in that field as the definition says, rather than fail.

A node whose version does not support the `typo_exclusions` capability sets the whole settings object aside and searches with the definition alone. The `unsupportedFeatures` field of the settings response lists the name.

The server validates word lists against the generation the index name answers from at write time. Invalid settings return `400 Bad Request` with one of the following error codes:

- `index:settings:typo_exclusions:unknown_field`: The list is applied to a field that does not exist in the index.
- `index:settings:typo_exclusions:field_not_text`: The list is applied to a field that is not searched as text.

### Field settings

Search settings can configure how searches read single fields under the `fields` field. Each entry specifies how a search reads one field. Every capability in an entry is disabled unless its object is present. An empty object enables the capability with the engine defaults.

The `fields` field is an object keyed by field name. A field inside an `object` field is keyed by its dotted path, such as `variants.colour`:

```json
{
  "fields": {
    "colour": { "interpret": {} },
    "brand": { "interpret": {} }
  }
}
```

A field settings entry contains the following fields:

- `interpret`: Reads the values the field holds out of the query text of a search in `user` mode, as a filter on the field. Carries no configuration options. See [Reading the values of a field](search-api.md#reading-the-values-of-a-field).

The engine applies `interpret` as follows:

- The field must be a `string` field with `filter` and `facet` and without `hierarchy`. A search matches words against the facet values of the generation answering the search, so a value is read as soon as a document holding it is indexed.
- A generation promoted later can lack the field, or define it without `filter` or `facet`. Searches then read the words as query text rather than fail.

A node whose version does not support the `interpret_values` capability sets the whole settings object aside and searches with the definition alone. The `unsupportedFeatures` field of the settings response lists the name.

The server validates field settings against the generation the index name answers from at write time. Invalid settings return `400 Bad Request` with one of the following error codes:

- `index:settings:fields:unknown_field`: The entry names a field that does not exist in the index.
- `index:settings:fields:interpret_unsupported`: The `interpret` setting is applied to a field that is not a `string` field with `filter` and `facet`, or that has `hierarchy`.

### Changing part of the search settings

```text
PATCH /v1alpha1/admin/indexes/{name}/settings
```

A `PATCH` request changes named parts of the search settings instead of replacing all of them.

The request body is a single change object where each key is a path naming a location in the settings:

```json
{
  "ranking.signals[field=sales].weight": 2.0,
  "ranking.tieBreakers": null
}
```

The change object applies the following update rules:

- A path with a value replaces the target value.
- A path set to `null` clears the target value.
- An omitted path leaves the existing value unchanged.

These are the rules a change object follows in [Changing some of the fields](documents-api.md#changing-some-of-the-fields) of a document, so a change is described the same way for both.

Paths use dot-joined field names. A path element can include a bracket selector (`[...]`) to pick list entries by what they hold rather than by position:

| Path | Description |
|---|---|
| `ranking` | The whole ranking object. |
| `ranking.signals` | The whole list of signals. |
| `ranking.signals[]` | A new signal added to the list. |
| `ranking.signals[field=sales]` | List entries whose `field` value equals `sales`. |
| `ranking.signals[field=sales].weight` | The `weight` field inside those matching signal entries. |
| `synonyms` | The whole synonyms object. |
| `synonyms.<name>` | A synonym set by name. |
| `synonyms.<name>.boost` | The boost value of a synonym set. |
| `synonyms.<name>.fields` | The list of target fields for a synonym set. |
| `synonyms.<name>.rules` | The list of rules for a synonym set. |
| `synonyms.<name>.rules[]` | A new rule added to a synonym set. |
| `typoExclusions` | The whole typo exclusions object. |
| `typoExclusions.<name>` | A word list by name. |
| `typoExclusions.<name>.fields` | The list of target fields for a word list. |
| `typoExclusions.<name>.words` | The words of a word list. |
| `typoExclusions.<name>.words[]` | A new word added to a word list. |
| `fields` | The whole field settings object. |
| `fields.<name>` | The settings of one field by name. |
| `fields.<name>.interpret` | The interpret configuration for a field. Set to `{}` to enable reading field values from query text, or `null` to disable. |

Inside bracket selectors, a backslash (`\`) escapes characters, such as `\]`. Objects along a path are created if they do not exist, but lists are not created.

A successful request returns `200 OK` with the search settings as stored and their new version in the `ETag` header.

The endpoint enforces the following rules:

- The server validates the merged ranking against the generation the index name answers from, using the same `index:ranking:*` error codes as a `PUT` request.
- An index with no stored settings is modified as if it had empty settings.
- Without an `If-Match` header, a change that conflicts with a concurrent update rebuilds on the newer version up to three times before returning `409 Conflict` with `index:settings:conflict`.
- With an `If-Match` header, a version mismatch returns `412 Precondition Failed` without retrying.
- If the stored settings contain capabilities that the answering node cannot describe, the request returns `409 Conflict` with `index:settings:unrepresentable`.

The endpoint returns `400 Bad Request` with one of the following `request:update:*` error codes if a path cannot be applied. These are the codes the [Documents API](documents-api.md#constraints-and-errors) reports for the same paths:

| Code | Condition |
|---|---|
| `request:update:path_invalid` | The key is not a valid path. |
| `request:update:path_unknown_field` | The path names a field that search settings do not have. |
| `request:update:no_match` | The selector matches no stored entry in the list. |
| `request:update:selector_not_supported` | The path specifies a selector on a field that is not a list. |
| `request:update:value_required` | The path targets a list without specifying an entry selector. |
| `request:update:not_an_object` | The path reaches inside a value that is not an object. |
| `request:update:add_reaches_inside` | The path sets a field on an entry being added (for example, `ranking.signals[].weight`). |
| `request:update:value_invalid` | The path specifies a value that the target field cannot hold. |

## Index states

The `status.state` field indicates the remote synchronization state as observed by the answering node:

| State | Description |
|-------|-------------|
| `NEEDS_PULL` | A newer remote state exists and has not been pulled yet. |
| `PULLING` | The node is fetching remote state. The state becomes `USABLE` when complete. |
| `USABLE` | The index is serving searches. On a read-only node, data is as current as the last pull. |
| `MODIFIED` | The index has local changes that are not yet pushed. Only writer nodes reach this state. |
| `PUSHING` | The node is pushing local changes. The state becomes `USABLE` when complete. |
| `UNSUPPORTED` | The definition requires engine features not present on this node version. Upgrade the node to resolve. |
| `INCOMPATIBLE` | The Lucene files are too old for this build to open. Reindexing into a new generation is required. |
| `CLOSED` | The index is closed on this node. A new request opens a fresh instance. |

The `status.readOnly` field indicates whether the answering node can modify the index. Only the node holding the index can modify it.

The `status.indexer` object identifies the holder node and the address where writes are forwarded. This field is omitted if no node holds the index, if the holder could not be read, if the holder provided no address, or on nodes using local storage. Data in this field can lag behind a node handover by a few seconds. For more information, see [Indexers](#indexers).

The `status.settingsUnsupportedFeatures` field lists the capabilities the index's [search settings](#search-settings) use that the answering node does not have. It is present only when the node has set the settings aside and searches with the definition alone.

## Lucene compatibility

The `status.luceneCompatibility` field indicates Lucene version compatibility. Lucene supports indexes created by the current major version and the preceding major version:

| Value | Description |
|-------|-------------|
| `CURRENT` | Created by the current major version. Compatible with the current and next Lucene major versions. |
| `ENDING` | Readable by the current version, but unsupported by the next Lucene major version. Reindex before upgrading across major versions. |
| `UNREADABLE` | Too old to open. The index reports the `INCOMPATIBLE` state and requires reindexing. |
| `UNKNOWN` | No version was recorded and no commit exists to determine the version (for example, on an empty index). |

The `status.luceneCreatedMajor` field contains the recorded Lucene major version. This field is omitted when compatibility is `UNKNOWN`.

## Actions

The API provides index action endpoints:

- `commit`: Pushes pending changes (documents and definition) to storage and returns the resulting status.
- `pull`: Fetches the latest remote state immediately instead of waiting for the refresh interval, and returns the resulting status.
- `promote`: Configures the index to serve from the specified generation and returns the updated index resource. The request path must specify a generation name; calling `promote` without a generation returns `index:generation:name_required`. Promoting the target of a `ready` reindex job finishes the job, while promoting before the job is ready is refused with `409 Conflict` (`reindex:target_busy`).

`commit` and `pull` act on the generation specified in the request path (or the live generation if omitted). Nodes automatically discover indexes, generations, and changes at the interval configured by `EXOFIND_INDEXES_REFRESH_INTERVAL`.

## Reindex

A reindex job populates a new generation by copying documents from an existing generation of the same index inside the engine, without resending documents from the client. For step-by-step instructions, see [Reindex into a new generation](../how-to/reindex-into-a-new-generation.md).

### Starting a job

To start a reindex job, send a `POST` request to `/v1alpha1/admin/indexes/{target}/actions/reindex`. This request requires the `indexes.reindex` permission.

The `{target}` must meet the following requirements:

- It must specify a generation by name (for example, `products@2`).
- The generation must already exist and must be empty.
- The generation must not be the live generation.
- The source generation must have a primary key and keep document sources (`source` mode not `none`).
- The primary key of the source and target generations must have the same field name and type.

If the target does not meet these requirements, the server returns `400 Bad Request`.

The request body accepts the following optional JSON fields:

- `from`: The generation to read documents from. Defaults to the live generation. Must belong to the same index as the target.
- `promote`: The promotion mode. `auto` (default) automatically promotes the target generation once it catches up with changes. `manual` pauses the job in the `ready` phase and keeps the target caught up until you manually promote it.

A successful request returns `202 Accepted` with the job record. The job runs in the background on the node holding the index.

An index can run at most one reindex job at a time. Starting a second job on an index returns `409 Conflict` with the error code `reindex:in_progress`. A finished job's record remains readable until a new job for that index replaces it.

### Creation parameter

You can create a generation and start a reindex job in one request by adding the `?reindex=auto` or `?reindex=manual` query parameter to a `PUT` request:

```text
PUT /v1alpha1/admin/indexes/products@2?reindex=auto
```

This creates the target generation with the definition in the request body and starts a reindex job reading from the live generation.

The `reindex` query parameter is one-shot and is not stored in the index definition. The server returns `400 Bad Request` if the request does not create a generation, such as on an initial index creation or on a `PUT` request that updates an existing generation's definition.

### Job record and phases

Reindex endpoints return a job record:

```json
{
  "index": "products",
  "target": "products@2",
  "source": "products@1",
  "phase": "copying",
  "promote": "auto",
  "documentsCopied": 125000,
  "sourceDocuments": 2400000,
  "backlog": 4100,
  "error": null,
  "startedAt": "2026-08-28T10:15:30Z",
  "updatedAt": "2026-08-28T10:16:02Z"
}
```

The job record contains the following fields:

- `index`: The name of the index.
- `target`: The generation being populated.
- `source`: The generation providing the source documents.
- `phase`: The current phase of the job.
- `promote`: The configured promote mode (`auto` or `manual`).
- `documentsCopied`: The number of confirmed documents copied to the target.
- `sourceDocuments`: The document count of the source generation when the copy started.
- `backlog`: The number of changed documents waiting to be replayed when the record was last written.
- `error`: The error message if the job failed, or `null`.
- `startedAt`: The timestamp when the job started.
- `updatedAt`: The timestamp when the job record was last updated.

A job progresses through the following phases:

| Phase | Description |
|---|---|
| `pending` | Accepted and waiting for a concurrency slot on the node. |
| `copying` | Streaming documents from the source to the target in primary key order. |
| `replaying` | Copying documents that changed in the source while the copy ran. |
| `ready` | Used only with `promote: manual`. Caught up and waiting for manual promotion, while continuing to catch up periodically. |
| `promoting` | Holding writes for the final drain and promotion. |
| `done` | Completed and promoted successfully. |
| `failed` | Stopped before promotion due to an error. The `error` field indicates the cause. |
| `cancelled` | Stopped before completion in response to a cancellation request. |

### Job status and fleet-wide listing

To check the status of a job on an index, send a `GET` request to `/v1alpha1/admin/indexes/{name}/actions/reindex`. If no job exists for the index, the server returns `404 Not Found` with the error code `reindex:not_found`.

To list every reindex job across the deployment, send a `GET` request to `/v1alpha1/admin/reindexes`.

Both endpoints require the `indexes.read` permission. Status and fleet-wide listings are served from a durable job record, so any node can serve the request and returns the same response.

### Cancelling a job

To stop an in-progress job, send a `POST` request to `/v1alpha1/admin/indexes/{name}/actions/reindex/cancel`. This requires the `indexes.reindex` permission.

Cancelling a job stops the background process and leaves the partially populated target generation in place. You can remove the generation with `DELETE /v1alpha1/admin/indexes/{target}`. Cancelling a finished job changes nothing.

### Target constraints and promotion

Direct document writes to a generation being filled by a reindex job return `409 Conflict` with the error code `reindex:target_busy`. Document writes to the live generation continue unaffected.

When a job configured with `promote: manual` reaches the `ready` phase, calling `POST /v1alpha1/admin/indexes/{target}/actions/promote` finishes the job by draining remaining changes, promoting the generation, and transitioning the job to `done`. Promoting a target generation before the job reaches the `ready` phase is refused with `409 Conflict` and the error code `reindex:target_busy`.

## Indexers

`GET /v1alpha1/admin/indexers` returns the candidate nodes competing to write indexes and the active writer claim for each index:

```json
{
  "candidates": [
    { "node": "node-a-7f21", "address": "http://node-a:8080", "expiresAt": "2026-08-21T10:15:30Z" },
    { "node": "node-b-90c4", "address": "http://node-b:8080", "expiresAt": "2026-08-21T10:15:32Z" }
  ],
  "claims": [
    { "index": "events", "node": "node-b-90c4", "address": "http://node-b:8080", "expiresAt": "2026-08-21T10:15:32Z" },
    { "index": "products", "node": "node-a-7f21", "address": "http://node-a:8080", "expiresAt": "2026-08-21T10:15:30Z" }
  ]
}
```

Any node can serve this endpoint from its local view of shared deployment state, including search-only nodes. The response can lag actual state by a few seconds.

This endpoint requires the `indexes.read` permission. If a credential lacks permissions for an index, that index is omitted from the `claims` list.

Indexes without an active claim are omitted from `claims` until a write operation assigns a writer. In candidate and claim entries:

- `address`: The target address for write forwarding. Omitted if the node did not set `EXOFIND_NODE_ADDRESS`.
- `expiresAt`: The timestamp when the entry expires unless renewed by the node.

On nodes using local storage, `candidates` and `claims` are empty. If a node cannot read shared state from storage, it returns `503 Service Unavailable`.

## Registry

The registry tracks which indexes and generations exist across the deployment. Registry endpoints compare the shared registry with remote storage and repair discrepancies.

Both endpoints are served by whichever node receives them and are never forwarded to the indexer. Both endpoints answer only in object storage mode. In local storage mode (`EXOFIND_STORAGE_MODE=local`), requests return `409 Conflict` with the error code `index:registry:audit_unavailable`.

### Audit

`GET /v1alpha1/admin/registry/audit` reads the registry and storage, comparing the two without changing either. This endpoint requires the `registry.audit` permission (deployment-scoped).

```json
{
  "registry": "PRESENT",
  "indexes": [
    {
      "name": "products",
      "registered": true,
      "live": "2",
      "generations": [
        { "name": "1", "registered": true, "stored": "SYNCED" },
        { "name": "2", "registered": true, "stored": "SYNCED" }
      ]
    },
    {
      "name": "analytics",
      "registered": false,
      "proposedLive": "1",
      "generations": [
        { "name": "1", "registered": false, "stored": "SYNCED" }
      ]
    }
  ],
  "unusable": []
}
```

The response contains the following fields:

- `registry`: The state of the registry object: `PRESENT`, `ABSENT` (no registry object), or `CORRUPT` (contents cannot be parsed).
- `indexes`: Every index named by the registry or found in storage, ordered by name. Each entry contains:
  - `name`: The name of the index.
  - `registered`: A boolean indicating whether the registry has an entry for the index.
  - `live`: The generation the index answers for. Omitted when unregistered or when no generation is live.
  - `proposedLive`: The generation that a repair with `promoteNewest` would make live. Omitted when none would be promoted.
  - `removedAt`: When the index was deleted, as an ISO 8601 timestamp. Present while the storage of the deleted index waits for the sweep that removes it; omitted otherwise.
  - `generations`: A list of generations found for the index. Each entry contains `name`, `registered` (boolean), `stored`, and `removedAt`.
    - `stored`: What storage holds under the generation:
      - `SYNCED`: Storage holds a manifest; nodes can pull and serve this generation.
      - `INCOMPLETE`: Storage holds a prefix without a manifest (such as an unfinished push or what an interrupted removal left of a deleted generation).
      - `MISSING`: The generation is registered, but nothing exists in storage.
    - `removedAt`: When the generation was deleted on its own, as an ISO 8601 timestamp. Present while its storage waits for the sweep that removes it. A generation of a deleted index carries the index's `removedAt` instead. Omitted otherwise.
- `unusable`: A list of storage prefixes whose names no index or generation may carry (as `index` or `index/generation`). A repair never registers these prefixes.

### Repair

`POST /v1alpha1/admin/registry/actions/repair` registers what storage holds. This endpoint requires the `registry.repair` permission (deployment-scoped).

The repair operation only adds entries. It registers every `SYNCED` generation that the registry does not name, and keeps existing entries as stored. Marked storage is skipped unless restored. It never deletes an index, a generation, or storage data. If the registry is absent, the repair writes it fresh. If the registry is corrupt, the repair replaces it with one rebuilt from storage.

The write is conditional and rebuilds on top of concurrent registry changes. The node that served the repair applies the repaired registry immediately. Other nodes pick up the changes within their registry refresh interval (`EXOFIND_INDEXES_REFRESH_INTERVAL`).

The request body accepts an optional JSON object:

```json
{
  "promoteNewest": true,
  "restore": [
    "books"
  ]
}
```

The request body contains the following fields:

- `promoteNewest`: A boolean. When `true`, each index created by the repair answers for its highest-numbered generation. Hand-named generations are not selected. Indexes that are already registered keep what they answer for.
- `restore`: A list of names of deleted indexes (`books`) or generations (`books@2`) whose storage the sweep has not removed yet. The repair removes the removal mark from each named entry and registers what it holds like any other unregistered storage. A name without a mark changes nothing.

A successful repair returns a summary of the changes:

```json
{
  "createdIndexes": [
    "books"
  ],
  "addedGenerations": [
    "books@1",
    "books@2"
  ],
  "promoted": [
    "books@2"
  ],
  "restored": [
    "books"
  ]
}
```

The response contains the following fields:

- `createdIndexes`: Index entries added to the registry.
- `addedGenerations`: Generations added to the registry, formatted as `index@generation`.
- `promoted`: Generations made live by the repair, formatted as `index@generation`.
- `restored`: Deleted indexes and generations whose removal mark the repair removed, in the order the request named them.

## Status codes

The Admin API returns the following status codes:

| Status code | Condition |
|-------------|-----------|
| `400 Bad Request` | The request body failed validation, or a `PATCH` of search settings named a place it cannot change (`request:update:*`). The response body details each validation error. See [Errors](errors.md). |
| `401 Unauthorized` | The request lacks valid credentials. See [Authentication](auth.md). |
| `403 Forbidden` | The credential does not have permission for the requested action on this index. |
| `404 Not Found` | The specified index or generation does not exist, a `PUT` request with `If-Match` targeted a non-existent resource, no reindex job exists for the index (`reindex:not_found`), the index has no search settings (`index:settings:not_found`), or the index falls outside the credential's allowed patterns. |
| `409 Conflict` | The index cannot be modified because no forwarding node is available, the index is synchronizing, a reindex job is already in progress (`reindex:in_progress`), the target generation is busy being reindexed (`reindex:target_busy`), a definition change is incompatible with documents in the target generation (`index:definition:incompatible`), the definition contains unrepresentable settings, a `PATCH` targeted search settings the node cannot describe (`index:settings:unrepresentable`), the index requires unsupported engine features, storage holds a generation under the new name that nothing deleted (`index:generation:storage_held`), writing to the registry failed, writing search settings failed (`index:settings:conflict`, `index:settings:io_error`, `index:settings:unavailable`), or a registry endpoint was called in local storage mode (`index:registry:audit_unavailable`). |
| `412 Precondition Failed` | The `If-Match` version does not match the current definition or search settings version. |
| `502 Bad Gateway` | The request was forwarded to the holder node, but the node did not respond. |
| `503 Service Unavailable` | The request conflicted with an index being closed to free resources (retrying reopens the index), or a node querying `/v1alpha1/admin/indexers` could not read the shared storage state. |
