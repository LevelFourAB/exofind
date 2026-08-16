# Swedish food search

Every food in the Swedish Food Agency's food database - 2 606 of them, with
what each holds per 100 grams - searched from one page.

```shell
./load.sh                  # define the index, index the documents, commit
cd .. && npm run dev       # serve the page against a node on localhost:8080
```

## What it shows

**Compound words split into their parts.** Swedish glues words together, so
a food is called `gravlaxsås` rather than `gravlax sauce`. Searching for
`sås` finds 21 foods when only whole words match and 124 when the index
splits compounds, which is what the page searches: `name` is analyzed the way
the locale asks for, and for Swedish that means every part of a compound is
indexed as well as the whole.

The index holds the name a second time as `nameWhole`, the same text with
`"decompound": "none"`, so what splitting is worth can be counted against a
field that does not. Nothing but having both to look at needs two fields - an
index would normally have `name` alone.

**Highlighting inside a word.** A hit on `sås` highlights `gravlaxsås` whole,
because what matched is a part of it. The page then finds the text that was
searched for inside the highlighted word and marks that part alone, so the
rule under `gravlax|sås` stops where the index cut. A word matched some other
way, through stemming or a typo, has no such part and is marked whole.

**Facets counted sideways of filters.** The group counts stay alive after a
group has been ticked, so the list of groups is still worth reading - a
filter narrows the hits and every other facet, but not the facet on its own
field. Energy and protein are counted into range buckets instead of per
value.

**Locale aware analysis.** The search says `"locale": "sv"`, so stopwords,
stemming and compound splitting are Swedish without the page configuring
anything.

What was searched for is in the URL, so a search worth showing someone is a
link:

```
index.html?q=sås&group=Rätter&energy=100-250
```

## The data

`documents.jsonl.gz` holds the documents, one per line, ready to be posted
to a node. `prepare.py` rebuilds it from the published database and needs
nothing but Python:

```shell
./prepare.py                     # downloads the current database
./prepare.py LivsmedelsDB.xlsx   # uses a copy already downloaded
```

It keeps the name, the group and eight nutrients out of the 60 columns the
database has, and writes the name twice, to `name` and `nameWhole`.

See [ATTRIBUTION.md](ATTRIBUTION.md) for where the data comes from and what
using it asks for.
