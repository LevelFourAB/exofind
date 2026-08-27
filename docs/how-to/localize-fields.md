# Localize fields

A field whose values differ per language is marked with `locales`, so
analysis and collation follow the locale each value carries.

## Declare the locales a field holds

```json
"name": {
  "type": "string",
  "locales": { "defaultLocale": "en", "locales": ["sv", "de"] },
  "matching": {}
}
```

- `defaultLocale` is assumed for values that carry no locale.
- `locales` lists the other locales the field holds values in.

Listing the locales is what tells the engine which variants of the field
exist - a value carrying a locale that was never listed is refused with
`index:update:locale_not_declared`.

Every named locale has to be one the engine has rules for; the tags are
listed in the [locale reference](../reference/locales.md), and a definition
naming any other tag is refused at validation.

## Search in a locale

A search names the locale it wants to read locale specific fields in:

```json
{
  "query": [ { "type": "text", "text": "äpple" } ],
  "locale": "sv"
}
```

The tag is matched as closely as the field's declared locales tell apart,
so a browser sending `sv-SE` reads a field that holds `sv`, and `nb-NO`
reads one that holds Norwegian as `no`. A field that holds no variant the
tag names falls back to its default, so a search across several fields
does not fail on the one that never held the locale. The same matching
applies to values on the way in.

In results, a locale specific field comes back as an object holding the one
variant the search read it in, keyed by that variant's tag:

```json
"document": {
  "id": "1",
  "name": { "sv": "röda löparskor" }
}
```

The key is the variant that was read rather than the tag the value was
given under, so a `sv-SE` value comes back under `sv`. A document that
holds no value in the variant leaves the field out of its hit.

A search answers in one locale, so a caller that needs a field in several -
a translation view, say - searches once per locale.

## Find documents that were never translated

By default a search naming a locale only finds the documents translated
into it. A product with an English name but no Swedish one is missing
from a `"locale": "sv"` search entirely - not ranked lower, invisible -
and it sorts as having no value and is counted under nothing.

`localeFallback` closes that. It gives each such document a value from
another locale when the document is indexed:

```json
{
  "localeFallback": { "chain": ["da", "en"] },
  "fields": {
    "name": {
      "type": "string",
      "locales": { "defaultLocale": "en", "locales": ["da", "no"] },
      "matching": {},
      "sort": {},
      "facet": {}
    }
  }
}
```

A document giving only an English `name` now also answers Danish and
Norwegian searches, sorts among the translated documents, and is counted
under its English name. One that gives Danish and English fills its
Norwegian variant from the Danish one, because `da` comes first in the
chain.

- The chain is tried in order, and a field skips the locales it holds no
  values in - so one chain serves fields declaring different locales.
- Leave `chain` out (`"localeFallback": {}`) to send every field to its
  own `defaultLocale`, which is what most indexes want.
- A locale no field of the index holds is refused, rather than silently
  never being taken from.

A filled value is analyzed and collated as the locale it fills, not as
the one it came from. That is what lets a Swedish search's terms meet it,
and what keeps `å` sorting where a Swedish reader expects among filled
and translated documents alike.

A search reads the filled variant like any other, so a hit found through
one comes back with the value it was filled with - an untranslated product
answers a Swedish search under `"sv"` with its English name rather than
with nothing to show. Nothing in a result says whether the variant it
answered with was translated or filled.

### What it costs

The copies are written per missing locale per document, so the cost
follows how much is untranslated - a fully translated field pays nothing.
It is the field's inverted index, doc values, any term vectors and its
stored value that multiply, not the copy of the document, which keeps only
what the document was given.

A field where the gap is cheaper than the copies - a long description
that is only ever read, rather than a name that is searched, sorted and
counted by - opts out:

```json
"description": {
  "type": "string",
  "locales": {
    "defaultLocale": "en",
    "locales": ["da", "no"],
    "fallback": "disabled"
  },
  "matching": {}
}
```

### Changing the chain

Filling happens when a document is indexed, so adding, changing or
removing a chain only decides what is written from there on, the way
every other analysis change does. Documents indexed before keep the
variants they were given until they are indexed again - which is also
what happens when a translation is added, since that is a change to the
document.

## What the locale decides

More than stopwords and stemming:

- Text in a locale whose words Unicode segmentation cannot find - Chinese,
  Japanese, Korean - is split by the locale's own segmentation, so no
  analyzer configuration is needed for an index to hold those languages.
- Normalization covers what Unicode case folding alone gets wrong: the
  Turkish dotless ı, Greek accents, elided articles.
- The languages that glue compounds into one word - Danish, Dutch, German,
  Norwegian, Swedish - index the parts alongside the whole, so `jakke`
  finds `regnjakke`. Japanese and Korean do the same through their
  segmentation. See
  [compound words](../reference/analysis.md#compound-words), including how
  to turn it off per field.
- Sorting with `collation: "locale"` (the default) orders by the rules of
  the locale, so `å` sorts where a reader of that locale expects it rather
  than after `z`.

## Multiple values and locales

A locale specific field holds one value per locale without declaring
`multiple` - a value per translation is not several values. Declaring
`multiple` means several values within the same locale.
