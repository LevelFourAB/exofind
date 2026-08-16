#!/usr/bin/env python3
"""Turn the Cleveland Museum of Art's open access collection into documents.

The museum publishes the metadata of everything it has released under CC0
through an API that hands out a thousand records at a time, so the whole of
it arrives in a minute or two. The images stay on the museum's own servers
and are linked to rather than copied, so what this writes holds a URL per
object and no pixels.

    ./prepare.py              # 30 000 objects, spread across the collection
    ./prepare.py 5000         # fewer, for a quick look

Only objects with an image are asked for, since the page shows a grid of
thumbnails. There are more of those than the example keeps, so they are taken
evenly spaced through the collection rather than from the front of it - the
API answers in accession order, which is the order the museum acquired
things, and the first thirty thousand of that would be a century of
collecting rather than a museum.
"""

import gzip
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from time import sleep

API = "https://openaccess-api.clevelandart.org/api/artworks/"

# The API turns away a request that does not say who is asking, so it says.
AGENT = "exofind-examples (https://github.com/exofind/engine)"

HERE = Path(__file__).parent

# How many objects to keep, and how many the API hands out at once.
TARGET = 30_000
PAGE = 1_000

# What to ask for of each object. A record holds its provenance, its exhibition
# history and every essay written about it, so naming the fields is the
# difference between a page of a couple of megabytes and one of several.
FIELDS = ",".join((
	"accession_number",
	"title",
	"creators",
	"creation_date",
	"creation_date_earliest",
	"creation_date_latest",
	"culture",
	"technique",
	"department",
	"type",
	"current_location",
	"creditline",
	"images",
	"url",
))


def fetch(skip):
	"""Ask for one page of objects, retrying a request that goes wrong."""
	request = urllib.request.Request(
		f"{API}?cc0=1&has_image=1&limit={PAGE}&skip={skip}&fields={FIELDS}",
		headers={"User-Agent": AGENT}
	)

	for attempt in range(5):
		try:
			with urllib.request.urlopen(request, timeout=120) as response:
				return json.load(response)
		except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError):
			sleep(2 ** attempt)

	raise SystemExit(f"The API would not answer for the page at {skip}")


def collection():
	"""Every object the museum has released with an image, in its own order."""
	first = fetch(0)
	total = first["info"]["total"]
	print(f"{total} objects released with an image")

	objects = list(first["data"])
	for skip in range(PAGE, total, PAGE):
		objects.extend(fetch(skip)["data"])
		print(f"{len(objects)}/{total}")

	return objects


def artist(object):
	"""Who made it, as a name on its own.

	The museum writes a creator as a name followed by where and when they
	worked - `Paolo Veronese (Italian, 1528-1588)` - which is worth showing
	whole but not worth counting, as no two artists would share an entry.
	"""
	creators = object.get("creators") or []
	if not creators:
		return None, None

	described = (creators[0].get("description") or "").strip()
	if not described:
		return None, None

	return described.split(" (")[0].strip(), described


def place(object):
	"""Where it comes from, as widely as the culture is written.

	A culture reads `China, Jiangxi province, Jingdezhen kilns, Qing dynasty`
	- every level of it in one string, so no two objects share one and a facet
	on it would count nothing twice. The first part is the part worth
	counting, and the whole string stays searchable.
	"""
	cultures = object.get("culture") or []
	if not cultures:
		return None, None

	return cultures[0].split(",")[0].strip(), "; ".join(cultures)


def keep(object):
	"""Keep the fields the example searches, dropping the empty ones."""
	document = {
		"id": object["accession_number"],
		"title": (object.get("title") or "").strip() or "Untitled",
		"image": object["images"]["web"]["url"],
		"url": object["url"],
	}

	name, described = artist(object)
	if name:
		document["artist"] = name
		document["artistLine"] = described

	where, culture = place(object)
	if where:
		document["place"] = where
		document["culture"] = culture

	for source, name in (
		("department", "department"),
		("type", "type"),
		("technique", "technique"),
		("creation_date", "date"),
		("creditline", "creditLine"),
	):
		value = (object.get(source) or "").strip()
		if value:
			document[name] = value

	# The years the museum dates the object between, which is what the page
	# buckets and orders by
	for source, name in (("creation_date_earliest", "year"), ("creation_date_latest", "yearEnd")):
		value = object.get(source)
		if isinstance(value, int):
			document[name] = value

	# Where in the museum it is standing, and so whether it is out at all
	gallery = (object.get("current_location") or "").strip()
	document["onView"] = bool(gallery)
	if gallery:
		document["gallery"] = gallery

	return document


def usable(object):
	"""Whether an object has everything the page needs to show it."""
	web = (object.get("images") or {}).get("web") or {}

	return bool(
		object.get("accession_number")
		and object.get("url")
		and web.get("url")
	)


def main():
	target = int(sys.argv[1]) if len(sys.argv) > 1 else TARGET

	# By accession number, which is what the index keys documents by - the
	# collection holds a handful of them twice, and two documents sharing a
	# primary key would silently be one
	objects = list({
		object["accession_number"]: object
		for object in collection() if usable(object)
	}.values())

	# Evenly spaced rather than the first of them, so the subset is a museum
	# rather than one end of its accession book
	step = max(1, len(objects) / target)
	picked = [objects[int(position * step)] for position in range(min(target, len(objects)))]

	written = 0
	with gzip.open(HERE / "documents.jsonl.gz", "wt", encoding="utf-8") as out:
		for object in picked:
			out.write(json.dumps(keep(object), ensure_ascii=False) + "\n")
			written += 1

	print(f"Wrote {written} documents to documents.jsonl.gz")


if __name__ == "__main__":
	main()
