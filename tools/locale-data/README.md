# Regenerating the locale data

`locale-data/` at the repository root holds the language data that does not
live in the engine, one directory per data set. Every set has a hyphenation
grammar (`patterns.txt.gz`) and a word list of compound parts
(`words.txt.gz`) for splitting compounds; Icelandic adds a form-to-lemma
lookup (`stemming.txt.gz`) and a stopword list (`stopwords.txt.gz`), because
neither is something Lucene ships for the language.

The engine does not read the word list or the lemma lookup as text. It reads
`words.fst` and `stemming.fst`, transducers holding the same words that it
memory maps rather than builds, which for Icelandic is the difference between
1.5 MB of heap and a 450 MB peak at startup. `words.txt.gz` and
`stemming.txt.gz` stay in the repository as what those transducers are built
from - a readable intermediate between the upstream sources and a binary
format, and the thing to diff when a data refresh changes what a language
splits into.

This directory holds the converter that produces those files from their
upstream sources. `locale-data/ATTRIBUTION.md` records where each source
comes from, its version, checksum and license - update it whenever a source
is refreshed.

A data refresh only affects documents indexed after it, the same as any
other analysis change, so shipping new data does not invalidate an existing
index.

## Fetching the sources

Every source is a direct download:

```bash
mkdir -p sources && cd sources

# Hyphenation grammars
base=https://raw.githubusercontent.com/hyphenation/tex-hyphen/master/hyph-utf8/tex/generic/hyph-utf8/patterns/tex
curl -LO $base/hyph-da.tex -LO $base/hyph-nl.tex -LO $base/hyph-sv.tex -LO $base/hyph-no.tex -LO $base/hyph-is.tex -LO $base/hyph-fi.tex
curl -LO https://mirrors.ctan.org/language/hyphenation/dehyph-exptl.zip
unzip dehyph-exptl.zip

# Word lists
curl -LO https://ordregister.dk/files/cor1.5.1.0.tsv
curl -LO https://kaino.kotus.fi/lataa/nykysuomensanalista2024.txt
curl -LO https://raw.githubusercontent.com/OpenTaal/opentaal-wordlist/master/wordlist.txt
curl -LO https://www.nb.no/sbfil/leksikalske_databaser/ordbank/20190123_norsk_ordbank_nob_2005.tar.gz
curl -LO https://www.nb.no/sbfil/leksikalske_databaser/ordbank/20190123_norsk_ordbank_nno_2012.tar.gz
tar xzf 20190123_norsk_ordbank_nob_2005.tar.gz
tar xzf 20190123_norsk_ordbank_nno_2012.tar.gz
curl -LO https://svn.spraakbanken.gu.se/sb-arkiv/pub/lexikon/saldom/saldom.xml
curl -LO https://dumps.wikimedia.org/wikidatawiki/entities/latest-lexemes.json.gz

# BÍN, which the download page asks you to accept the terms of first:
# https://bin.arnastofnun.is/gogn/mimisbrunnur/
curl -L -o SHsnid.csv.zip "https://bin.arnastofnun.is/django/api/nidurhal/?file=SHsnid.csv.zip"
unzip SHsnid.csv.zip
```

## Converting

`Convert.java` runs as a single-file program, one subcommand per source
format - see its header for the exact forms. The full regeneration:

```bash
out=../../locale-data

# Grammars. Danish merges the Norwegian patterns because its own set is too
# sparse to find compound boundaries; nb and nn share the Norwegian set.
java Convert.java tex $out/da/patterns.txt sources/hyph-da.tex sources/hyph-no.tex
java Convert.java tex $out/de/patterns.txt sources/dehyph-exptl/dehyphn-x-2024-02-28.pat
java Convert.java tex $out/fi/patterns.txt sources/hyph-fi.tex
java Convert.java tex $out/is/patterns.txt sources/hyph-is.tex
java Convert.java tex $out/nl/patterns.txt sources/hyph-nl.tex
java Convert.java tex $out/sv/patterns.txt sources/hyph-sv.tex
java Convert.java tex $out/nb/patterns.txt sources/hyph-no.tex
java Convert.java tex $out/nn/patterns.txt sources/hyph-no.tex

# Word lists
java Convert.java cor $out/da/words.txt sources/cor1.5.1.0.tsv
java Convert.java wikidata $out/de/words.txt sources/latest-lexemes.json.gz Q188
java Convert.java kotus $out/fi/words.txt sources/nykysuomensanalista2024.txt
java Convert.java wordlist $out/nl/words.txt sources/wordlist.txt
java Convert.java saldo $out/sv/words.txt sources/saldom.xml
java Convert.java ordbank $out/nb/words.txt \
    sources/20190123_Norsk_ordbank_nob_2005/lemma.txt \
    sources/20190123_Norsk_ordbank_nob_2005/fullformsliste.txt
java Convert.java ordbank $out/nn/words.txt \
    sources/20190123_Norsk_ordbank_nno_2012/lemma_2012.txt \
    sources/20190123_Norsk_ordbank_nno_2012/fullformer_2012.txt

# Icelandic, all three files from the one source
java Convert.java bin-words $out/is/words.txt sources/SHsnid.csv
java Convert.java bin-stemming $out/is/stemming.txt sources/SHsnid.csv
java Convert.java bin-stopwords $out/is/stopwords.txt sources/SHsnid.csv

gzip -9 $out/*/patterns.txt $out/*/words.txt $out/is/stemming.txt $out/is/stopwords.txt
```

## Building the transducers

The `fst-` subcommands turn the lists above into what the engine reads. They
are the only ones needing a classpath: Lucene writes the format and ICU folds
the words the way the engine folds the tokens looked up in them. Both come
from the engine's own dependencies, so name them from the build:

```bash
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
cp=$(cat target/classpath.txt)

for l in da de fi is nb nl nn sv; do
    java -Xmx8g -cp "$cp" tools/locale-data/Convert.java \
        fst-words $out/$l/words.fst $out/$l/words.txt.gz
done

java -Xmx8g -cp "$cp" tools/locale-data/Convert.java \
    fst-stemming $out/is/stemming.fst $out/is/stemming.txt.gz
```

The inputs are read gzipped or plain, so this runs either straight after the
text is written or later against the shipped data. Icelandic holds every form
in memory while sorting, which is what the large heap is for.

The hand-made set under `src/test/resources/locale-data/test/` is built the
same way and has to be rebuilt whenever its word list is edited; the header
of that file carries the command.

A transducer is tied to the version of Lucene that wrote it, so a Lucene
upgrade means rebuilding every one of them. `DecompounderTest` and
`LemmatizerTest` open each shipped transducer, which is what turns a missed
rebuild into a build failure rather than a locale that stops working once
deployed.

`DecompounderTest` pins that every shipped data set splits its language's
words and `LemmatizerTest` pins what the Icelandic lookup answers, so a broken
regeneration fails the build rather than quietly indexing compounds whole or
leaving words unreduced.
