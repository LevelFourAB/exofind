# Search API

```
POST /v1alpha1/indexes/{name}/search
```

A search runs on whichever node receives it, against the state that node
has - no request has to reach the indexer.

## Request

```json
{
  "query": [
    { "type": "text", "text": "silent spr", "fields": { "name": 3, "description": null } },
    { "field": "published", "match": { "value": true } }
  ],
  "filters": [
    { "field": "category", "match": { "type": "in", "values": ["fiction"] } }
  ],
  "facets": [ { "field": "category" } ],
  "sort": [ { "type": "score" }, { "field": "name", "order": "asc" } ],
  "locale": "sv",
  "fields": ["name", "price"],
  "highlight": { "fields": { "name": {} } },
  "limit": 20,
  "offset": 0,
  "total": "estimate"
}
```

Everything is optional - an empty request matches every document and brings
back the first few of them.

| Property | Meaning |
|----------|---------|
| `query` | Clauses a document has to satisfy, all of them - the list is an implicit AND. Left out to match every document. A clause here narrows every facet count, so a search box and the scope it runs in belong here. |
| `filters` | The refinements a user has ticked, each a condition on a single field. They narrow the hits the way `query` clauses do, but a facet leaves the filters on its own field out of its counts - see [Facets](#facets). |
| `facets` | What to count the matches per value of, see [Facets](#facets). Left out for no counting. |
| `sort` | The order results come back in. Left out for the best matches first. |
| `signals` | Values of the documents themselves to take into their relevance, see [Signals](#signals). Left out to rank by the ones the index declares. |
| `locale` | Locale the search reads locale specific fields in (BCP-47). Matched as closely as each field's declared locales tell apart, so `sv-SE` reads a field holding `sv`; a field holding no variant the tag names reads its default. Left out to leave every field to its own default locale. |
| `fields` | Fields to bring back with each result. A field inside an [`object`](field-types.md#object) is named by its dotted path and comes back inside the object, which then holds only the fields that were asked for. Naming a field the index has no way to return is refused, see [Document source](field-types.md#document-source). Left out for every stored field. The primary key is always included. |
| `highlight` | Fields to return highlighted fragments for, see [Highlighting](#highlighting). Left out for none. |
| `limit` | How many results to return. Defaults to 10. `0` answers only how many there are. |
| `offset` | How many results to skip. At most one of `offset`, `after` and `before` is given. |
| `after` | Cursor to continue after, from the `next` of a previous response. |
| `before` | Cursor to read the window preceding, from the `previous` of a previous response. |
| `pages` | Ask for numbered pages - being present is what asks. Optionally `{ "max": n }` bounds how many page entries come back (default 9). Implies an exact total. |
| `total` | How far the total is counted: `estimate` (default) stops once it is known more matched than came back, leaving a lower bound; `exact` counts every match. |

## Clauses

Clauses are a tagged union where `type` selects the kind. A clause with no
`type` is a field clause - the only kind carrying `field` together with
`match` - which keeps the common case short.

### `field`

Match documents by what a single field holds.

```json
{ "field": "category", "match": { "value": "fiction" } }
```

The field has to be defined for the way the clause uses it; using a field in
a way the definition never asked for is refused with
`index:query:usage_not_enabled` rather than answered with no results.

### `text`

Match text that someone typed, against several fields at once.

```json
{ "type": "text", "text": "silent spr", "fields": { "name": 3, "description": null } }
```

- `fields` - the fields to look in and how much a hit in each counts. A
  field mapped to `null` counts as much as its definition says. Left out to
  look in every field that can be matched on.
- `match` - how the words combine: `all` (default), `any`, `phrase` or
  `user`. `phrase` needs them in the order typed and next to each other -
  what quoting means in a search box. `user` reads the text the way a person
  writes it, see [Reading what was typed](#reading-what-was-typed).
- `prefix` - how the word still being typed is treated: `last_token`
  (default) matches it as a prefix, `off` requires it whole.
- `typos` - whether words may contain typing mistakes: `auto` (default)
  follows what each field's `typoTolerance` allows, `off` turns it off for
  this search.
- `slop` - how many other words may sit between the words of a phrase,
  counted across the phrase as a whole. `0` by default, the words next to
  each other.
- `relax` - what may be let go of rather than find nothing: `unmatched`
  (default), `words` or `off`, see [Finding something rather than
  nothing](#finding-something-rather-than-nothing).
- `combine` - what a match is complete within: `term` (default) or `field`.

A phrase holds within a single field, so `combine` makes no difference to
it, and its words are taken as typed whatever the fields' `typoTolerance`
allows. A word an analysis chain drops - a stopword - leaves a hole the
phrase keeps: `spring of 1962` finds a value written that way, `spring 1962`
does not. Fields defined only for `autocomplete` cannot answer for the order
of their words; a search naming no fields skips them, and naming one is
refused with `index:query:usage_not_enabled`.

`slop` loosens how far apart the words of a phrase may sit, never the order
they come in. `silent spring` with `"slop": 1` finds a value reading `silent
green spring`; written as `spring silent` it finds neither, at any slop.
Documents whose words sit closer together rank above ones where they sit
further apart. Only `phrase` and `user` build a phrase for it to loosen, so a
`slop` above zero on `all` or `any` is refused with
`search:clause:slop_not_applicable`.

Under `term`, each word is looked for in every field and counts in whichever
holds it best, so the words may sit in different fields - `red nike shoes`
finds a document whose color, brand and name each hold one word. A document
is ranked by its words added up. Under `field`, a single field has to satisfy
`match` on its own and a document is ranked by the field it matched best -
the right ask when the fields are parallel renderings of the same content,
such as a title and its body.

Fields whose analysis cuts the text into different numbers of words - one
decompounds or drops a stopword the others keep - cannot be lined up word by
word; each group of fields that agree is combined on its own, and a document
is ranked by the group that matched it best.

#### Reading what was typed

`"match": "user"` reads the text as somebody writing in a search box meant
it, and combines what is left as `all`. Two pieces of punctuation carry
meaning, both of them ones people already use without being told to:

```json
{ "type": "text", "text": "running shoes \"trail ready\" -leather", "match": "user" }
```

| Written | Read as |
|---------|---------|
| `"apple watch"` | Those words as a phrase, in that order |
| `-leather` | Documents holding the word are left out |
| `-"apple watch"` | Documents holding the phrase are left out |

Everything else is text. A minus inside a word (`e-mail`), a quote inside one
(`it"s`), a minus with nothing after it - none of them mean anything here, so
they stay part of what is searched for. A quote nobody closed runs to the end
of the text, because somebody halfway through typing a phrase has not made a
mistake yet.

Nothing that can be typed is an error. The parts a text is read as are the
same clauses that can be written by hand, so every other option of the clause
reaches them: `fields` and `combine` say where and how each part is looked
for, `slop` reaches the quoted parts, and `prefix` reaches the word the text
ended in the middle of - the last loose word, or the last word of a quote
nobody closed. Two things are held back from the exclusions, which throw
documents away rather than bring them in: an excluded word is never read as
half typed, and never widened by `typoTolerance`.

Where the same quotes written as a `phrase` clause would be refused, `user`
gives way instead: a field defined only for `autocomplete` answers a quoted
part as the loose words inside it, because the quotes were typed by somebody
who cannot be expected to know which fields the search covers. A text of
nothing but exclusions runs against the whole index, and a text with nothing
to search for in it matches nothing.

#### Finding something rather than nothing

`all` needs every word, so one word a document happens not to hold empties the
page - and the longer the text, the more often that happens. `relax` says what
a search may let go of instead:

| `relax` | Lets go of |
|---------|------------|
| `unmatched` (default) | Words nothing in the index holds. Keeping such a word could only ever have found nothing, so dropping it loses no result |
| `words` | Those, and then the word the most documents hold - the one that says the least about what was wanted - one at a time until something is found |
| `off` | Nothing. An empty page is the answer |

Only a search that came back empty lets go of anything, so a search that found
something is answered exactly as it was asked. Only the loose words of the text
go: a quoted phrase and a `-word` exclusion were asked for deliberately, and a
`phrase` clause is that same ask written by hand. Relaxing stops while a word is
still left, so a text never widens to everything. Words that went are still
counted in the ranking, so a document holding one of them comes first among the
results that are left.

A relaxed search answers what it let go of beside the results:

```json
{
  "hits": [ ... ],
  "relaxed": {
    "dropped": [
      { "word": "waterproof", "reason": "unmatched" },
      { "word": "mens", "reason": "common" }
    ],
    "text": "running shoes"
  }
}
```

The key is only there when something was let go, and it always means the
results answer less than what was asked for. `text` is what the search ran with
in the end, for showing what the page actually answers. The total and the facet
counts are counted from that same search, so they describe the results being
shown.

A search whose filters match nothing is not relaxed around - when nothing
matches even with the text gone, the words are not why the page is empty.

### `knn`

Match the `k` documents whose vector in a field is nearest to the given one,
scored by how near they are.

```json
{ "type": "knn", "field": "embedding", "vector": [0.1, 0.2], "k": 10,
  "filter": [ { "field": "published", "match": { "value": true } } ] }
```

The vector has to have the dimensions the field declares. `filter` narrows
which documents may be neighbours before the nearest are picked, all of its
clauses have to be satisfied.

### `nested`

Match documents where a single value of a `nested` [`object`
field](field-types.md#object) satisfies all of the clauses - a condition on
several fields of the same value, rather than on the document as a whole.
The fields of a flattened object are ordinary fields of the index named by
their dotted path and need no clause here; a `nested` clause on one is
refused with `index:query:nested:flattened`.

```json
{ "type": "nested", "path": "variants", "clauses": [
  { "field": "variants.color", "match": { "value": "red" } },
  { "field": "variants.price", "match": { "type": "range", "lt": 20 } }
] }
```

- `path` - the name of the object field.
- `clauses` - what has to hold inside a single value, naming fields by their
  dotted path. Empty asks only that a value exists.
- `score` - which of the values that matched decides what the document
  scores: `max` (default), `min`, `avg` or `total`. Only means something
  when something inside the clause ranks.

A clause naming a field inside an object can only sit here, and only under
its own path; a `nested` clause on a field that is not an object is refused.
Anything that runs against a single value may sit inside - `field`, `text`,
`and`, `or`, `not` and `boost`. A clause that only means something for the
documents of the index, another `nested` or a `knn`, is refused with
`index:query:nested:unsupported_clause`.

A `text` clause inside covers the fields of the path when it names none, and
its words have to be found in a single value, the same way the other clauses
hold inside one. The clause then ranks the document by what its values
scored, which is what `score` reads:

```json
{ "type": "nested", "path": "variants", "score": "total", "clauses": [
  { "type": "text", "text": "waterproof leather" }
] }
```

Ordering by and counting a value inside an object are written as an ordinary
[sort](#sorts) and [facet](#facets) on the dotted path, and both read the
values the `nested` clauses of the search matched - see there.

### `and`, `or`, `not`

Combine other clauses: all of them, at least one of them, none of them. Each
carries `clauses`, a list of clauses.

### `boost`

Rank documents that satisfy all of the clauses higher, without leaving out
the ones that do not.

```json
{ "type": "boost", "weight": 2, "clauses": [ { "field": "featured", "match": { "value": true } } ] }
```

`weight` is how much satisfying the clauses counts relative to the rest of
the query - above one lifts, below one holds back.

## Matchers

What a field clause looks for. A tagged union where `type` selects the kind;
no `type` means `equals`, keeping the common case down to the value being
looked for.

| Matcher | Shape | Meaning |
|---------|-------|---------|
| `equals` | `{ "value": v }` | Values equal to the given one. |
| `in` | `{ "type": "in", "values": [v, ...] }` | Values equal to any of the given ones. An empty list matches nothing, the way a filter nobody has picked a value in does. |
| `any` | `{ "type": "any" }` | Documents that have any value for the field at all. |
| `prefix` | `{ "type": "prefix", "value": "EX-" }` | Values starting with the prefix, compared against the whole value rather than the words inside it. |
| `under` | `{ "type": "under", "path": "Men/Shoes" }` | Values sitting at or below a path of a tree, which is what choosing a category asks for. Only a field defined with [`hierarchy`](field-types.md#string) can answer it. Levels are matched whole, so `Men/Sho` finds nothing where a `prefix` would have found the shoes. |
| `range` | `{ "type": "range", "gte": 10, "lt": 20 }` | Values between two bounds. Each side is one of an inclusive (`gte`/`lte`) and an exclusive (`gt`/`lt`) bound; either side may be left open, at least one has to be given. |
| `text` | `{ "type": "text", "text": "..." }` | Text matched within one field, analyzed the same way the field was. Carries the same `match`, `prefix`, `typos`, `slop` and `relax` options as the `text` clause. |
| `distance` | `{ "type": "distance", "lat": 59.3, "lon": 18.1, "radius": 5000 }` | Geo point values within `radius` meters of the origin. |

A matcher a field's type has no meaning for is refused - a boolean cannot
answer `prefix`. Matcher and clause identifiers are what callers write, so
they are never renamed.

## Sorts

A tagged union like clauses: no `type` means a field sort. Leaving `order`
out takes the default for the kind - score descending, field ascending.

| Sort | Shape | Meaning |
|------|-------|---------|
| `field` | `{ "field": "name", "order": "asc" }` | By the value of a field, which has to be defined for sorting. |
| `score` | `{ "type": "score" }` | By how well documents match. |
| `distance` | `{ "type": "distance", "field": "location", "lat": 59.3, "lon": 18.1 }` | By how far a geo point field's value is from the origin, nearest first. There is no farthest first, so it carries no order. |

The tie breakers of the index's `ranking` are appended after whatever the
search asks for, so they decide the order within ties without disturbing it.

### Ordering by a value inside an object

A field sort naming a field inside a `nested`
[`object`](field-types.md#object) by its dotted path orders documents by one
of the values they hold there - inside a flattened object the dotted path is
an ordinary field, and a sort on it is an ordinary field sort:

```json
"query": [
  { "type": "nested", "path": "variants", "clauses": [
    { "field": "variants.color", "match": { "value": "red" } }
  ] }
],
"sort": [ { "field": "variants.price", "order": "asc" } ]
```

Only the values the search matched take part, which is what the `nested`
clauses every result had to satisfy say - clauses inside an `or`, a `not` or
a `boost` take no part, and a search that asked nothing of the values orders
by all of them. A document stands for the end of them the order asks for, so
ascending by price orders products by their cheapest red variant and
descending by their most expensive one. Where a document holding no matching
value ends up is the `missing` of the field's `sort`, the same as for a
document holding no value at all.

A `distance` sort inside an object is refused with
`index:query:nested:sort_unsupported`.

## Signals

The index's `ranking` signals - values of the documents themselves, multiplied
into their relevance - are what a search ranks by unless it brings its own:

```json
"signals": [
  { "field": "purchases", "saturation": { "pivot": 50 } },
  { "field": "published", "decay": { "halfLife": 604800 }, "weight": 0.5 }
]
```

Given, they replace the index's whole - trying out a ranking runs the one being
tried, not it added to the one in place - and an empty list ranks by how well
documents match alone. The shapes and what each field type can answer for are
the same as in a [definition](field-types.md#signals).

Signals are only read where relevance is the ordering, so a search giving a
`sort` of its own is unaffected. A search whose clauses only narrow is ranked
by its signals alone, which is what orders browsing a category by what sells.

A signal naming a field the index does not have is refused with
`index:query:field_not_found`, one naming a field that is not defined for
sorting with `index:query:usage_not_enabled`, and a shape the field's type has
no meaning for with `index:invalid-query-type`.

## Facets

Asking for `facets` answers with how many matches hold each value of a
field, which is what a list of filters to pick from is built out of. The
field has to be defined for faceting (`facet` in its definition); one that
is not is refused with `index:query:usage_not_enabled`.

```json
"filters": [
  { "field": "category", "match": { "type": "in", "values": ["fiction"] } }
],
"facets": [
  { "field": "category", "limit": 20 },
  { "name": "years", "field": "published_year", "order": "value" }
]
```

| Option | Meaning |
|--------|---------|
| `name` | What the counts are keyed by in the response. Defaults to the field, and only has to be given when one search counts the same field twice. Two facets resolving to the same name are refused with `search:facet:duplicate_name`. |
| `field` | The field to count. |
| `limit` | How many values to bring back at most, between 1 and 1000. Defaults to 10. |
| `order` | `count` (default) answers the most common values first; `value` answers ascending by the value itself. |
| `ranges` | Buckets to count the matches into instead of per value - see [Range buckets](#range-buckets). Being present is what asks for it, and neither `limit` nor `order` combines with it (`search:facet:ranges_conflicting`). |
| `path` | The level of the tree to count the children of, for a field whose values are paths - see [Counting down a tree](#counting-down-a-tree). Left out to count from the top. |
| `depth` | How many levels below `path` to count, between 1 and 10. Defaults to 1. |

The response carries the counts under `facets`, keyed by name:

```json
"facets": {
  "category": {
    "values": [
      { "value": "fiction", "count": 87 },
      { "value": "poetry", "count": 21 }
    ],
    "totalValues": 14
  }
}
```

`totalValues` is how many distinct values the matches held in all - more
than the number of values whenever the limit was reached. A value comes back
in the shape the field returns it in results: a string, boolean or number,
and an ISO 8601 instant in UTC for a timestamp field. A value can always be
sent straight back as a filter on the same field.

### Range buckets

A facet given `ranges` counts the matches into buckets instead of per
value - what a price facet or a date facet shows. It works on number and
timestamp fields; a type whose values have no order to bucket, such as a
string, is refused with `index:invalid-query-type`.

```json
"facets": [
  { "field": "price", "ranges": [
    { "to": 100 },
    { "from": 100, "to": 200 },
    { "from": 200 }
  ] }
]
```

A bucket holds the values from `from` up to but not including `to`, so an
adjacent pair sharing a bound counts no value twice. Either bound may be
left out for an open end, but not both (`search:facet:range_empty`), and
`to` has to be above `from` (`index:query:facet_range_empty`). Bounds are
values of the field: numbers for a number field, ISO 8601 timestamps for a
timestamp field. At most 1000 buckets per facet
(`search:facet:ranges_too_many`).

The response answers `buckets` in place of `values`, one per range and in
the order they were given, echoing the bounds:

```json
"facets": {
  "price": {
    "buckets": [
      { "to": 100, "count": 41 },
      { "from": 100, "to": 200, "count": 17 },
      { "from": 200, "count": 3 }
    ]
  }
}
```

A bucket can be sent back as a `range` filter on the same field with
`gte`/`lt`. Ticking one bucket keeps the others countable the same way
value facets do - see below - but offering several buckets at once needs a
filter that ORs ranges together, which the filter shape does not have yet.

What a facet counts is decided by where the conditions of the search sit:

- Everything in `query` narrows every count, the way it narrows the hits.
- A filter narrows the counts of every facet except the ones on its own
  field. Ticking `fiction` still shows what the other categories would
  hold - the counts a filtering UI needs to keep its other options alive -
  while the hits and every other facet are narrowed by it. Every filter on
  the field is left out together, and it is the field that decides, not the
  facet's name.
- A locale specific field is counted in the variant the search reads it in -
  the locale asked for when the field holds it, its default otherwise.

Counting collects every match, so a search that asks for facets always
answers an exact total. A search with `"limit": 0` and facets is the
cheapest way to refresh the counts of a filtering UI without fetching hits.

### Counting down a tree

A field defined with [`hierarchy`](field-types.md#string) holds paths rather
than values standing on their own, and a facet on it counts one level of the
tree at a time - the counts a category navigation is built out of. `path` says
which level to count the children of and `depth` how far below it to go; a
facet giving neither answers the top level.

```json
"facets": [ { "field": "category", "path": "Men", "depth": 2 } ]
```

The counts come back nested, each level carrying both the level itself and the
whole path down to it:

```json
"facets": {
  "category": {
    "values": [
      { "value": "Shoes", "path": "Men/Shoes", "count": 42, "totalValues": 2,
        "values": [
          { "value": "Running", "path": "Men/Shoes/Running", "count": 28 },
          { "value": "Casual", "path": "Men/Shoes/Casual", "count": 14 }
        ] },
      { "value": "Outerwear", "path": "Men/Outerwear", "count": 9 }
    ],
    "totalValues": 2
  }
}
```

`value` is the level, which is what a navigation shows; `path` is what a filter
on the field takes. `limit`, `order` and `totalValues` apply per level, so a
limit of 20 is 20 children of each level rather than 20 nodes in all. The
deepest level counted carries no `values` of its own.

A document is counted at every level its path passes through, and once at each:
a product filed under `Men/Shoes/Running` is one of the products under `Men`.
One filed in two trees at once counts in both.

Drilling in is an `under` filter on the same field:

```json
"filters": [
  { "field": "category", "match": { "type": "under", "path": "Men/Shoes" } }
],
"facets": [ { "field": "category", "path": "Men" } ]
```

The sideways rule is what makes this work: the filter sits on the facet's own
field, so it is left out of the counts and `Outerwear` stays visible and
countable beside the chosen `Shoes`. Every other filter and the whole query
narrow the counts the way they narrow the hits.

`path` and `depth` are refused with `index:query:usage_not_enabled` on a field
that holds no paths, and neither combines with `ranges`
(`search:facet:ranges_on_a_tree`). Counting a path nobody is filed under
answers no values rather than an error.

### Counting a value inside an object

A facet naming a field inside a `nested`
[`object`](field-types.md#object) by its dotted path counts how many
documents hold each value there - inside a flattened object the dotted path
is an ordinary field, and a facet on it is an ordinary facet:

```json
"facets": [ { "field": "variants.color" } ]
```

The counts are of documents, the way every other facet's are, so a product
holding three red variants is one red product. Only the values the search
matched are counted, which is what the `nested` clauses every result had to
satisfy say - the same values a [sort](#ordering-by-a-value-inside-an-object)
on the path orders by - so counting colours under a search for variants
below 20 answers the colours of those variants rather than every colour of
the products they belong to.

Filters name fields of the index and never reach inside an object, so these
facets have no sideways scope of their own: a `nested` clause narrowing them
sits in `query` and narrows the counts along with everything else. Range
buckets work the same way and count a document once however many of its
values fall in the bucket.

## Highlighting

Asking for `highlight` answers each hit with fragments of the fields named,
showing what the text of the search matched:

```json
"highlight": {
  "fields": {
    "name": {},
    "description": { "fragments": 1, "length": 80, "pre": "<b>", "post": "</b>" }
  }
}
```

`fields` maps each field to its options, `{}` asking for the defaults. A
field has to be declared highlightable on the usage its text search targets -
`matching` when the field has it, `autocomplete` only when it does not - and
one that is not is refused with `index:query:usage_not_enabled`, like any
other usage the definition never asked for.

| Option | Meaning |
|--------|---------|
| `fragments` | How many fragments to return at most. Defaults to 3. |
| `length` | How long a fragment aims to be in characters, between 1 and 10000. Fragments break on sentences, stretched or shrunk toward this. Text shorter than it comes back as a single fragment holding all of it. Defaults to 150. |
| `pre` | What to put in front of each match. May be empty. Defaults to `<em>`. |
| `post` | What to put after each match. May be empty. Defaults to `</em>`. |

What gets highlighted follows what ranked the hit:

- Fragments are built from the scoring part of the search. A clause that only
  narrows - a field clause used as a filter - never highlights, so a document
  is not highlighted for the category it happens to be in. The conditions of
  a `boost` do count: they are part of why the hit ranks where it does.
- A word matched while half typed is highlighted whole: searching `spr`
  highlights `Spring`, which is the point of showing it. The same goes for
  words matched despite typing mistakes.
- A locale specific field is highlighted in the variant the search read it
  in - the locale asked for when the field holds it, its default otherwise.

The fragments are the stored text as it was given, with `pre` and `post`
spliced in - nothing is HTML escaped. Text beyond the first 10000 characters
of a value is not searched for matches.

```json
{
  "hits": [
    {
      "id": "9781234567890",
      "score": 8.42,
      "document": { "name": "Silent Spring", "price": 19.5 },
      "highlights": { "name": ["<em>Silent</em> Spring"] }
    }
  ],
  "total": { "count": 128, "exact": false },
  "page": { "limit": 20, "offset": 0, "next": "AW8..." },
  "tookMs": 7.412
}
```

- `hits` - the results, in the order asked for. `id` is the primary key,
  left out for an index that has no primary key. `score` is left out when
  the search computed no scores - a search made only of filters - rather
  than defaulted to something that looks like a value. `document` holds the
  fields asked for, as they were given: a field with several values is an
  array, a locale specific field is an object keyed by locale tag.
  `highlights` is present whenever the search asked for highlighting, field
  name to a list of fragments - a field the hit holds no match in is left
  out of it, so a hit nothing matched carries `{}`.
- `total` - how many documents matched. `exact` says whether `count` is the
  whole number or at least that many.
- `facets` - the counts per value asked for, keyed by the name of each
  facet, see [Facets](#facets). Present whenever facets were asked for, and
  left out entirely when they were not.
- `page` - where this window sits. `offset` is left out when the window was
  reached through a cursor - following a hit does not count what it skips.
  `previous` and `next` are cursors for the neighbouring windows, each left
  out when there is nothing on that side.
- `relaxed` - what the search let go of to find anything, see [Finding
  something rather than nothing](#finding-something-rather-than-nothing).
  Left out entirely when it found what was asked for.
- `tookMs` - how long answering took, measured around the whole call, in
  milliseconds and fractions of one.

### Numbered pages

Asking for `"pages": {}` adds `page.pages` to the response:

```json
"pages": {
  "count": 7,
  "previous": { "number": 1, "cursor": "..." },
  "next": { "number": 3, "cursor": "..." },
  "start": [ { "number": 1, "cursor": "..." }, { "number": 2, "cursor": "...", "current": true } ],
  "end": [ { "number": 7, "cursor": "..." } ]
}
```

The entries are split into `start`, `middle` and `end` runs so a pager
renders `1 2 3 … 7` with the ellipses exactly where a run boundary falls.
`end` is left out when the last page is deeper than paging goes, so a pager
never offers a jump that would be refused. `current` marks the page the
response is showing. Page numbers count from one.

## Paging rules

- An `offset` may reach at most `SEARCH_MAX_PAGE_DEPTH` deep; a request past
  it is refused with `search:page:too_deep`.
- `next`/`previous` cursors carry the position of the hit their window ended
  at rather than a count, so following them costs the same at any depth and
  is not capped. A cursor is tied to the sort it was handed out under and
  refused under another (`search:cursor:sort_mismatch`) - changing the query
  while keeping the position is fine.
- A numbered page's cursor is a count: it keeps working whatever the sort,
  and stays under the cap.
- Numbered pages need a numbered position, so `pages` combines with `offset`
  or a page's own cursor but not with `after`/`before`.
