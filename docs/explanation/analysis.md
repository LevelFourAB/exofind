# Text analysis

This document explains why text analysis in Exofind is structured around a single indexing chain, how the indexing and querying sides interact, and what changing an analyzer chain costs the index.

## Why analysis exists

A search matches terms, not text. The engine converts the text of a value into terms when it indexes the value, and converts the text of a search into terms when it runs the search. A document answers a search only when the two sides produce the same term.

## One chain, two sides

An analyzer chain describes the indexing side. The engine derives the querying side from it. You do not configure the two separately.

Deriving the querying chain prevents a failure that nothing reports. Two chains configured separately can drift apart. A field that stems on one side and not on the other matches nothing, returns no error, and reports nothing.

## Where the two sides differ

The derived query chain differs from the indexing chain in exactly the places where a component widens the value. Widening both sides matches far more than the search asked for, or counts the same widening twice.

Both sides run the same kinds of component in the same order:

```d2 title="The indexing chain beside the query chain derived from it, with the widening filters marked as the place the two differ"
grid-columns: 2

indexing: The indexing chain {
  direction: down
  chars: "Character\nfilters"
  tokenizer: Tokenizer
  narrow: "Token filters that\nkeep or replace\na token"
  widen: "Token filters\nthat add tokens"
  terms: "Terms written\nwith the document"
  chars -> tokenizer -> narrow -> widen -> terms
}

querying: The derived query chain {
  direction: down
  chars: "The same\ncharacter filters"
  tokenizer: "The same\ntokenizer"
  narrow: "The same keeping\nand replacing\nfilters"
  widen: "synonyms and\ndecompound left out,\nedgeNgram narrowed"
  terms: "Terms the\nsearch looks up"
  chars -> tokenizer -> narrow -> widen -> terms
}
```

Three components make up the widening step, and each one differs on the query side:

- `edgeNgram`, which autocomplete uses: indexing writes every prefix of a value as a term, so that what a person has typed so far is itself a term to look up. At query time, the typed text is cut to the longest prefix that was indexed instead of being cut into prefixes again.
- `synonyms` inside a chain: indexing widens the value with the terms of the rules. The component drops away on the query side, because widening the query with the same set counts the same synonym twice.
- `decompound`: indexing widens a value with the parts of its compound words and keeps the compound itself. It drops away on the query side for the same reason. A search for a part matches the document holding the compound, and a search for the whole compound matches only documents that hold it.

## Widening the query instead

A synonym set meant to widen the query is not part of a chain at all. The search settings of the index carry it, and the engine adds it after the analyzers.

The two sides cost different things:

- A set inside the chain reaches only documents indexed after it.
- A set in the search settings reaches every document already indexed and needs no reindex.
- Putting the same rule on both sides counts it twice.

## Why analysis is the setting that needs a reindex

The terms are written to disk when the document is indexed. Ranking is evaluated when the search runs, so a changed weight reaches the next search. A changed chain reaches only documents indexed after the change, so changing the analysis of a live index means reindexing into a new generation.

## Why the default chain stems and decompounds

When a definition gives no analyzer, the engine builds the chain from the usage and the locale of the value. The chain it builds for `matching` normalizes, splits compounds, drops stopwords, and stems, all by the locale of the value. That serves prose, where a search for one word should find its inflected forms.

The cost is that the chain cannot tell a code from a word. A field that holds SKUs, model numbers, or names wants the `preserve_terms` preset, which tokenizes and normalizes but keeps each word whole. A field that has to match exact characters wants a custom chain holding `normalize` alone.

Decompounding is on by default only for `da`, `de`, `fi`, `is`, `nl`, `no`, `nb`, `nn`, and `sv`. Setting `"decompound": "none"` on a usage turns it off. A custom chain never splits compounds unless it names the `decompound` filter.

## Why the locale decides the components

A component that takes a locale and is given none uses the locale of the value being analyzed. One chain therefore serves a field whose values arrive in several locales.

Stopwords, stemming, tokenization, and locale-specific normalization all come from the locale this way. This design has two consequences:

- A node that lacks the locale data an index definition names rejects the definition when it validates it. It does not index the values under the wrong analysis.
- Some locales are handled differently because the general approach does not work for them. Icelandic stems by looking each word up in a full form list, because its inflection is too irregular for a rule-based stemmer. Traditional Chinese rewrites characters into their Simplified forms before segmentation, because the Chinese word model holds the Simplified forms only, and the rewriting runs after any character filters in the chain. Japanese and Korean segment compounds in the tokenizer instead of through a decompounding dictionary.

## What a chain costs the index

A component that widens a value writes more terms for it. Decompounding writes the parts beside the compound, and an edge n-gram writes every prefix of every token.

## What a chain decides beyond matching

Combining the words of a search across several fields works only where the fields tokenize text into the same words. The engine groups fields that share a tokenization and ranks a document by its best-matching group. Two fields analyzed differently therefore cannot count one word between them.

## Related

- [Analysis](../reference/analysis.md) - Reference for components, presets, and their options.
- [Customizing text analysis](../how-to/customize-analysis.md) - How-to guide for configuring a chain on a field.
- [Locales](../reference/locales.md) - Reference for supported locale tags.
- [Changing synonyms without reindexing](../how-to/change-synonyms-without-reindexing.md) - How-to guide for moving a synonym set to the query side.
- [Reindexing into a new generation](../how-to/reindex-into-a-new-generation.md) - How-to guide for changing the analysis of a live index.
- [Relevance](relevance.md) - Explanation of how the terms a chain produces are scored.
- [Excluding words from typo tolerance](../how-to/exclude-words-from-typo-tolerance.md) - How-to guide for holding one word to its spelling.
