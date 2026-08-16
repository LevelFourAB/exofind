# Attribution

The documents in `documents.jsonl.gz` are metadata from the Cleveland Museum
of Art's open access collection - the part of it the museum has released
under [CC0](https://creativecommons.org/publicdomain/zero/1.0/), dedicated to
the public domain with no permission needed to use it. Retrieved 2026-08-16
through the museum's Open Access API:

- <https://openaccess-api.clevelandart.org/>
- <https://github.com/ClevelandMuseumArt/openaccess> (the same data as a dump)
- <https://www.clevelandart.org/open-access-api>

`prepare.py` asks for the objects marked `cc0=1` and `has_image=1` and takes
the title, the creator, the department, the kind of object, the culture, the
technique, the dates and the URL of each. Two of them are narrowed so that a
facet has values that repeat - the culture down to its first part, the
creator down to the name - and both are kept whole alongside. The values are
otherwise as published.

## The images

No images are copied here. Each document holds the URL of the museum's own
web-sized image on `openaccess-cdn.clevelandart.org`, and the page loads them
from there. Both the metadata and the images of the CC0 part of the
collection are free to use; the objects still in copyright are not part of
what the API answers with here.

The API asks for no key. It refuses a request that does not say who is
asking, so `prepare.py` sends a `User-Agent` naming the example.
