# Regenerating the decompounding data

The engine splits compound words using per-language data in
`decompound-data/` at the repository root: a hyphenation grammar
(`patterns.txt.gz`) and a word list of compound parts (`words.txt.gz`) per
data set. This directory holds the converter that produces those files from
their upstream sources. `decompound-data/ATTRIBUTION.md` records where each
source comes from, its version, checksum and license - update it whenever a
source is refreshed.

A data refresh only affects documents indexed after it, the same as any
other analysis change, so shipping new data does not invalidate an existing
index.

## Fetching the sources

Every source is a direct download:

```bash
mkdir -p sources && cd sources

# Hyphenation grammars
base=https://raw.githubusercontent.com/hyphenation/tex-hyphen/master/hyph-utf8/tex/generic/hyph-utf8/patterns/tex
curl -LO $base/hyph-da.tex -LO $base/hyph-nl.tex -LO $base/hyph-sv.tex -LO $base/hyph-no.tex
curl -LO https://mirrors.ctan.org/language/hyphenation/dehyph-exptl.zip
unzip dehyph-exptl.zip

# Word lists
curl -LO https://ordregister.dk/files/cor1.5.1.0.tsv
curl -LO https://raw.githubusercontent.com/OpenTaal/opentaal-wordlist/master/wordlist.txt
curl -LO https://www.nb.no/sbfil/leksikalske_databaser/ordbank/20190123_norsk_ordbank_nob_2005.tar.gz
curl -LO https://www.nb.no/sbfil/leksikalske_databaser/ordbank/20190123_norsk_ordbank_nno_2012.tar.gz
tar xzf 20190123_norsk_ordbank_nob_2005.tar.gz
tar xzf 20190123_norsk_ordbank_nno_2012.tar.gz
curl -LO https://svn.spraakbanken.gu.se/sb-arkiv/pub/lexikon/saldom/saldom.xml
curl -LO https://dumps.wikimedia.org/wikidatawiki/entities/latest-lexemes.json.gz
```

## Converting

`Convert.java` runs as a single-file program, one subcommand per source
format - see its header for the exact forms. The full regeneration:

```bash
out=../../decompound-data

# Grammars. Danish merges the Norwegian patterns because its own set is too
# sparse to find compound boundaries; nb and nn share the Norwegian set.
java Convert.java tex $out/da/patterns.txt sources/hyph-da.tex sources/hyph-no.tex
java Convert.java tex $out/de/patterns.txt sources/dehyph-exptl/dehyphn-x-2024-02-28.pat
java Convert.java tex $out/nl/patterns.txt sources/hyph-nl.tex
java Convert.java tex $out/sv/patterns.txt sources/hyph-sv.tex
java Convert.java tex $out/nb/patterns.txt sources/hyph-no.tex
java Convert.java tex $out/nn/patterns.txt sources/hyph-no.tex

# Word lists
java Convert.java cor $out/da/words.txt sources/cor1.5.1.0.tsv
java Convert.java wikidata $out/de/words.txt sources/latest-lexemes.json.gz Q188
java Convert.java wordlist $out/nl/words.txt sources/wordlist.txt
java Convert.java saldo $out/sv/words.txt sources/saldom.xml
java Convert.java ordbank $out/nb/words.txt \
    sources/20190123_Norsk_ordbank_nob_2005/lemma.txt \
    sources/20190123_Norsk_ordbank_nob_2005/fullformsliste.txt
java Convert.java ordbank $out/nn/words.txt \
    sources/20190123_Norsk_ordbank_nno_2012/lemma_2012.txt \
    sources/20190123_Norsk_ordbank_nno_2012/fullformer_2012.txt

gzip -9 $out/*/patterns.txt $out/*/words.txt
```

`DecompounderTest` pins that every shipped data set splits its language's
words, so a broken regeneration fails the build rather than quietly
indexing compounds whole.
