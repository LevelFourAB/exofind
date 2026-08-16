# Attribution

The documents in `documents.jsonl.gz` come from
[OurAirports](https://ourairports.com/data/), a community built database of
the airports of the world, dedicated to the public domain by its
contributors. Retrieved 2026-08-16 from the daily export:

- <https://davidmegginson.github.io/ourairports-data/airports.csv>
  - SHA-256 `f23f8924e70a585ceebc03ec4e49beb3aa7743588caf5490c045f1fe53320a71`
- <https://davidmegginson.github.io/ourairports-data/countries.csv>
  - SHA-256 `2a9dbee691125b0cdb8ceb5fe227c48c903f99c488963b8e53e2ab366521c639`

`prepare.py` keeps the airports that carry an IATA code and can be flown to,
takes the name, the code, the municipality, the size, the elevation and the
coordinates of each, resolves the country code against `countries.csv`, and
repeats the name and the municipality under a second key each. The values
themselves are as published.

The data is contributed by volunteers and carries no warranty - it is here to
search, not to navigate by.
