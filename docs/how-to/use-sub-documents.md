# Use sub-documents

A sub-document is a value with fields of its own held inside another document:
the variants of a product, the lines of an order, the opening hours of a
place. Defining a field to hold them, indexing them, searching inside them,
ordering and counting by them and changing them is what this guide walks
through.

What they are for is being matched one at a time. Fields beside each other
cannot say which value goes with which - `colors: [red, blue]` next to
`prices: [30, 10]` does not say that red is the expensive one - while a search
over sub-documents asks for a variant that is *both* red and under 20 rather
than a product that is red in one variant and cheap in another.

The field is called `object` where the index is defined, holds sub-documents
when its `mode` is `nested`, and is reached through the `nested` clause where
it is searched. Below all three, every value is indexed as a document of its
own and joined back to the document holding it, and three things the rest of
this guide keeps running into follow from that: values are matched a value at
a time, results are always documents, and a document is written and deleted
along with its values.

Matching one value at a time is also the only reason to pay for any of it. An
object that is only structure - values whose fields never have to hold
together - takes `"mode": "flattened"` instead, which folds its fields into
the document as ordinary fields under the dotted path, searched with no
`nested` clause and no join. What each mode allows is listed in [`object`
fields](../reference/field-types.md#object); this guide is about `nested`.

## Define the field

```json
"variants": {
  "type": "object",
  "multiple": true,
  "mode": "nested",
  "fields": {
    "color": { "type": "string", "filter": {}, "facet": {}, "required": true },
    "size": { "type": "string", "filter": {}, "multiple": true },
    "price": { "type": "double", "filter": {}, "sort": {}, "facet": {} },
    "material": { "type": "string", "matching": {} }
  }
}
```

`multiple` on the object is what makes it a list, and a list has to say its
`mode` - flattened, `color = red` and `price < 20` could be satisfied by two
different variants, so which way a list answers is never defaulted. A field
without `multiple` holds one value, refuses a document giving it several with
`index:update:not_multiple`, and is never a sub-document: a single value is
one unit whichever way it is kept, so it always flattens and takes no `mode` -
the shape for a group of fields that belong together, such as a `dimensions`
holding a width and a height.

The fields inside are defined the way the index's own are, and may use
`filter`, `matching`, `autocomplete`, `sort`, `facet`, `validation`,
`required` and `multiple`. `required` means required in every value. Refused
inside are the usages that only mean something for a document of the index -
`primaryKey`, and `highlight`, which reads text back out of the document a
fragment is shown for - as are `locales`, `stored`, objects inside objects and
wildcard names. The object itself holds no value of its own, so `filter`,
`sort`, `facet`, `locales` and `stored` on it are refused too.

An index with `nested` objects needs the `type.object` feature, and
`type.object.usages` besides when anything beyond `filter` is used inside one,
so a node without them refuses the index rather than indexing the values with
none of what those usages write.

Giving an inner field a usage it did not have reaches only values indexed from
then on, the same as anywhere else - on an index already holding documents,
roll it out into [a new generation](roll-out-a-definition-change.md).

## Index documents

A value is written as a JSON object and a list of them as an array, in the
same document as everything else:

```http
POST /v1alpha1/indexes/products/documents
Content-Type: application/x-ndjson

{"id": "1", "name": "Rain jacket", "variants": [{"color": "red", "size": ["S", "M"], "price": 15.0}, {"color": "black", "price": 25.0}]}
```

What a JSON object means depends on the field it was given to - the same shape
is a locale map for one field and a geo point for another - so [the
definition](../reference/documents-api.md#how-a-document-is-shaped) is what
decides.

The values are validated the way a document is:

| Refused | Code |
|---------|------|
| A value that is not an object | `index:update:not_a_document` |
| An object given to a field that is not one | `index:update:unexpected_document` |
| A field the object does not declare | `index:update:field_not_found` |
| A value missing an inner `required` field | `index:update:required_field_missing` |

A document is written whole, so indexing one under a key it already holds
replaces every value the field had, and deleting the document takes the values
with it.

Values come back with results through the copy the index keeps of each
document, and an object field cannot be `stored` on its own - an index defined
with `"source": "none"` therefore answers without them.

## Ask several things of one value

A `nested` clause names the object field as its `path` and holds the
conditions that have to meet in a single value:

```json
{
  "query": [
    { "type": "nested", "path": "variants", "clauses": [
      { "field": "variants.color", "match": { "value": "red" } },
      { "field": "variants.price", "match": { "type": "range", "lt": 20 } }
    ] }
  ]
}
```

Fields inside go by their dotted path, and a path resolves only inside a
`nested` clause for it: naming `variants.color` in `query` directly is refused
with `index:query:nested:outside` and under another object's path with
`index:query:nested:not_in_path`. A clause on a field of the index cannot sit
inside one either.

Anything that runs against a single value may sit inside - `field`, `text`,
`and`, `or`, `not` and `boost`. A `nested` within a `nested`, and a `knn`, are
refused with `index:query:nested:unsupported_clause`. Empty `clauses` ask only
that the document holds a value at all.

Conditions on values belong in `query`. A filter is a condition on a single
field of the index and never reaches inside an object, so a refinement over
variants is a `nested` clause in `query` too - and it narrows every facet
count along with the hits, rather than being left out of the counts of its own
field the way [a filter is](../reference/search-api.md#facets).

Results are documents, never values. A hit carries the whole field, the values
that matched and the ones that did not alike, so a UI showing which variant
answered picks it out of what came back.

## Search text inside the values

A `text` clause inside a `nested` clause covers the fields of the path when it
names none, and its words have to be found in a single value the way the other
clauses hold inside one. A `text` clause outside covers the fields of the
index and nothing inside an object, so text that should reach both is an `or`
of the two:

```json
"query": [
  { "type": "or", "clauses": [
    { "type": "text", "text": "waterproof leather" },
    { "type": "nested", "path": "variants", "score": "total", "clauses": [
      { "type": "text", "text": "waterproof leather" }
    ] }
  ] }
]
```

`score` says which of the values that matched decides what the document
scores: `max` (default), `min`, `avg` or `total`. It means something only when
something inside the clause ranks, which a filtering condition does not.

Highlighting reads the text back out of the document a fragment is shown for,
and a value is not that document, so an inner field cannot be highlighted -
declaring it is refused. Highlight the document's own fields instead.

## Order and count by a value

A sort and a facet name the dotted path directly, because what they say is
about the document rather than about one value:

```json
{
  "query": [
    { "type": "nested", "path": "variants", "clauses": [
      { "field": "variants.color", "match": { "value": "red" } }
    ] }
  ],
  "sort": [ { "field": "variants.price", "order": "asc" } ],
  "facets": [ { "field": "variants.color" } ]
}
```

Only the values the search matched take part in either, which is what the
`nested` clauses every result had to satisfy say - clauses inside an `or`, a
`not` or a `boost` take no part, and a search that asked nothing of the values
reads all of them. A sort stands the document at the end of its values the
order asks for, so ascending orders products by their cheapest red variant and
descending by their most expensive. A facet counts documents the way every
other facet does, so a product holding three red variants is one red product,
and counting colours under a search for variants below 20 answers the colours
of those variants rather than every colour of the products they belong to.

A `distance` sort inside an object is refused with
`index:query:nested:sort_unsupported`.

## Change some of the values

`actions/update` changes a field at a time and replaces the field whole, so an
update naming an object field leaves it holding the values that update gives
and not the ones already there:

```http
POST /v1alpha1/indexes/products/documents/actions/update
Content-Type: application/json

{"documents": [{"id": "1", "variants": [{"color": "red", "price": 12.0}, {"color": "black", "price": 25.0}]}]}
```

There is no path into a single value: `variants.color` is not a field of the
index, and an update naming it is refused with `index:update:field_not_found`.
Changing one variant means sending every variant the document should end up
with.

Read them back first when the caller does not have them. The index keeps a
copy of each document unless the definition turned it off, and a search brings
the field back:

```json
{
  "filters": [ { "field": "id", "match": { "value": "1" } } ],
  "fields": ["variants"]
}
```

`fields` names fields of the index, so it takes `variants` and not
`variants.price` - a dotted path there is refused as a field the index does
not have. Finding the document by its key this way needs the key defined for
`filter`.

## What the values cost

Each value is a Lucene document in the same block as the document holding it,
so 100 000 products with five variants each are 600 000 documents. The block
is written and deleted as one: changing a single variant rewrites the whole
document, and removing the document removes its values. A search asking
something of the values joins them back to their documents, which a condition
on the document's own fields never has to do.

Whether variants belong inside the document, in documents of their own, or
rolled up onto the product is worth measuring rather than arguing about.
`GroupingBenchmark` lays a catalogue out four ways and puts the same questions
to each - see [Comparing ways of holding
variants](benchmark-the-engine.md#comparing-ways-of-holding-variants).

## Related

- [`object` fields](../reference/field-types.md#object) - every property, and
  what a field inside one may declare.
- [The `nested` clause](../reference/search-api.md#nested) - the request
  shape, along with [ordering
  by](../reference/search-api.md#ordering-by-a-value-inside-an-object) and
  [counting](../reference/search-api.md#counting-a-value-inside-an-object) a
  value.
- [Documents API](../reference/documents-api.md#how-a-document-is-shaped) -
  the shape a document is written in, and what `actions/update` changes.
- [Define an index](define-an-index.md) - the rest of a definition.
- [Roll out a definition change](roll-out-a-definition-change.md) - reaching
  values that are already indexed.
