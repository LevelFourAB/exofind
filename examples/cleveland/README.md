# Cleveland Museum of Art

30 000 objects from the museum's open access collection, on a wall of
thumbnails you can narrow from the side.

```shell
./load.sh                  # define the index, index the documents, commit
cd .. && npm run dev       # serve the page against a node on localhost:8080
```

## What it shows

**Facets counted sideways of what has been ticked.** Tick a department and
the objects narrow, the other facets narrow with them - and the department
counts stay as they were, so the list is still worth reading. That is the
whole reason a request keeps `filters` apart from `query`: a filter narrows
every facet except the one on its own field, while what was typed into the
search box narrows every count there is. A grid of pictures is what makes it
obvious, because the wall changes while the numbers beside it do not.

**Buckets, for a field where every value is its own.** No two objects share a
year worth counting, so `year` is counted into ranges instead - a facet with
`ranges` answers `buckets` in place of `values`, and picking one sends it
straight back as a `range` filter.

**Two ways of paging, from one response.** Numbered pages need the whole
count and a position that can be counted to; the response works the numbers
out and hands back `start`, `middle` and `end` runs with the gaps exactly
where an ellipsis belongs, each entry carrying the cursor that fetches it.
Going on from a cursor needs neither, costs the same however deep it has
gone, and is what the "keep going" button follows. The page builds both, so
the difference is a control rather than a paragraph.

**Highlighting what matched.** The title, the artist and what the object is
made of come back with the matched words marked, and the material is only
shown when it is why the object is there - which for `gold` is most of them.

What was searched for is in the URL, so a search worth showing someone is a
link:

```
index.html?q=dragon&department=Chinese%20Art&made=1400-1700&order=old
```

## The data

`documents.jsonl.gz` holds the documents, one per line, ready to be posted to
a node. `prepare.py` builds it from the museum's open access API and needs
nothing but Python:

```shell
./prepare.py              # 30 000 objects, spread across the collection
./prepare.py 5000         # fewer, for a quick look
```

The API hands out a thousand records at a time, so the whole collection
arrives in a couple of minutes. Only the objects released under CC0 with an
image are asked for, since the page is a grid of pictures, and the subset is
taken evenly spaced through them rather than from the front - the API answers
in accession order, and the first thirty thousand of that would be a century
of collecting rather than a museum.

Two fields are narrowed from what the museum publishes, because a facet has
to have values that repeat:

- `place` is the first part of the culture, so `China, Jiangxi province,
  Jingdezhen kilns, Qing dynasty` is counted as `China`. The whole of it
  stays searchable as `culture`.
- `artist` is the creator's name without the parenthesis that follows it, so
  `Paolo Veronese (Italian, 1528-1588)` is counted as `Paolo Veronese`. The
  full line is kept as `artistLine`.

The images are not copied - each document holds the URL of the museum's own
web-sized image, and the page links to them.

See [ATTRIBUTION.md](ATTRIBUTION.md) for where the data comes from.
