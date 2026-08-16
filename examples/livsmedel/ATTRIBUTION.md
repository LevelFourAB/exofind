# Attribution

The documents in `documents.jsonl.gz` come from Livsmedelsverkets
livsmedelsdatabas (the Swedish Food Agency's food composition database),
version 2026-07-01, published by Livsmedelsverket under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.en). Retrieved
2026-08-16.

- <https://soknaringsinnehall.livsmedelsverket.se/Spara/HamtaHelaDatabasen>
  (the whole database as a spreadsheet, handed out in answer to a `POST`)
- SHA-256 `0e821c26fce1422a9c637fff5cdb5d04028a89f9b1980c1f8ddaccee341de948`

`prepare.py` keeps a subset of the columns - the name, the group and eight
nutrients - and repeats the name under a second key. The values themselves
are as published, which is what the agency asks of anyone using them: cite
the source and do not alter the data.

The agency also publishes the same database through an API documented at
<https://www.livsmedelsverket.se/en/about-us/open-data/food-composition-data/>.
