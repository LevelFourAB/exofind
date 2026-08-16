# Airport type-ahead

Every airport in the world that carries an IATA code - 8 799 of them -
completed as you type.

```shell
./load.sh                  # define the index, index the documents, commit
cd .. && npm run dev       # serve the page against a node on localhost:8080
```

## What it shows

**Two ways of completing what somebody typed, and what each is for.** The
page searches `nameAhead` and `municipalityAhead`, defined for
`autocomplete`, so every prefix of every word is indexed and `sto` is looked
up as a word rather than scanned for. They declare `typoTolerance`, so
`stockhlm` finds Stockholm and `stokho` finds it while the word is still
being typed. The code is completed the other way - `iata` holds one value
rather than a sentence of them, so it answers a `prefix` matcher on the whole
of what was typed, which is what lets `ES` list Sweden and `ord` find
O'Hare. Neither clause is narrower than the other, so the two are an `or`.

The index also holds every name a second time in `name` and `municipality`,
defined for `matching`, where words are matched whole and a quoted
`"stockholm bromma"` can ask for them in that order - something a field
holding prefixes has no order to answer for. Nothing but having both to look
at needs the name twice; an index would normally pick the usage it wants.

**What a field is worth is the definition's to say.** `iata` declares
`"weight": 8` and `nameAhead` declares `3`, so a hit in the code counts for
more than a hit in a name. Searching `ord` puts Chicago O'Hare first for that
reason; flattening the weights to one drops it below Ordos and Ord River,
which have `ord` in their names. The search names the fields and leaves the
weights to the index - sending `null` for a field is what asks for the weight
it declares.

**Nearness, from a filter and a sort.** Picking somewhere to measure from
adds a `distance` matcher on the `geo_point` field, bounded by the radius,
and orders by a `distance` sort when there is no text to rank by. With text,
relevance stays the order and nearness only bounds what may show up - a
search for `sto` near Stockholm is still a search for `sto`. The kilometers
in each row are the browser's own arithmetic on the coordinates that came
back with the document.

What each search cost the node is in `tookMs`, and the page says it beside
the count under the field. What was searched for is in the URL, so a search
worth showing someone is a link:

```
index.html?q=ord&weighting=flat&near=59.33,18.07&radius=1000
```

## The data

`documents.jsonl.gz` holds the documents, one per line, ready to be posted to
a node. `prepare.py` rebuilds it from the published CSV files and needs
nothing but Python:

```shell
./prepare.py                                # downloads the current data
./prepare.py airports.csv countries.csv     # uses copies already downloaded
```

It keeps the airports that have an IATA code and are somewhere a flight can
land - closed fields, heliports and seaplane bases are left out - and writes
the name and the municipality twice each, once for completing and once for
matching.

See [ATTRIBUTION.md](ATTRIBUTION.md) for where the data comes from.
