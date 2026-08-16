# Decompounding data

The files in this directory drive the splitting of compound words at
indexing. Each data set holds a hyphenation grammar (`patterns.txt.gz`, one
Liang pattern per line) and a word list of compound parts (`words.txt.gz`,
one word per line), both derived from the sources below with the converters
in `tools/decompound/`. This file is the attribution for those sources and
ships wherever the data does.

Conversion keeps the sources' linguistic content: patterns are extracted
from the TeX `\patterns` blocks unchanged, and word lists are filtered to
single words of three letters or more in the word classes compounds are
built from (nouns, adjectives, verbs and their compounding stems). All
sources were retrieved on 2026-08-16.

## da - Danish

**Grammar**: Danish hyphenation patterns by Frank Jensen (1994, hyph-utf8
version 2011-01-11), used under the MIT license option of its dual
LPPL 1.3+/MIT licensing. Copyright (C) 1994 Frank Jensen.
Merged with the Norwegian patterns described under `nb` below: the Danish
set alone is too sparse to find most compound boundaries, and Bokmål
orthography is close enough that its patterns supply the missing break
points, with wrong candidates filtered by the word list.

- <https://raw.githubusercontent.com/hyphenation/tex-hyphen/master/hyph-utf8/tex/generic/hyph-utf8/patterns/tex/hyph-da.tex>
- SHA-256 `23e0a50f0c20d632be06f973e700323463961eac737bb14f6c3787675a4f5575`

**Words**: Det Centrale Ordregister (COR) version 1.5.1.0, published by
Dansk Sprognævn and Styrelsen for Dataforsyning og Infrastruktur under
CC0 1.0.

- <https://ordregister.dk/files/cor1.5.1.0.tsv>
- SHA-256 `1c4d1c06bd676e66be8e8c0af68615c8a892472a05af3b261ba8a9d1f2d2b82b`

## de - German

**Grammar**: `dehyphn-x-2024-02-28.pat` from the `dehyph-exptl` package by
the Deutschsprachige Trennmustermannschaft, MIT license. Copyright (c)
2013-2024 Stephan Hennig, Werner Lemberg, Günter Milde, Sander van Geloven,
Georg Pfeiffer, Gisbert W. Selke, Tobias Wendorf, Keno Wehr.

- <https://mirrors.ctan.org/language/hyphenation/dehyph-exptl.zip>
- SHA-256 `a76f094eec36ded0f37137a39c791dd39e9aae25559131b4b60b7f459df70ba4`

**Words**: German lexemes from the Wikidata lexeme dump
(`latest-lexemes.json.gz`), published by the Wikimedia Foundation under
CC0 1.0.

- <https://dumps.wikimedia.org/wikidatawiki/entities/latest-lexemes.json.gz>
- SHA-256 `2933e168ab15bb86bf6db6787d5de18c22b780dbcfd141fb50051509ff6ea802`

## nb, nn - Norwegian Bokmål and Nynorsk

**Grammar** (shared): Norwegian hyphenation patterns from hyph-utf8
(`hyph-no.tex`, version 2012), Copyright (C) 2004-2005 Rune Kleveland,
Ole Michael Selberg, 2007 Karl Ove Hufthammer. License: "Copying and
distribution of this file, with or without modification, is permitted in
any medium without royalty provided the copyright notice and this notice
are preserved."

- <https://raw.githubusercontent.com/hyphenation/tex-hyphen/master/hyph-utf8/tex/generic/hyph-utf8/patterns/tex/hyph-no.tex>
- SHA-256 `05a2ac5fbf88bdc78c580e5cb8a158e6aa760d13756604f9523aa99feec3ec65`

**Words**: Norsk ordbank, published by Nasjonalbiblioteket (Språkbanken)
and the University of Oslo under CC-BY 4.0. Bokmål edition 2005, Nynorsk
edition 2012, both in the 2019-01-23 packaging.

- <https://www.nb.no/sbfil/leksikalske_databaser/ordbank/20190123_norsk_ordbank_nob_2005.tar.gz>
  SHA-256 `bc3570cb23270745087619ea446fe3e84978fc13d1aacac0d2f9c9ce62197437`
- <https://www.nb.no/sbfil/leksikalske_databaser/ordbank/20190123_norsk_ordbank_nno_2012.tar.gz>
  SHA-256 `bb8cfee8f9459928a966231b049f0898f666b5701af0ac37fe9bf1048615a96d`

## nl - Dutch

**Grammar**: Dutch hyphenation patterns from hyph-utf8 (`hyph-nl.tex`,
version 1.1, 1996), MIT license. Copyright (C) 1996 Piet Tutelaers.

- <https://raw.githubusercontent.com/hyphenation/tex-hyphen/master/hyph-utf8/tex/generic/hyph-utf8/patterns/tex/hyph-nl.tex>
- SHA-256 `d21499bfbee53e4d50e867ef92a41fd28b65725d7c91c980e4f8f02889bf6b2a`

**Words**: the OpenTaal word list (`wordlist.txt`), by Stichting OpenTaal
(<https://opentaal.org>), used under the Revised BSD License option of its
dual BSD/CC-BY 3.0 licensing. Copyright (c) OpenTaal. Redistribution
requires this notice; the list carries the Dutch Language Union's
Keurmerk Spelling only when unmodified, and this filtered derivative does
not claim it.

- <https://raw.githubusercontent.com/OpenTaal/opentaal-wordlist/master/wordlist.txt>
- SHA-256 `12e5fb5e3c73840b583b30016926d1f63a75e9bf1652a3a6634b2ba7c49ad7be`

## sv - Swedish

**Grammar**: Swedish hyphenation patterns from hyph-utf8 (`hyph-sv.tex`),
Copyright (C) 1994 Jan Michael Rynning, under LPPL 1.2 or later. As LPPL
requires of a modified redistribution, this converted form carries a
different name (`patterns.txt`) and differs from the original by the
conversion described above; the original is at the URL below.

- <https://raw.githubusercontent.com/hyphenation/tex-hyphen/master/hyph-utf8/tex/generic/hyph-utf8/patterns/tex/hyph-sv.tex>
- SHA-256 `102253382bd91cdb7535de5c20744254c9ac9aaa4ca12ed420eb835e5004c0b3`

**Words**: the SALDO morphology (`saldom.xml`) by Språkbanken Text,
University of Gothenburg, under CC-BY 4.0.

- <https://svn.spraakbanken.gu.se/sb-arkiv/pub/lexikon/saldom/saldom.xml>
- SHA-256 `dd24e06f5c63ec7b76414ed2dfe173f541017640b7d68f5c056eb8c94c2345e9`
