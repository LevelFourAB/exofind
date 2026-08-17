# Locales

The languages this build of the engine has rules for. A locale is named by
its BCP 47 tag wherever a definition says what language a value is in - the
`defaultLocale` and `locales` of a [locale specific
field](field-types.md#properties-on-every-type), the `locale` of an analysis
component, the chain of a [locale fallback](field-types.md#locale-fallback) -
and by a search that reads locale specific fields in one.

A tag that is not listed here is refused rather than ignored:
`index:field:locales:unsupported_locale` for a field,
`index:field:analyzer:unsupported_locale` for an analysis chain,
`index:locale_fallback:unsupported_locale` for a fallback chain and
`search:locale:unsupported` for a search. What each definition needs is
recorded in its features as `locale.<tag>`, so a node built without a locale
refuses an index that uses it instead of indexing the text as if it were
English.

## Supported languages

| Tag | Language | Stopwords | Stemming | Own segmentation | Compound splitting |
|-----|----------|-----------|----------|------------------|--------------------|
| `ar` | Arabic | yes | yes | | |
| `bg` | Bulgarian | yes | yes | | |
| `bn` | Bengali | yes | yes | | |
| `ca` | Catalan | yes | yes | | |
| `cs` | Czech | yes | yes | | |
| `da` | Danish | yes | yes | | yes |
| `de` | German | yes | yes | | yes |
| `el` | Greek | yes | yes | | |
| `en` | English | yes | yes | | |
| `es` | Spanish | yes | yes | | |
| `et` | Estonian | yes | yes | | |
| `eu` | Basque | yes | yes | | |
| `fa` | Persian | yes | yes | | |
| `fi` | Finnish | yes | yes | | |
| `fr` | French | yes | yes | | |
| `ga` | Irish | yes | yes | | |
| `gl` | Galician | yes | yes | | |
| `hi` | Hindi | yes | yes | | |
| `hu` | Hungarian | yes | yes | | |
| `hy` | Armenian | yes | yes | | |
| `id` | Indonesian | yes | yes | | |
| `it` | Italian | yes | yes | | |
| `ja` | Japanese | yes | yes | yes | |
| `ko` | Korean | | | yes | |
| `lt` | Lithuanian | yes | yes | | |
| `lv` | Latvian | yes | yes | | |
| `nb` | Norwegian Bokmål | yes | yes | | yes |
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

`en` is the locale assumed wherever nothing says otherwise.

**Stopwords** and **stemming** are what the engine-built `matching` chain
applies for the locale; a [custom chain](analysis.md#custom-chains) takes
them by naming the locale on a `stopwords` or `stemming` component instead.
Japanese and Korean drop the parts of speech that are grammar rather than
meaning, which is what a stopword list does elsewhere and is why Korean needs
no list of its own.

What stemming means follows the language: Japanese reduces the long final
vowel loanwords write both ways, Chinese stems the Latin words mixed into the
text, and Korean and Thai have none because their words do not inflect.

**Own segmentation** marks the languages whose words come from a
dictionary rather than from Unicode, because the writing puts no spaces
between them. Thai writes no spaces either and needs no entry here - the
Unicode segmentation the engine already uses finds its words.

**Compound splitting** marks the locales the engine ships decompounding data
for, so `jakke` finds `regnjakke`. It needs the data to be present on the
node - see [compound words](analysis.md#compound-words).

Normalization comes with the locale wherever the language needs more than
Unicode case folding for two spellings of a word to meet: the Turkish
dotless ı, Greek accents, the articles elided onto the front of a word in
Catalan, French, Irish and Italian, and the several Unicode forms of a
letter in the Arabic, Indic and Cyrillic scripts.

## Varieties of a language

`nb` and `nn` are how Norwegian is written, and both resolve to `no` when
nothing holds the narrower tag: a field holding `no` answers a search for
either. No other language here is split this way.

A tag is otherwise matched by dropping the subtags the available locales do
not tell apart, so a browser sending `sv-SE` reads a field holding `sv`.
What that means for a field holding several locales is in [Localize
fields](../how-to/localize-fields.md).

## Sorting in a locale not listed here

Collation comes from ICU, which knows every locale, so a `sort` with
`"collation": "locale"` orders by the rules of whatever locale the value
carries. The list above is about analysis - what a language does to text on
its way into the index - and a language is only listed when there are real
rules to apply, rather than claiming support for a fallback to nothing.
