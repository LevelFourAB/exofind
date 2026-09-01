# Record shop

50 000 records from the Discogs catalogue carrying 342 603 pressings between
them, so a filter can ask that one pressing answer everything at once.

```shell
./load.sh                  # define the index, index the documents, commit
mise run site              # serve the page against a node on localhost:8080
```

A record is a product and a pressing is a variant of it. `variants` is an
`object` field in `nested` mode, so every pressing is a document of its own
holding a format, a country, a year, a label, a catalogue number, a price, and
whether it is in stock.

## What it shows

**A condition that has to hold within one value.** Ask for a UK vinyl under
€25. One `nested` clause holding all three conditions finds records with a
pressing that is all three. Three conditions written as three clauses find
records with a UK pressing, a vinyl pressing, and a cheap pressing, which need
not be the same one. The **Match** control sends both, so the counts and the
rows change while the ticks stay where they are. Under `any pressing` a row
whose pressings answered only between them says so, because `matched` returns
what a single value matched and there is nothing to list.

**Which values answered.** `matched` returns the pressings the `nested`
clauses matched instead of the whole list, so a search narrowed to UK vinyl
lists UK vinyl under each record. `totalValues` counts the rest of them, and
`pressings` on the record counts every pressing Discogs knows of, including
the ones this catalogue left out.

**A value as a hit of its own.** The **Rows** control switches `hits` between
the record and the pressing. As pressings, the total counts pressings, a row
is one pressing with its own price, and ordering by price orders pressings
rather than records.

**Facets and ordering on a field inside a value.** Format, country, when it
was pressed and what it costs are all fields of a pressing. A facet on one of
them counts records: a record pressed on vinyl four times counts once under
Vinyl. Ordering a record by `variants.price` takes its lowest matching price
going up and its highest coming down, so `cheapest` means the cheapest
pressing that answered.

**A facet against a filter it cannot separate from.** A filter exclusion drops
a whole filter entry, and the conditions on a pressing are one entry. A facet
on a pressing therefore leaves out every pressing filter rather than only its
own, so ticking Vinyl does not shrink the Format list to Vinyl. The facets on
the record - genre and style - exclude their own filter exactly, the way the
other examples do.

What was searched for is in the URL, so a search worth showing someone is a
link:

```
index.html?q=kraftwerk&variants.format=Vinyl&variants.country=UK&price=10-25
```

## The data

`documents.jsonl.gz` holds the documents, one per line, ready to be posted to
a node. `prepare.py` builds it from the Discogs monthly data dump and needs
nothing but Python:

```shell
./prepare.py              # 50 000 records, about 11 MB
./prepare.py 5000         # fewer, for a quick look
./prepare.py 300000       # about 67 MB, for a node with more in it
./prepare.py 1000000 3    # about 186 MB, keeping records of three pressings
```

The second number is the fewest pressings a record needs to be kept. It is
what a larger catalogue is traded against, because most masters have very few:

| Fewest pressings | Records available |
| --- | --- |
| 4 (the default) | 756 241 |
| 3 | 1 239 600 |
| 2 | 2 578 989 |

Asking for more records than carry the current number writes every one there
is, says so, and says what to run instead.

The dump is two files, 11 GB compressed between them, and the pass over the
releases is about 19 million records. Expect half an hour and 20 GB of scratch
space on the first run. Every stage writes to `.cache/` and is skipped when it
is already there, so a second run at a different size costs seconds. Delete
`.cache/` to start over.

Discogs calls a record a *master* and a pressing a *release*, and each release
names the master it belongs to. `prepare.py` reads both files, sorts them by
that id, and walks the two together, so nothing larger than one record is ever
held in memory.

Two limits shape the catalogue:

- A record needs four pressings to be kept. Around half of all masters carry
  exactly two, which is a thin thing to compare and a thinner one to filter.
- A record keeps at most 12 pressings, taken one format at a time so a record
  that exists on vinyl, CD and cassette shows all three. Records that sold have
  been pressed hundreds of times, and every value is a document in the block
  holding the record. See [How sub-documents are
  stored](../../docs/explanation/document-blocks.md).

The pressings of a record are held cheapest first. `matched` answers with a few
of the values that matched, in the order the record holds them, so holding them
by price puts the cheapest matching pressings on the card.

## What a larger catalogue costs

Measured on one node holding the index on local disk, searching with the page's
own request - eight facets, five of them on a field inside the pressing:

| Records | Pressings | `documents.jsonl.gz` | Index on disk | A search |
| --- | --- | --- | --- | --- |
| 50 000 | 342 603 | 11 MB | 58 MB | 55 ms |
| 300 000 | 2 053 993 | 67 MB | 344 MB | 320 ms |
| 1 000 000 | 5 345 653 | 186 MB | 932 MB | 860 ms |

Almost all of that is the facets. At a million records the same search takes
10 ms without them, 20 ms with the two facets on the record, and 860 ms with
the five on fields inside the pressing. A facet on an inner field counts over
every pressing in the index, and this catalogue holds five of them.

Two things follow for a node meant to answer quickly:

- Counting fewer things is the lever, not searching fewer. Dropping a facet on
  an inner field is worth more than dropping a hundred thousand records.
- The facets on a pressing name `excludeFilters`, so they count the whole
  catalogue however much has been ticked. Taking that off makes a narrowed
  search much cheaper - a ticked Vinyl brings the same search to 60 ms - at the
  cost of the Format list shrinking to Vinyl once Vinyl is ticked.

Set `EXOFIND_SEARCH_TIMEOUT` above what the search actually takes at the size
being served. See [Running a public demo
node](../../docs/how-to/run-a-demo-node.md) for the rest of the caps.

**The prices and the stock are invented.** Discogs publishes no prices. Both
are worked out from the release id with a fixed hash, so a rebuild produces
the same catalogue. See [ATTRIBUTION.md](ATTRIBUTION.md) for how, and for
where the rest of the data comes from.

There are no images. Discogs releases none under CC0, so the page prints each
sleeve instead: the colour from the artist, and the object printed on it from
the format. A record is drawn as what most of its pressings are; a row that is
a pressing is drawn as that pressing. A wall of records, CDs and cassettes is
told apart by shape.

Only the colour under the object answers to the light and dark themes. The
object keeps its ink in both, because every light part of it is painted over
that ink rather than over the colour.
