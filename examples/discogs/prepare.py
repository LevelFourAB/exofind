#!/usr/bin/env python3
"""Turn the Discogs monthly data dump into a catalogue of records and pressings.

Discogs publishes what its contributors have catalogued as a monthly XML dump
under CC0. Two of the four files matter here, and the shape of a shop falls
straight out of them:

- A *master* is a record as a thing - `The Dark Side Of The Moon` - and becomes
  a product.
- A *release* is one pressing of it - the 1973 UK vinyl, the 1994 CD remaster,
  the 2016 gatefold reissue - and becomes a variant of that product.

    ./prepare.py              # 50 000 records, spread across the catalogue
    ./prepare.py 5000         # fewer, for a quick look
    ./prepare.py 300000       # more, for a node with room for them
    ./prepare.py 1000000 3    # more again, by keeping records of three pressings

The second number is the fewest pressings a record needs to be kept, and it is
what a large catalogue is traded against: 756 000 masters carry four pressings
or more, 1 240 000 carry three, and 2 579 000 carry two. Asking for more
records than carry the current number says so and says what to run instead.

The releases dump is 10 GB compressed and roughly 19 million records, so the
first run takes about half an hour and needs 20 GB of scratch space. Every
stage writes to `.cache/` and is skipped when its output is already there, so
asking for a different number of products afterwards costs seconds rather than
another pass. Delete `.cache/` to start over.

Prices and stock are invented. Discogs publishes no prices - what its
marketplace charges is not in the dump - and a shop without them cannot show
the thing this example exists to show, which is a condition that has to hold
within one variant rather than anywhere in the product. They are derived from
the release id, so they are the same on every machine and every run, and both
this file and the page say they are made up. Nothing else here is: the titles,
artists, formats, countries, labels and catalogue numbers are as published.
"""

import collections
import gzip
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path
from xml.etree.ElementTree import XMLParser

HERE = Path(__file__).parent
CACHE = HERE / ".cache"

# Which month's dump to build from. A dump is replaced rather than amended, so
# naming one keeps a rebuild comparable with what is checked in. The listing at
# https://data.discogs.com/ says which months there are.
DUMP = "20260801"
DOWNLOAD = "https://data.discogs.com/?download=data%2F{year}%2Fdiscogs_{dump}_{part}.xml.gz"

# How many products to keep, how many pressings to keep of one product, and how
# many pressings a product needs before it is worth keeping at all. The cap is
# there because a record that sold keeps being pressed - some masters carry
# over a thousand releases - and every value is a document of its own inside
# the block holding the product. See docs/explanation/document-blocks.md.
TARGET = 50_000
MAX_VARIANTS = 12
MIN_VARIANTS = 4

# Subtrees of a release that nothing here reads. Skipping them saves building
# the Python objects; the parser still has to run over the bytes.
SKIP = frozenset((
	"tracklist", "videos", "identifiers", "companies", "extraartists",
	"notes", "series", "sublabels", "images", "profile", "urls",
	"namevariations", "aliases", "members", "groups", "contact_info",
))

# Discogs tells two artists of the same name apart by numbering the later one,
# so `Nirvana` is the band and `Nirvana (2)` is somebody else. The number is
# worth keeping out of a facet, where it would split one name into several.
DISAMBIGUATED = re.compile(r"\s*\(\d+\)$")

# A release dates itself as `1973`, `1973-03` or `1973-03-00`, and says `0` or
# nothing when nobody knows.
YEAR = re.compile(r"^(\d{4})")

# What a pressing costs before anything is done to it, by what it is pressed
# on, in euro.
BASE_PRICE = {
	"Vinyl": 20.0,
	"Box Set": 44.0,
	"CD": 8.5,
	"Cassette": 6.0,
	"Reel-To-Reel": 32.0,
	"Shellac": 24.0,
	"Acetate": 36.0,
	"Lathe Cut": 27.0,
	"Flexi-disc": 5.5,
	"File": 4.0,
	"DVD": 10.0,
	"Blu-ray": 13.0,
	"SACD": 16.0,
}
DEFAULT_PRICE = 11.0

# What a price is rounded to, and the least a pressing goes for.
PRICE_STEP = 0.5
PRICE_FLOOR = 2.5

# What a pressing being one of these does to the price. A reissue is the cheap
# way to own a record and an original is not, which is most of what makes a
# second-hand price interesting to filter on.
PRICE_FOR_SPEC = {
	"Reissue": 0.65,
	"Repress": 0.7,
	"Unofficial Release": 0.6,
	"Compilation": 0.85,
	"Remastered": 1.15,
	"Deluxe Edition": 1.35,
	"Limited Edition": 1.5,
	"Numbered": 1.4,
	"Coloured Vinyl": 1.35,
	"Picture Disc": 1.45,
	"180 Gram": 1.25,
	"Gatefold": 1.1,
	"Promo": 1.6,
	"Test Pressing": 3.0,
	"Misprint": 2.0,
	"White Label": 1.8,
}


def say(message):
	"""Say where the run has got to, now rather than at the end of it.

	Python holds what a script writes back when it is not writing to a
	terminal, so a run piped to a file or a log would sit silent for half an
	hour and then say all of it at once.
	"""
	print(message, flush=True)


def digest(*parts):
	"""A number between 0 and 1 for these parts, the same one everywhere.

	Python salts `hash` differently in every process, so a run would price a
	record differently from the last one. This does not.
	"""
	raw = hashlib.blake2b("/".join(parts).encode("utf-8"), digest_size=8).digest()

	return int.from_bytes(raw, "big") / float(1 << 64)


def price_of(release_id, format, specs, year):
	"""Invent what a pressing costs, from what it is and how old it is."""
	price = BASE_PRICE.get(format, DEFAULT_PRICE)

	for spec in specs:
		price *= PRICE_FOR_SPEC.get(spec, 1.0)

	# Older is dearer, levelling off before the war, because a shop that priced
	# 1925 at ten times 1975 would have one bucket holding everything.
	if year:
		price *= 1.0 + 0.02 * min(60, max(0, 1995 - year))

	# Enough of a spread that two pressings of the same kind are not the same
	# price, and not so much that the kind stops showing through.
	price *= 0.8 + 0.5 * digest("price", release_id)

	return max(PRICE_FLOOR, round(price / PRICE_STEP) * PRICE_STEP)


def stock_of(release_id, year):
	"""Invent whether a pressing is in stock. Old ones run out more often."""
	likelihood = 0.85 if not year or year >= 2000 else 0.55

	return digest("stock", release_id) < likelihood


def named(value):
	"""Whether a genre or a style is one somebody could have meant.

	Anyone can edit Discogs, so a handful of records carry a genre of `1`
	where a number was typed into the wrong box. A facet answers its most
	common values, and one of them being a digit reads as the search being
	broken instead of the catalogue having a typo in it.
	"""
	return bool(value) and any(character.isalpha() for character in value)


def plain(name):
	"""A name without the number Discogs tells duplicates apart by."""
	return DISAMBIGUATED.sub("", name).strip()


def year_of(text):
	"""The year in a Discogs date, or None where there is not one."""
	found = YEAR.match(text or "")
	if not found:
		return None

	year = int(found.group(1))

	return year if 1880 <= year <= 2100 else None


def credited(artists, joins):
	"""Who a record is by, both as one line and as names on their own.

	Discogs writes a credit as artists with the words between them, so
	`Simon & Garfunkel` is two artists joined by `&` and is worth showing that
	way. A facet wants the names apart, because nobody looks for the ampersand.
	"""
	names = [plain(name) for name in artists if plain(name)]
	if not names:
		return None, []

	line = names[0]
	for name, join in zip(names[1:], joins):
		separator = (join or ",").strip()
		line += f" {separator} " if separator not in (",", "") else f"{separator} "
		line += name

	return line, names


class Records:
	"""Reads a Discogs dump, calling `found` with a dict per record.

	The dump is one enormous element holding every record, so nothing can hold
	the tree it parses to. This walks it as it arrives and keeps only the
	fields the example searches, which is a few dozen bytes of the couple of
	kilobytes a release is written as.
	"""

	def __init__(self, tag, found):
		self.tag = tag
		self.found = found
		self.count = 0

		self.skipping = 0
		self.path = []
		self.text = []
		self.record = None
		self.format = None

	def start(self, tag, attributes):
		if self.skipping:
			self.skipping += 1
			return

		if tag in SKIP:
			self.skipping = 1
			return

		self.path.append(tag)
		self.text = []

		if tag == self.tag:
			self.record = {
				"id": attributes.get("id"),
				"artists": [], "joins": [], "genres": [], "styles": [],
				"labels": [], "formats": [],
			}
		elif self.record is None:
			pass
		elif tag == "label":
			self.record["labels"].append((attributes.get("name"), attributes.get("catno")))
		elif tag == "format":
			self.format = {"name": attributes.get("name"), "specs": []}
			self.record["formats"].append(self.format)
		elif tag == "master_id":
			self.record["main"] = attributes.get("is_main_release") == "true"

	def data(self, data):
		if not self.skipping:
			self.text.append(data)

	def end(self, tag):
		if self.skipping:
			self.skipping -= 1
			return

		self.path.pop()
		value = "".join(self.text).strip()
		self.text = []

		record = self.record
		if record is None:
			return

		if tag == self.tag:
			self.record = None
			self.format = None
			self.count += 1
			self.found(record)
		elif tag == "name" and self.path[-1:] == ["artist"]:
			record["artists"].append(value)
		elif tag == "join":
			record["joins"].append(value)
		elif tag == "genre":
			record["genres"].append(value)
		elif tag == "style":
			record["styles"].append(value)
		elif tag == "description" and self.format is not None:
			self.format["specs"].append(value)
		elif tag in ("title", "country", "released", "year", "master_id", "main_release"):
			record[tag] = value

	def close(self):
		return self.count


def walk(path, tag, found, every=1_000_000):
	"""Run `found` over every record in a gzipped dump."""
	records = Records(tag, found)
	parser = XMLParser(target=records)

	with gzip.open(path, "rb") as raw:
		while True:
			chunk = raw.read(1 << 20)
			if not chunk:
				break

			parser.feed(chunk)
			if records.count // every > (records.count - 1) // every:
				say(f"  {records.count:,} {tag}s")

	parser.close()
	say(f"  {records.count:,} {tag}s, done")


def download(part):
	"""Fetch one file of the dump, unless it is already here."""
	path = CACHE / f"discogs_{DUMP}_{part}.xml.gz"
	if path.exists():
		return path

	url = DOWNLOAD.format(year=DUMP[:4], dump=DUMP, part=part)
	say(f"Downloading {path.name}")

	def build(partial):
		with urllib.request.urlopen(url, timeout=120) as response, partial.open("wb") as out:
			while chunk := response.read(1 << 22):
				out.write(chunk)

	return staged(path, build)


def staged(path, build):
	"""Build a cached file, unless it is already there.

	Every stage writes beside its own name and is renamed into place once it
	has finished, because the pass over the releases takes half an hour and a
	half-written file left by an interrupted run would be read as a whole one.
	"""
	if path.exists():
		return path

	partial = path.with_name(path.name + ".partial")
	build(partial)
	partial.rename(path)

	return path


def sort_by_id(path):
	"""Sort a `<id>\\t<json>` file by its id, so two of them can be walked together."""
	sorted_path = path.with_suffix(".sorted")
	if sorted_path.exists():
		if path.exists():
			path.unlink()

		return sorted_path

	say(f"Sorting {path.name}")

	def build(partial):
		subprocess.run(
			["sort", "-t", "\t", "-k1,1n", "-T", str(CACHE), "-o", str(partial), str(path)],
			check=True, env=dict(os.environ, LC_ALL="C")
		)

	staged(sorted_path, build)
	path.unlink()

	return sorted_path


def read_masters():
	"""Write every master as a product, keyed by its id."""
	sorted_path = CACHE / "masters.sorted"
	if sorted_path.exists():
		return sorted_path

	def build(partial):
		source = download("masters")
		say("Reading masters")

		with partial.open("w", encoding="utf-8") as out:
			def found(master):
				line, names = credited(master["artists"], master["joins"])
				if not line or not master.get("title") or not master.get("id"):
					return

				product = {
					"title": master["title"],
					"artistLine": line,
					"artist": names,
					"url": f"https://www.discogs.com/master/{master['id']}",
				}

				for source_name, name in (("genres", "genre"), ("styles", "style")):
					values = [value for value in master[source_name] if named(value)]
					if values:
						product[name] = values

				year = year_of(master.get("year"))
				if year:
					product["year"] = year

				out.write(f"{master['id']}\t{json.dumps(product, ensure_ascii=False)}\n")

			walk(source, "master", found, every=500_000)

	return sort_by_id(staged(CACHE / "masters.tsv", build))


def read_releases():
	"""Write every release that belongs to a master as a variant of it."""
	sorted_path = CACHE / "variants.sorted"
	if sorted_path.exists():
		return sorted_path

	def build(partial):
		source = download("releases")
		say("Reading releases - this is the long one")

		with partial.open("w", encoding="utf-8") as out:
			def found(release):
				master = release.get("master_id")
				if not master or master == "0" or not release.get("id"):
					return

				formats = [one for one in release["formats"] if one["name"]]
				if not formats:
					return

				# A release pressed on more than one thing takes the name of the
				# first, and the descriptions of all of them.
				specs = []
				for one in formats:
					for spec in one["specs"]:
						if spec and spec not in specs:
							specs.append(spec)

				# What the release is, and nothing worked out from it. The price
				# and the stock are put on at the join, where changing how they
				# are worked out costs a pass over a file instead of another
				# half hour over the dump.
				year = year_of(release.get("released"))
				variant = {
					"sku": release["id"],
					"format": formats[0]["name"],
				}

				if specs:
					variant["spec"] = specs
				if year:
					variant["year"] = year
				if release.get("title"):
					variant["title"] = release["title"]
				if release.get("country"):
					variant["country"] = release["country"]

				# The first label credited, as published. A release lists the
				# same label once per catalogue number it carries, so the
				# first is the one to take. `Not On Label` is a label here:
				# it is what a bootleg or a self-release is credited to.
				for name, catno in release["labels"]:
					if name:
						variant["label"] = plain(name)
						if catno and catno.lower() not in ("none", "n/a", "-"):
							variant["catno"] = catno
						break

				line = json.dumps(
					[release.get("main", False), variant], ensure_ascii=False
				)
				out.write(f"{master}\t{line}\n")

			walk(source, "release", found)

	return sort_by_id(staged(CACHE / "variants.tsv", build))


def grouped(path):
	"""Walk a sorted `<id>\\t<json>` file, handing over one id at a time."""
	current = None
	values = []

	with path.open(encoding="utf-8") as lines:
		for line in lines:
			key, _, payload = line.partition("\t")
			key = int(key)

			if key != current:
				if current is not None:
					yield current, values
				current, values = key, []

			values.append(json.loads(payload))

	if current is not None:
		yield current, values


def priced(variant):
	"""Put the invented price and stock on a pressing.

	Neither is in the dump. Both come from the release id through a fixed
	hash, so a rebuild prices the catalogue the same way.
	"""
	variant["price"] = price_of(
		variant["sku"], variant["format"], variant.get("spec", []), variant.get("year")
	)
	variant["inStock"] = stock_of(variant["sku"], variant.get("year"))

	return variant


def pick_variants(variants):
	"""Choose which pressings to keep, spread across the formats there are.

	A record that sold has been pressed hundreds of times, mostly on the same
	thing in the same country, and a product holding all of them is neither
	readable nor cheap. Taking them one format at a time means a product that
	exists on vinyl, CD and cassette shows all three rather than twelve vinyl.
	"""
	by_format = {}
	for main, variant in variants:
		priced(variant)
		by_format.setdefault(variant["format"], []).append((not main, variant.get("year") or 9999, variant))

	for candidates in by_format.values():
		candidates.sort(key=lambda candidate: (candidate[0], candidate[1], candidate[2]["sku"]))

	# Biggest format first, so the way most of them were pressed leads
	order = sorted(by_format.values(), key=len, reverse=True)

	picked = []
	while len(picked) < MAX_VARIANTS:
		took = False
		for candidates in order:
			if not candidates:
				continue

			picked.append(candidates.pop(0)[2])
			took = True
			if len(picked) >= MAX_VARIANTS:
				break

		if not took:
			break

	# Cheapest first, because a search asks for a few of the values a record
	# matched and answers them in the order the record holds them. Holding
	# them by price means the few a page shows are the cheapest ones that
	# matched, which is what a shop puts at the top of a list of pressings.
	picked.sort(key=lambda variant: (variant["price"], variant["sku"]))

	return picked


def main_format(found):
	"""What a record mostly exists as, over every pressing of it.

	Counted before the cap rather than after. `pick_variants` takes one format
	at a time on purpose, so the dozen it keeps hold roughly equal numbers of
	each and counting those would call a record that ran to nine hundred vinyl
	pressings and two cassettes a cassette.
	"""
	counted = collections.Counter(variant["format"] for main, variant in found)

	# By count, then by name, so a tie falls the same way on every machine
	return min(counted.items(), key=lambda entry: (-entry[1], entry[0]))[0]


def join(masters_path, variants_path):
	"""Put the pressings of a master onto it, writing every product there is."""
	def build(partial):
		say("Joining masters to their pressings")
		products = 0
		variants = grouped(variants_path)
		pending = next(variants, None)

		with partial.open("w", encoding="utf-8") as out:
			for master_id, records in grouped(masters_path):
				while pending is not None and pending[0] < master_id:
					pending = next(variants, None)

				if pending is None or pending[0] != master_id:
					continue

				found = pending[1]
				pending = next(variants, None)

				product = dict(records[0])
				product["id"] = str(master_id)
				product["pressings"] = len(found)
				product["variants"] = pick_variants(found)
				product["mainFormat"] = main_format(found)

				# A pressing is usually titled the same as the record, so the
				# title is kept only where it says something the record does
				# not - a deluxe edition, or a title changed for one market.
				for variant in product["variants"]:
					if variant.get("title") == product["title"]:
						del variant["title"]

				out.write(json.dumps(product, ensure_ascii=False) + "\n")
				products += 1

		say(f"  {products:,} products")

	return staged(CACHE / "products.jsonl", build)


def wanted(product, least):
	"""Whether a product is worth putting in front of somebody.

	A record needs several pressings before it shows what the example is for.
	Around half of all masters carry exactly two, which is a thin thing to
	compare and a thinner one to filter, so the catalogue asks for more.

	Asking for more records means asking for less of them: 756 000 masters
	carry four pressings, 1 240 000 carry three, and 2 579 000 carry two.
	"""
	return product["pressings"] >= least


def sample(products_path, target, least):
	"""Take an evenly spaced subset, and write it where load.sh looks for it.

	The cache holds every product there is, so the size and the cut-off are
	both decided here. Asking for another number costs a pass over a file
	rather than another pass over the dump.
	"""
	def eligible():
		with products_path.open(encoding="utf-8") as lines:
			for line in lines:
				if wanted(json.loads(line), least):
					yield line

	total = sum(1 for _ in eligible())

	# Evenly spaced rather than the first of them, because a master id says
	# when somebody catalogued the record rather than anything about it, and
	# the front of that is twenty years of dance twelve-inches.
	step = max(1, total / target)
	picked = {int(position * step) for position in range(min(target, total))}

	written = 0
	with gzip.open(HERE / "documents.jsonl.gz", "wt", encoding="utf-8") as out:
		for position, line in enumerate(eligible()):
			if position in picked:
				out.write(line)
				written += 1

	say(f"Wrote {written:,} of {total:,} records to documents.jsonl.gz")

	# Asking for more records than carry that many pressings writes every one
	# there is and no more, so the run says how to reach the number asked for.
	if written < target and least > 2:
		say(
			f"  {target:,} was asked for. Only {total:,} records have {least}"
			f" pressings or more - run `./prepare.py {target} {least - 1}` for a"
			" wider catalogue."
		)


def main():
	target = int(sys.argv[1]) if len(sys.argv) > 1 else TARGET
	least = int(sys.argv[2]) if len(sys.argv) > 2 else MIN_VARIANTS
	CACHE.mkdir(exist_ok=True)

	masters_path = read_masters()
	variants_path = read_releases()
	sample(join(masters_path, variants_path), target, least)


if __name__ == "__main__":
	main()
