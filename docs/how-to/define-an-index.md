# Define an index

An index is defined by sending the definition you want it to have. The same
request creates the index and updates it later on, so keep the definition in
version control and apply it whenever it changes.

## Send the definition

```http
PUT /v1alpha1/admin/indexes/products
Content-Type: application/json

{
  "metadata": {
    "owner": "search-team"
  },
  "fields": {
    "id": {
      "type": "string",
      "primaryKey": true,
      "required": true
    },
    "name": {
      "type": "string",
      "sort": {},
      "matching": {
        "highlight": {},
        "weight": 2,
        "typoTolerance": {}
      },
      "autocomplete": {}
    },
    "description": {
      "type": "string",
      "matching": {
        "analyzer": { "preset": "full_text" },
        "highlight": {}
      }
    },
    "category": {
      "type": "string",
      "filter": {},
      "facet": {}
    },
    "published": {
      "type": "boolean",
      "filter": {}
    },
    "metadata.*": {
      "type": "string",
      "filter": {}
    }
  }
}
```

The definition is desired state: it is sent in full, and anything left out
is removed. A rejected definition reports every problem it found, so they
can be fixed in one go - see [Errors](../reference/errors.md).

A `PUT` changes what happens to documents indexed from then on, and reaches
nothing already in the index. Once an index holds documents, a change that
alters how their values would have been indexed - a field gaining `matching`,
a different analyzer, an edited synonym set - needs [a rollout through a new
generation](roll-out-a-definition-change.md) instead.

## Pick what each field is for

Each field has a `type` selecting what it can hold, and every way of using
it is opt-in - including the configuration is what enables it, an empty
object being the defaults of the engine.

The ways that read the same whatever the field holds:

- `filter` narrows results down to documents holding a value. Filtering
  compares exact values; for strings case is folded away first, so filtering
  on `Fiction` also finds `fiction` (`keyword.caseFolding` turns that off).
  For numbers and timestamps it also means ranges, and for geo points
  distance.
- `sort` orders results by the field. Strings compare by the rules of the
  locale rather than by their bytes, so `Äpple` sorts before `Zebra` instead
  of after it; set `sort.collation` to `binary` to compare bytes.
- `facet` counts how many documents share each value - the basis of a list
  of filters to pick from, requested through the search API's
  [`facets`](../reference/search-api.md#facets). On number and timestamp
  fields the same counts can fall into
  [range buckets](../reference/search-api.md#range-buckets) instead, for a
  price or date facet.

The ways that depend on how text is analyzed belong to the string type:
`matching` searches with a query, `autocomplete` matches what a user has
typed so far, and `hierarchy` reads values as paths through a tree such as
`Men/Shoes/Running` - which is what a category navigation needs, as a facet
on such a field counts
[one level at a time](../reference/search-api.md#counting-down-a-tree).
[Field types](../reference/field-types.md) lists every option on every type.

## Enable more than one value

A field holds a single value unless it declares `"multiple": true`, and a
document giving a single-valued field several values is refused. A locale
specific field holds one value per locale either way - a value per
translation is not several values - so `multiple` there means several values
within the same locale.

## Group fields that belong together

A product with a list of variants, each holding a color and a price, is not
well served by two multi-valued fields - `colors: [red, blue]` next to
`prices: [30, 10]` cannot say that red is the expensive one. Declare an
`object` field instead, holding the fields each variant has:

```json
"variants": {
  "type": "object",
  "multiple": true,
  "fields": {
    "color": { "type": "string", "filter": {} },
    "price": { "type": "double", "filter": {} }
  }
}
```

A search then matches one variant at a time through the `nested` clause of
the [search API](../reference/search-api.md#nested), so "red and under 20"
means one variant that is both. Give the fields inside `sort`, `facet` and
`matching` as well, and the variant that matched can order the product,
count it and rank it - "the cheapest red variant, cheapest first" is a
`nested` clause and a sort on `variants.price`. What the fields inside may
declare is listed in [Field types](../reference/field-types.md#object), and
[Use sub-documents](use-sub-documents.md) walks indexing, searching and
changing the values.

## Cover many names with one field

A field name can contain `*` to define several fields at once, such as
`metadata.*` above. The `*` stands for exactly one name segment. When
patterns overlap, the one with the longer literal prefix wins - the exact
rules are in [Field types](../reference/field-types.md#wildcard-fields).

## Decide how much of a document is kept

By default the index keeps a copy of every document as it was given, so
results come back holding the values that were indexed, and a document can
be indexed again from the index itself after its definition changes. When
documents are large and results only need to identify them, turn it off and
mark the fields to bring back:

```json
{
  "source": "none",
  "fields": {
    "id": { "type": "string", "primaryKey": true, "required": true },
    "name": { "type": "string", "stored": true }
  }
}
```

Changing this does not rewrite what is already indexed - it decides what is
written from there on, and both kinds keep reading.

## Put the thing named what was typed above the things that mention it

A search for `iphone 15` has to return the phone, not the case listed for it.
Say so on the field that holds the name:

```json
"name": {
  "type": "string",
  "matching": {
    "exact": {},
    "lengthNormalization": "strong"
  }
}
```

`exact` writes the value a second time as one term and lifts a document whose
whole value is what was searched for. It only reorders: the lift reaches
documents the words had already found, so hit counts and facets are the same
either way. Whole means whole, so it takes effect once the last word has been
typed out.

`lengthNormalization` is the same question by degrees - how much the words a
value holds beyond the ones searched for count against it. `"strong"` suits a
field holding names, where the leftover words are the difference between the
thing asked for and something related to it; `"none"` suits a field holding
everything a document is about, where a fuller value is not a worse answer.
Nothing is written for it, so it can be changed without reindexing.

Declare `exact` where the value of the field is the identity of the document.
On a description or a list of tags it lifts whatever happens to be short.

## Break ties in the order of results

```json
"ranking": {
  "tieBreakers": [
    { "field": "name", "direction": "ascending" }
  ]
}
```

Tie breakers are appended after whatever ordering a search asks for, so they
decide the order within ties without ever disturbing it. Each field has to
be defined for sorting.

## Rank what sells, or what is new, above the rest

A tie breaker only reaches documents that scored the same. To make a value the
documents carry count towards relevance itself, declare a signal:

```json
"ranking": {
  "signals": [
    { "field": "purchases", "saturation": { "pivot": 50 } },
    { "field": "published", "decay": { "halfLife": 604800 }, "weight": 0.5 }
  ]
}
```

The score is multiplied by `1 + weight * shape`, where the shape is a number
between zero and one: `saturation` reads a number as how far it is above the
pivot, `decay` reads a timestamp as how long ago it was, halving every
`halfLife` seconds. A product bought as often as the pivot is worth half of
what the signal can give; one holding no value at all is left exactly as it
matched.

Pick the pivot from the catalogue - roughly the count a product you would call
popular has, not the count of the most popular one. The field has to be
defined for sorting, which is where the value is read from. Nothing is written
into the documents, so a signal can be changed, weighted differently or taken
away without reindexing, and a search can carry its own to try one out first.

## Update without overwriting someone else

The response to a `PUT` or `GET` carries the version of the definition as an
`ETag`. Send it back as `If-Match` when updating to be told that someone
else changed the index in the meantime - `412 Precondition Failed` - rather
than overwriting their change:

```http
PUT /v1alpha1/admin/indexes/products
If-Match: "9f2c1a0b3d4e5f60"
```

## Where the request has to go

Definitions can only be changed on the node holding the indexer role. Any
other node redirects the request there with a `307`, so send it anywhere and
follow redirects - the details are in the
[admin API reference](../reference/admin-api.md).
