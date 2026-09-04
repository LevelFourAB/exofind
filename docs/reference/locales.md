# Locales

This reference lists the supported locales and their language analysis features.

A locale is identified by its BCP 47 tag. Locales are used in the following configurations:

- The `defaultLocale` and `supported` properties of the [locales an index declares](field-types.md#declared-locales).
- The `defaultLocale`, `locales` and `only` properties of a [locale-specific field](field-types.md#properties-on-every-type).
- The `locale` property of an analysis component.
- The chain of a [locale fallback](field-types.md#locale-fallback).
- A search query that reads locale-specific fields.

`en` is the default locale when no locale is specified.

## Supported languages

The following table lists the supported language tags and their analysis capabilities:

| Tag | Language | Stopwords | Stemming | Own segmentation | Compound splitting |
|-----|----------|-----------|----------|------------------|--------------------|
| `ar` | Arabic | yes | yes | | |
| `bg` | Bulgarian | yes | yes | | |
| `bn` | Bengali | yes | yes | | |
| `ca` | Catalan | yes | yes | | |
| `ckb` | Central Kurdish (Sorani) | yes | yes | | |
| `cs` | Czech | yes | yes | | |
| `da` | Danish | yes | yes | | yes |
| `de` | German | yes | yes | | yes |
| `el` | Greek | yes | yes | | |
| `en` | English | yes | yes | | |
| `es` | Spanish | yes | yes | | |
| `et` | Estonian | yes | yes | | |
| `eu` | Basque | yes | yes | | |
| `fa` | Persian | yes | yes | | |
| `fi` | Finnish | yes | yes | | yes |
| `fr` | French | yes | yes | | |
| `ga` | Irish | yes | yes | | |
| `gl` | Galician | yes | yes | | |
| `hi` | Hindi | yes | yes | | |
| `hu` | Hungarian | yes | yes | | |
| `hy` | Armenian | yes | yes | | |
| `id` | Indonesian | yes | yes | | |
| `is` | Icelandic | yes | yes | | yes |
| `it` | Italian | yes | yes | | |
| `ja` | Japanese | yes | yes | yes | |
| `ko` | Korean | | | yes | |
| `lt` | Lithuanian | yes | yes | | |
| `lv` | Latvian | yes | yes | | |
| `nb` | Norwegian Bokmål | yes | yes | | yes |
| `ne` | Nepali | yes | yes | | |
| `nl` | Dutch | yes | yes | | yes |
| `nn` | Norwegian Nynorsk | yes | yes | | yes |
| `no` | Norwegian | yes | yes | | yes |
| `pl` | Polish | yes | yes | | |
| `pt` | Portuguese | yes | yes | | |
| `ro` | Romanian | yes | yes | | |
| `ru` | Russian | yes | yes | | |
| `sr` | Serbian | yes | yes | | |
| `sv` | Swedish | yes | yes | | yes |
| `ta` | Tamil | yes | yes | | |
| `te` | Telugu | yes | yes | | |
| `th` | Thai | yes | | | |
| `tr` | Turkish | yes | yes | | |
| `uk` | Ukrainian | yes | yes | | |
| `zh` | Chinese | yes | yes | yes | |
| `zh-Hant` | Chinese (Traditional) | yes | yes | yes | |

### Analysis features

- **Stopwords**: The built-in `matching` analyzer chain applies stopwords for the locale. A [custom chain](analysis.md#custom-chains) applies them by specifying the locale on a `stopwords` component. Japanese and Korean drop grammatical parts of speech instead of using a separate stopword list.
- **Stemming**: The built-in `matching` analyzer chain applies stemming for the locale. A custom chain applies stemming by specifying the locale on a `stemming` component. Stemming behavior varies by language:
  - Japanese reduces elongated final vowels in loanwords.
  - Chinese stems mixed Latin words.
  - Korean and Thai do not have stemming rules because words do not inflect.
- **Own segmentation**: Indicates languages that use a dictionary-based word segmenter instead of Unicode segmentation because words are written without spaces. Thai words are segmented using standard Unicode segmentation.
- **Compound splitting**: Indicates locales that include decompounding data (for example, searching for `jakke` matches `regnjakke`). Decompounding requires decompounding data on the node. For more information, see [compound words](analysis.md#compound-words).
- **Locale data**: Icelandic reads its stopwords, stemming, and compound parts from the [locale data directory](configuration.md#locale-data) rather than from components built into the engine. A node without the data reports Icelandic as unsupported.
- **Normalization**: Applied automatically when a language requires rules beyond Unicode case folding. Normalization covers the following cases:
  - Turkish dotless `ı`.
  - Greek accents.
  - Elided articles in Catalan, French, Irish, and Italian.
  - Distinct Unicode forms of letters in Arabic, Indic, and Cyrillic scripts.
- **Script rewriting**: `zh-Hant` rewrites Traditional characters as their Simplified forms before the text is segmented, because the Chinese word model holds the Simplified forms only. A value indexed as `zh-Hant` produces the same terms as the same sentence written in Simplified and indexed as `zh`. Character positions are unchanged, so highlights point at the text as it was sent.

## Varieties of a language

`nb` (Norwegian Bokmål) and `nn` (Norwegian Nynorsk) both resolve to `no` (Norwegian) when a field does not specify the narrower tag. A field configured with `no` matches a search for either `nb` or `nn`.

Chinese is split by script rather than by written form. `zh-TW`, `zh-HK` and `zh-MO` resolve to `zh-Hant`, because those regions write Traditional without stating it in the tag. A field that holds only `zh` still answers a search for them, because there is no closer variant to read. To index both scripts separately, configure the field with both `zh` and `zh-Hant`.

Other language tags match by dropping subtags that the available locales do not distinguish. For example, a search request specifying `sv-SE` matches a field configured with `sv`.

Case carries no meaning in a tag. `zh-hant`, `ZH-HANT` and `zh-Hant` all name the same locale, and a definition stores whichever spelling you send as the canonical one, `zh-Hant`. Reading the definition back returns the canonical spelling.

For information about fields configured with multiple locales, see [Localize fields](../how-to/localize-fields.md).

## Comparative words

A search in user mode reads a number typed next to a comparative word of the search locale as a filter. For more information, see [reading numbers and units](search-api.md#reading-numbers-and-units).

Every locale reads a number written with a unit, but only the locales in the following table have comparative words:

| Locale | Below | At most | Above | At least | Range |
| --- | --- | --- | --- | --- | --- |
| `da` Danish | under, mindre end, billigere end | max, maks, højst, op til | over, mere end, dyrere end | min, mindst, fra | mellem … og, fra … til |
| `de` German | unter, weniger als, billiger als | max, maximal, höchstens, bis, bis zu | über, mehr als, teurer als | min, mindestens, ab | zwischen … und, von … bis |
| `en` English | under, below, less than, cheaper than | max, maximum, at most, up to | over, above, more than | min, minimum, at least, from | between … and, from … to, … to … |
| `es` Spanish | menos de, por debajo de | max, máximo, hasta, como máximo | más de, por encima de | min, mínimo, al menos, desde, a partir de | entre … y, de … a, desde … a |
| `fi` Finnish | alle, vähemmän kuin | max, enintään, korkeintaan | yli, enemmän kuin | min, vähintään | none |
| `fr` French | moins de, sous | max, maximum, au plus, jusqu'à | plus de, au-dessus de | min, minimum, au moins, à partir de | entre … et, de … à |
| `it` Italian | meno di, sotto | max, massimo, al massimo, fino a | più di, oltre, sopra | min, minimo, almeno, da | tra … e, fra … e, da … a |
| `nb`, `nn`, `no` Norwegian | under, mindre enn, billigere enn | max, maks, høyst, opp til | over, mer enn, dyrere enn | min, minst, fra | mellom … og, fra … til |
| `nl` Dutch | onder, minder dan, goedkoper dan | max, maximaal, hoogstens, tot | boven, meer dan, duurder dan | min, minimaal, minstens, vanaf | tussen … en, van … tot |
| `pt` Portuguese | menos de, abaixo de | max, máximo, no máximo, até | mais de, acima de | min, mínimo, pelo menos, desde, a partir de | entre … e, de … a, desde … a |
| `sv` Swedish | under, mindre än, billigare än | max, högst, upp till | över, mer än, dyrare än | min, minst, från | mellan … och, från … till |

## Sorting in a locale not listed here

Collation uses International Components for Unicode (ICU), which supports all locales. A `sort` definition with `"collation": "locale"` sorts values according to the rules of the locale assigned to the value.

The supported languages table lists locales supported for text analysis during indexing. A locale is included only when the engine provides specific analysis rules for that language. Icelandic is supported only on a node that has its locale data installed.

## Errors

The engine rejects unsupported locale tags. The following table lists the error codes returned when a tag is unsupported:

| Error | Condition |
| --- | --- |
| `index:field:locales:unsupported_locale` | A field definition specifies an unsupported locale tag in `locales` or `defaultLocale`. |
| `index:field:analyzer:unsupported_locale` | An analysis chain specifies an unsupported locale tag. |
| `index:locale_fallback:unsupported_locale` | A fallback chain specifies an unsupported locale tag. |
| `search:locale:unsupported` | A search query specifies an unsupported locale tag. |

The following table lists the error codes returned for index-level locale declarations:

| Error | Condition |
| --- | --- |
| `index:locales:default_locale_required` | An index definition specifies `locales` without `defaultLocale`. |
| `index:field:locales:not_declared` | A field definition specifies a locale in `only` or `defaultLocale` that the index does not declare. |
| `index:field:locales:default_not_in_only` | A field definition specifies an `only` list that does not contain the default locale of the field. |
| `index:field:locales:list_with_declaration` | A field definition specifies a `locales` array on an index that declares `locales`. |
| `index:field:locales:only_without_declaration` | A field definition specifies `only` on an index that does not declare `locales`. |

Each definition records its required locales in its features as `locale.<tag>`. A node built without a locale rejects an index that uses that locale instead of indexing the text as English.
