# Attribution

The documents in `documents.jsonl.gz` are built from the Discogs monthly data
dump of 2026-08-01, which Discogs publishes under
[CC0](https://creativecommons.org/publicdomain/zero/1.0/), dedicated to the
public domain with no permission needed to use it:

- <https://data.discogs.com/>
- <https://www.discogs.com/developers> (the same shapes, as an API)

`prepare.py` reads two of the four files in the dump. A *master* becomes a
product and a *release* becomes one of its variants, joined on the `master_id`
each release carries.

## What comes from the dump

These values are as Discogs publishes them:

| Field | From |
| --- | --- |
| `title`, `year`, `genre`, `style` | The master |
| `artist`, `artistLine` | The artists credited on the master |
| `variants.title`, `variants.country` | The release |
| `variants.format`, `variants.spec` | The formats of the release |
| `variants.year` | The date the release is published under |
| `variants.label`, `variants.catno` | The first label credited on the release |

Two of them are narrowed so that a facet has values that repeat:

- Discogs numbers the second artist to use a name, so `Nirvana (2)` is a
  different band from `Nirvana`. The number is dropped from `artist` and from
  `variants.label`, and a facet counts the two together.
- A credit spanning several artists is kept whole as `artistLine` for a page to
  show, and split into `artist` for a facet to count.

The free text a contributor can add beside a format, such as `180 Gram Clear
Vinyl`, is dropped. `variants.spec` holds only the descriptions Discogs offers
from a list, so the values repeat often enough to count.

Anyone can edit Discogs, so a genre or a style is kept only when it holds a
letter. This drops the handful of records carrying a genre of `1`, where a
number was typed into the wrong box.

`mainFormat` is counted rather than published: it is the format most of a
record's pressings are, which is what the page draws the sleeve as.

## What is invented

**`variants.price` and `variants.inStock` are made up.** Discogs publishes no
prices. What its marketplace charges is not part of the dump, and this example
needs a price to show a condition that has to hold within one variant.

Both are derived from the release id with a fixed hash, so a rebuild produces
the same catalogue. The price starts from what the release is pressed on,
moves with what the pressing is (a reissue is cheaper, a test pressing is
dearer), rises with age, and takes a spread of up to 30 percent. Stock is more
likely on a release from 2000 or later. The amounts are in euro, rounded to
the half.

None of this reflects any real price. `prepare.py` documents the calculation,
and the demo page says the prices are invented.

## What is left out

The dump holds tracklists, credits, notes, videos, and identifiers for every
release. None of them are read, which keeps a document a few hundred bytes
instead of a few kilobytes.

## The images

No images are copied here, and none are linked. Discogs images are not part of
the dump and are not under CC0. The demo page draws a sleeve for each record
from its own title, artist, and genre.
