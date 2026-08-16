#!/usr/bin/env python3
"""Turn the Swedish Food Agency's food database into documents to index.

The database is published as one spreadsheet of a row per food and a column
per nutrient. This reads it with nothing but the standard library - an xlsx
is a zip of XML - and writes the columns the example uses as newline
delimited JSON, one document per line, ready to be posted to a node.

Run it to refresh `documents.jsonl` against the current version of the
database:

    ./prepare.py                     # downloads the current database
    ./prepare.py LivsmedelsDB.xlsx   # uses a copy already downloaded

The name of every food is written twice, to `name` and to `nameWhole`. They
hold the same text and differ only in how the index analyzes it, which is
what lets the page search the same food with compound splitting on and off
and show the two side by side.
"""

import gzip
import json
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

# The download the "Ladda ner Livsmedelsdatabasen" button of the agency's
# search site asks for, which is the whole database as one spreadsheet.
DOWNLOAD_URL = "https://soknaringsinnehall.livsmedelsverket.se/Spara/HamtaHelaDatabasen"

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"

# The columns to keep, by the heading they have in the spreadsheet. Nutrients
# are per 100 grams of food, which is what the page says when it shows them.
COLUMNS = {
	"Livsmedelsnummer": "id",
	"Livsmedelsnamn": "name",
	"Gruppering": "group",
	"Energi (kcal)": "energy",
	"Fett, totalt (g)": "fat",
	"Protein (g)": "protein",
	"Kolhydrater, tillgängliga (g)": "carbohydrates",
	"Fiber (g)": "fiber",
	"Sockerarter, totalt (g)": "sugars",
	"Salt, NaCl (g)": "salt",
}

HERE = Path(__file__).parent


def download(target):
	"""Fetch the database, which is handed out in answer to a POST."""
	request = urllib.request.Request(DOWNLOAD_URL, method="POST", data=b"")
	with urllib.request.urlopen(request) as response:
		target.write_bytes(response.read())


def rows(workbook):
	"""Read every row of the single sheet as a list of cell values."""
	with zipfile.ZipFile(workbook) as archive:
		shared = [
			"".join(part.text or "" for part in entry.iter(NS + "t"))
			for entry in ET.fromstring(archive.read("xl/sharedStrings.xml"))
		]

		sheet = ET.fromstring(archive.read("xl/worksheets/sheet1.xml"))

	for row in sheet.iter(NS + "row"):
		values = []
		for cell in row:
			value = cell.find(NS + "v")
			if value is None:
				values.append("")
			elif cell.get("t") == "s":
				values.append(shared[int(value.text)])
			else:
				values.append(value.text)

		yield values


def documents(workbook):
	"""Turn the sheet into documents, skipping down to the headings first.

	The sheet opens with the version of the database and what the numbers are
	per, so the headings are the first row that holds the name column rather
	than a fixed row number.
	"""
	found = None
	for values in rows(workbook):
		if found is None:
			if "Livsmedelsnamn" in values:
				found = values
			continue

		document = {}
		for column, name in COLUMNS.items():
			raw = values[found.index(column)] if column in found else ""
			if raw == "":
				continue

			document[name] = raw if name in ("id", "name", "group") else float(raw)

		if "id" not in document or "name" not in document:
			continue

		# Searched twice over, once split into its parts and once whole
		document["nameWhole"] = document["name"]

		yield document


def main():
	workbook = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "LivsmedelsDB.xlsx"

	if len(sys.argv) <= 1 and not workbook.exists():
		print(f"Downloading the database to {workbook}")
		download(workbook)

	target = HERE / "documents.jsonl.gz"
	written = 0

	with gzip.open(target, "wt", encoding="utf-8") as out:
		for document in documents(workbook):
			out.write(json.dumps(document, ensure_ascii=False))
			out.write("\n")
			written += 1

	print(f"Wrote {written} documents to {target}")


if __name__ == "__main__":
	main()
