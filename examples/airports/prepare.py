#!/usr/bin/env python3
"""Turn the OurAirports database into documents to index.

OurAirports publishes the airports of the world as CSV, one row per airport
and one file of country codes to names. This keeps the airports that carry
an IATA code - the three letters printed on a boarding pass, which is what
somebody types - and writes them as newline delimited JSON, one document per
line, ready to be posted to a node.

    ./prepare.py              # downloads the current data
    ./prepare.py airports.csv countries.csv   # uses copies already downloaded

The name and the municipality are each written twice, to `name` and
`nameAhead` and to `municipality` and `municipalityAhead`. They hold the
same text and differ only in how the index analyzes it: one is indexed for
matching whole words with typing mistakes forgiven, the other for completing
a word somebody is still typing. The page searches one pair or the other and
shows what each finds, which is what it is there to compare.
"""

import csv
import gzip
import json
import sys
import urllib.request
from pathlib import Path

DATA = "https://davidmegginson.github.io/ourairports-data"

HERE = Path(__file__).parent

# What the type column says, as the page says it. Anything not here - a
# closed field, a heliport - is left out: an airport nobody can fly from is
# not what somebody typing three letters is looking for.
SIZES = {
	"large_airport": "large",
	"medium_airport": "medium",
	"small_airport": "small",
}

def download(name, target):
	print(f"Downloading {name} to {target}")
	with urllib.request.urlopen(f"{DATA}/{name}") as response:
		target.write_bytes(response.read())


def read(path, name):
	"""Read one of the CSV files, downloading it if it is not here yet."""
	if not path.exists():
		download(name, path)

	with path.open(encoding="utf-8", newline="") as source:
		return list(csv.DictReader(source))


def number(text):
	"""A column as a number, or nothing when the row left it empty."""
	try:
		return float(text)
	except (TypeError, ValueError):
		return None


def documents(airports, countries):
	names = {row["code"]: row["name"] for row in countries}

	for row in airports:
		iata = row["iata_code"].strip()
		size = SIZES.get(row["type"])
		latitude, longitude = number(row["latitude_deg"]), number(row["longitude_deg"])

		if not iata or size is None or latitude is None or longitude is None:
			continue

		document = {
			"id": row["ident"],
			"name": row["name"],
			"nameAhead": row["name"],
			"iata": iata,
			"size": size,
			"country": names.get(row["iso_country"], row["iso_country"]),
			"location": { "latitude": latitude, "longitude": longitude },
			"scheduled": row["scheduled_service"] == "yes",
		}

		municipality = row["municipality"].strip()
		if municipality:
			document["municipality"] = municipality
			document["municipalityAhead"] = municipality

		elevation = number(row["elevation_ft"])
		if elevation is not None:
			document["elevation"] = int(elevation)

		yield document


def main():
	given = sys.argv[1:]
	airports = Path(given[0]) if len(given) > 0 else HERE / "airports.csv"
	countries = Path(given[1]) if len(given) > 1 else HERE / "countries.csv"

	rows = read(airports, "airports.csv")
	names = read(countries, "countries.csv")

	target = HERE / "documents.jsonl.gz"
	written = 0

	with gzip.open(target, "wt", encoding="utf-8") as out:
		for document in documents(rows, names):
			out.write(json.dumps(document, ensure_ascii=False))
			out.write("\n")
			written += 1

	print(f"Wrote {written} documents to {target}")


if __name__ == "__main__":
	main()
