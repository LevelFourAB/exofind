# Customize text analysis

How text is analyzed is decided per usage through `analyzer`, on a string
field's `matching` or `autocomplete`. Leaving it out lets the engine build
analysis from the locale and the usage, which is the right choice for most
fields - reach for the steps below when a field needs something the default
does not do.

## Start from a preset

A preset names a chain the engine expands:

```json
"sku": {
  "type": "string",
  "matching": {
    "analyzer": { "preset": "preserve_terms" }
  }
}
```

- `preserve_terms` tokenizes and normalizes but keeps every word whole - for
  names, codes and SKUs, where stemming `running` to `run` would be wrong.
- `full_text` also drops stopwords, splits compounds and stems - for prose.

A preset is expanded before it is stored, so reading the definition back
shows the chain it became - what a preset means can then never shift under
an index that already exists.

## Spell out a custom chain

When neither preset fits, give the whole pipeline - char filters, a
tokenizer and token filters:

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

The chain describes the indexing side; the engine derives the querying side
from it. Leaving the tokenizer out picks the right one for the locale of
each value. Every component and its options are in the
[analysis reference](../reference/analysis.md).

## Share a chain between fields

Name the chain once under `resources` and refer to it:

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

## Share stopwords

A stopword list that repeats across fields is named the same way, and used
from the stopwords component of a chain:

```json
"resources": {
  "stopwords": { "brands": ["acme"] }
}
```

```json
"filters": [ { "stopwords": { "named": "brands" } } ]
```

A list one field needs can still be inlined with `words`, and an empty
`{ "stopwords": {} }` takes the list of the locale of the value being
analyzed.

## Add synonyms

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

```json
"filters": [ { "synonyms": { "named": "cars" } } ]
```

An `equivalent` rule makes each of its words match the others; a `mapping`
goes one way - a value containing `ny` also answers searches for `new york`,
but not the reverse.

Synonyms are applied when a value is indexed rather than when it is
searched, so a changed set affects documents indexed from there on, like
every other analysis change. Reindex the documents that should pick up the
change.
