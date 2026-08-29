package se.l4.exofind.engine.index.locales;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.ar.ArabicNormalizationFilter;
import org.apache.lucene.analysis.ar.ArabicStemFilter;
import org.apache.lucene.analysis.bg.BulgarianAnalyzer;
import org.apache.lucene.analysis.bg.BulgarianStemFilter;
import org.apache.lucene.analysis.bn.BengaliAnalyzer;
import org.apache.lucene.analysis.bn.BengaliNormalizationFilter;
import org.apache.lucene.analysis.bn.BengaliStemFilter;
import org.apache.lucene.analysis.ca.CatalanAnalyzer;
import org.apache.lucene.analysis.ckb.SoraniAnalyzer;
import org.apache.lucene.analysis.ckb.SoraniNormalizationFilter;
import org.apache.lucene.analysis.ckb.SoraniStemFilter;
import org.apache.lucene.analysis.cn.smart.HMMChineseTokenizer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.cz.CzechStemFilter;
import org.apache.lucene.analysis.da.DanishAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.de.GermanLightStemFilter;
import org.apache.lucene.analysis.de.GermanNormalizationFilter;
import org.apache.lucene.analysis.el.GreekAnalyzer;
import org.apache.lucene.analysis.el.GreekLowerCaseFilter;
import org.apache.lucene.analysis.el.GreekStemFilter;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.en.EnglishPossessiveFilter;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.es.SpanishAnalyzer;
import org.apache.lucene.analysis.es.SpanishLightStemFilter;
import org.apache.lucene.analysis.et.EstonianAnalyzer;
import org.apache.lucene.analysis.eu.BasqueAnalyzer;
import org.apache.lucene.analysis.fa.PersianAnalyzer;
import org.apache.lucene.analysis.fa.PersianNormalizationFilter;
import org.apache.lucene.analysis.fa.PersianStemFilter;
import org.apache.lucene.analysis.fi.FinnishAnalyzer;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.fr.FrenchLightStemFilter;
import org.apache.lucene.analysis.ga.IrishAnalyzer;
import org.apache.lucene.analysis.ga.IrishLowerCaseFilter;
import org.apache.lucene.analysis.gl.GalicianAnalyzer;
import org.apache.lucene.analysis.gl.GalicianStemFilter;
import org.apache.lucene.analysis.hi.HindiAnalyzer;
import org.apache.lucene.analysis.hi.HindiNormalizationFilter;
import org.apache.lucene.analysis.hi.HindiStemFilter;
import org.apache.lucene.analysis.hu.HungarianAnalyzer;
import org.apache.lucene.analysis.hy.ArmenianAnalyzer;
import org.apache.lucene.analysis.id.IndonesianAnalyzer;
import org.apache.lucene.analysis.id.IndonesianStemFilter;
import org.apache.lucene.analysis.in.IndicNormalizationFilter;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.it.ItalianLightStemFilter;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseBaseFormFilter;
import org.apache.lucene.analysis.ja.JapaneseKatakanaStemFilter;
import org.apache.lucene.analysis.ja.JapanesePartOfSpeechStopFilter;
import org.apache.lucene.analysis.ja.JapaneseTokenizer;
import org.apache.lucene.analysis.ko.KoreanPartOfSpeechStopFilter;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.lt.LithuanianAnalyzer;
import org.apache.lucene.analysis.lv.LatvianAnalyzer;
import org.apache.lucene.analysis.lv.LatvianStemFilter;
import org.apache.lucene.analysis.morfologik.MorfologikFilter;
import org.apache.lucene.analysis.ne.NepaliAnalyzer;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.no.NorwegianAnalyzer;
import org.apache.lucene.analysis.no.NorwegianLightStemFilter;
import org.apache.lucene.analysis.no.NorwegianLightStemFilterFactory;
import org.apache.lucene.analysis.pl.PolishAnalyzer;
import org.apache.lucene.analysis.pt.PortugueseAnalyzer;
import org.apache.lucene.analysis.pt.PortugueseLightStemFilter;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.ro.RomanianNormalizationFilter;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.sr.SerbianAnalyzer;
import org.apache.lucene.analysis.sr.SerbianNormalizationFilter;
import org.apache.lucene.analysis.stempel.StempelFilter;
import org.apache.lucene.analysis.stempel.StempelStemmer;
import org.apache.lucene.analysis.sv.SwedishAnalyzer;
import org.apache.lucene.analysis.ta.TamilAnalyzer;
import org.apache.lucene.analysis.te.TeluguAnalyzer;
import org.apache.lucene.analysis.te.TeluguNormalizationFilter;
import org.apache.lucene.analysis.te.TeluguStemFilter;
import org.apache.lucene.analysis.th.ThaiAnalyzer;
import org.apache.lucene.analysis.tr.ApostropheFilter;
import org.apache.lucene.analysis.tr.TurkishAnalyzer;
import org.apache.lucene.analysis.tr.TurkishLowerCaseFilter;
import org.apache.lucene.analysis.uk.UkrainianMorfologikAnalyzer;
import org.apache.lucene.analysis.util.ElisionFilter;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.SetIterable;
import org.tartarus.snowball.ext.ArmenianStemmer;
import org.tartarus.snowball.ext.BasqueStemmer;
import org.tartarus.snowball.ext.CatalanStemmer;
import org.tartarus.snowball.ext.DanishStemmer;
import org.tartarus.snowball.ext.DutchStemmer;
import org.tartarus.snowball.ext.EnglishStemmer;
import org.tartarus.snowball.ext.EstonianStemmer;
import org.tartarus.snowball.ext.FinnishStemmer;
import org.tartarus.snowball.ext.HungarianStemmer;
import org.tartarus.snowball.ext.IrishStemmer;
import org.tartarus.snowball.ext.LithuanianStemmer;
import org.tartarus.snowball.ext.NepaliStemmer;
import org.tartarus.snowball.ext.RomanianStemmer;
import org.tartarus.snowball.ext.RussianStemmer;
import org.tartarus.snowball.ext.SerbianStemmer;
import org.tartarus.snowball.ext.SwedishStemmer;
import org.tartarus.snowball.ext.TamilStemmer;
import org.tartarus.snowball.ext.TurkishStemmer;

import morfologik.stemming.Dictionary;

/**
 * The locales this build of the engine has support for.
 *
 * A definition can name a locale - as the default of a locale specific field,
 * in the locales a field holds values in, or in a component of an analysis
 * chain - and naming one this build does not have is refused when the
 * definition is validated, so it never gets as far as indexing without the
 * locale's rules.
 *
 * Each entry hands {@link StandardLocaleSupport} the pieces Lucene ships for
 * the language: its stopword list, its stemmer, its own way of splitting text
 * into words when Unicode segmentation cannot find them, and whatever it
 * needs on top of Unicode case folding for two spellings of a word to meet.
 * Collation always comes from ICU, which knows every locale here. A language
 * that glues compounds into one word also names its {@link Decompounder}
 * data, shipped with the engine rather than by Lucene.
 *
 * A language is only listed when there are real rules for it - segmentation,
 * a stopword list or a stemmer. Any locale can still be sorted by its own
 * collation through ICU, but claiming `locale.xx` support for analysis that
 * would fall back to nothing would promise more than it delivers. That is why
 * a locale whose analysis comes from {@link LocaleData} rather than from the
 * jar is listed only where the data is installed: the node that cannot analyze
 * the language says so, and a definition naming the locale is refused there
 * rather than indexed unanalyzed. Which locales those are is decided once,
 * when this class is first used, so data installed after a node has started
 * is not picked up until it restarts.
 */
public final class Locales {
	private static final ImmutableMap<String, LocaleSupport> SUPPORTED = build();

	/**
	 * The locale assumed when nothing says otherwise.
	 */
	private static final LocaleSupport DEFAULT = SUPPORTED.get("en");

	private Locales() {
	}

	private static ImmutableMap<String, LocaleSupport> build() {
		var locales = Maps.mutable.<String, LocaleSupport>empty();

		/*
		 * The stopword list is written as the words appear in text, so it
		 * runs before the normalization stemming needs - which is why that
		 * normalization sits with the stemmer here, mirroring Lucene's
		 * ArabicAnalyzer.
		 */
		register(locales, StandardLocaleSupport.of("ar")
			.withStopWords(ArabicAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new ArabicStemFilter(
				new ArabicNormalizationFilter(stream)
			)));

		register(locales, StandardLocaleSupport.of("bg")
			.withStopWords(BulgarianAnalyzer.getDefaultStopSet())
			.withStemmer(BulgarianStemFilter::new));

		/*
		 * The Indic scripts write the same letter in more than one Unicode
		 * form, which the Indic normalization makes one before stopwords and
		 * stemming see it - the same for Hindi, Tamil and Telugu below.
		 */
		register(locales, StandardLocaleSupport.of("bn")
			.withStopWords(BengaliAnalyzer.getDefaultStopSet())
			.withNormalizer(stream -> new BengaliNormalizationFilter(
				new IndicNormalizationFilter(stream)
			))
			.withStemmer(BengaliStemFilter::new));

		/*
		 * Catalan glues elided articles onto the front of a word - l'home -
		 * the way French does, so they are taken off before stopwords and
		 * stemming see the word.
		 */
		register(locales, StandardLocaleSupport.of("ca")
			.withStopWords(CatalanAnalyzer.getDefaultStopSet())
			.withNormalizer(stream -> new ElisionFilter(stream, CATALAN_ARTICLES))
			.withStemmer(stream -> new SnowballFilter(stream, new CatalanStemmer())));

		/*
		 * Sorani writes Kurdish in the Arabic script, where several of its
		 * letters have a second Unicode form and the ezafe is marked with a
		 * vowel sign that text writes as often as it leaves out. Regularizing
		 * both makes the spellings of a word meet. The stopword list is
		 * written in the forms that come out of it.
		 */
		register(locales, StandardLocaleSupport.of("ckb")
			.withStopWords(SoraniAnalyzer.getDefaultStopSet())
			.withNormalizer(SoraniNormalizationFilter::new)
			.withStemmer(SoraniStemFilter::new));

		register(locales, StandardLocaleSupport.of("cs")
			.withStopWords(CzechAnalyzer.getDefaultStopSet())
			.withStemmer(CzechStemFilter::new));

		register(locales, StandardLocaleSupport.of("da")
			.withStopWords(DanishAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(stream, new DanishStemmer()))
			.withDecompounder(Decompounder.forData("da")));

		/*
		 * The light stemmer expects umlauts and ß folded onto their base
		 * letters first, which also makes `Häuser` and `Haeuser` meet.
		 */
		register(locales, StandardLocaleSupport.of("de")
			.withStopWords(GermanAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new GermanLightStemFilter(
				new GermanNormalizationFilter(stream)
			))
			.withDecompounder(Decompounder.forData("de")));

		/*
		 * Greek folds case its own way - final sigma, and the accents modern
		 * Greek writes but its stopword list and stemmer are written without.
		 */
		register(locales, StandardLocaleSupport.of("el")
			.withStopWords(GreekAnalyzer.getDefaultStopSet())
			.withNormalizer(GreekLowerCaseFilter::new)
			.withStemmer(GreekStemFilter::new));

		/*
		 * The possessive is grammar rather than a form the stemmer knows
		 * about, so it is taken off first - `Carson's` stems through `Carson`.
		 */
		register(locales, StandardLocaleSupport.of("en")
			.withStopWords(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET)
			.withStemmer(stream -> new SnowballFilter(
				new EnglishPossessiveFilter(stream), new EnglishStemmer()
			)));

		register(locales, StandardLocaleSupport.of("es")
			.withStopWords(SpanishAnalyzer.getDefaultStopSet())
			.withStemmer(SpanishLightStemFilter::new));

		register(locales, StandardLocaleSupport.of("et")
			.withStopWords(EstonianAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(stream, new EstonianStemmer())));

		register(locales, StandardLocaleSupport.of("eu")
			.withStopWords(BasqueAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(stream, new BasqueStemmer())));

		/*
		 * Persian shares letters with Arabic that its keyboards type either
		 * way, so both normalizations run before the stopword list, which is
		 * written in the normalized forms.
		 */
		register(locales, StandardLocaleSupport.of("fa")
			.withStopWords(PersianAnalyzer.getDefaultStopSet())
			.withNormalizer(stream -> new PersianNormalizationFilter(
				new ArabicNormalizationFilter(stream)
			))
			.withStemmer(PersianStemFilter::new));

		register(locales, StandardLocaleSupport.of("fi")
			.withStopWords(FinnishAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(stream, new FinnishStemmer()))
			.withDecompounder(Decompounder.forData("fi")));

		register(locales, StandardLocaleSupport.of("fr")
			.withStopWords(FrenchAnalyzer.getDefaultStopSet())
			.withNormalizer(stream -> new ElisionFilter(stream, FrenchAnalyzer.DEFAULT_ARTICLES))
			.withStemmer(FrenchLightStemFilter::new));

		/*
		 * Irish lowercases its own way because initial mutations - the n- of
		 * nAthair - belong to grammar, not to the word being looked up.
		 */
		register(locales, StandardLocaleSupport.of("ga")
			.withStopWords(IrishAnalyzer.getDefaultStopSet())
			.withNormalizer(stream -> new IrishLowerCaseFilter(
				new ElisionFilter(stream, IRISH_ARTICLES)
			))
			.withStemmer(stream -> new SnowballFilter(stream, new IrishStemmer())));

		register(locales, StandardLocaleSupport.of("gl")
			.withStopWords(GalicianAnalyzer.getDefaultStopSet())
			.withStemmer(GalicianStemFilter::new));

		register(locales, StandardLocaleSupport.of("hi")
			.withStopWords(HindiAnalyzer.getDefaultStopSet())
			.withNormalizer(stream -> new HindiNormalizationFilter(
				new IndicNormalizationFilter(stream)
			))
			.withStemmer(HindiStemFilter::new));

		register(locales, StandardLocaleSupport.of("hu")
			.withStopWords(HungarianAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(stream, new HungarianStemmer())));

		register(locales, StandardLocaleSupport.of("hy")
			.withStopWords(ArmenianAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(stream, new ArmenianStemmer())));

		register(locales, StandardLocaleSupport.of("id")
			.withStopWords(IndonesianAnalyzer.getDefaultStopSet())
			.withStemmer(IndonesianStemFilter::new));

		/*
		 * Icelandic inflects further than a rule stemmer reaches - a noun has
		 * eight forms before the definite article is suffixed onto any of
		 * them, and the stem vowel changes as often as the ending does - so
		 * words are looked up in a full form list instead. That list, the
		 * stopwords and the compound parts are all locale data rather than
		 * something Lucene ships, which is why the locale is registered only
		 * where the data is there to read.
		 */
		var icelandic = Lemmatizer.forData("is");
		if(icelandic.isAvailable()) {
			register(locales, StandardLocaleSupport.of("is")
				.withStopWords(() -> stopWords("is"))
				.withStemmer(icelandic::stem)
				.withDecompounder(Decompounder.forData("is")));
		}

		register(locales, StandardLocaleSupport.of("it")
			.withStopWords(ItalianAnalyzer.getDefaultStopSet())
			.withNormalizer(stream -> new ElisionFilter(stream, ITALIAN_ARTICLES))
			.withStemmer(ItalianLightStemFilter::new));

		/*
		 * Japanese writes no spaces, so its words come from a morphological
		 * dictionary rather than from Unicode. The tokenizer knows the base
		 * form of what it conjugates, and restoring it before stopwords is
		 * what lets a conjugated する still meet the list; the parts of
		 * speech that are grammar rather than meaning are dropped the same
		 * way stopwords are. What remains of stemming is katakana - a long
		 * final vowel that loanwords write both ways.
		 */
		register(locales, StandardLocaleSupport.of("ja")
			.withStopWords(JapaneseAnalyzer.getDefaultStopSet())
			.withTokenizer(() -> new JapaneseTokenizer(
				null, true, JapaneseTokenizer.DEFAULT_MODE
			))
			.withNormalizer(stream -> new JapanesePartOfSpeechStopFilter(
				new JapaneseBaseFormFilter(stream),
				JapaneseAnalyzer.getDefaultStopTags()
			))
			.withStemmer(JapaneseKatakanaStemFilter::new));

		/*
		 * Korean writes spaces between phrases rather than words, and glues
		 * particles onto what they follow. The tokenizer takes both apart;
		 * dropping the particle parts of speech is what a stopword list is
		 * for languages that write their grammar as words.
		 *
		 * A compound noun is kept whole beside the parts it is built from, the
		 * way a decompounded language keeps its whole word - Lucene otherwise
		 * emits only the parts, and a search for the compound as written would
		 * miss the documents holding it.
		 */
		register(locales, StandardLocaleSupport.of("ko")
			.withTokenizer(() -> new KoreanTokenizer(
				KoreanTokenizer.DEFAULT_TOKEN_ATTRIBUTE_FACTORY,
				null,
				KoreanTokenizer.DecompoundMode.MIXED,
				false
			))
			.withNormalizer(KoreanPartOfSpeechStopFilter::new));

		register(locales, StandardLocaleSupport.of("lt")
			.withStopWords(LithuanianAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(stream, new LithuanianStemmer())));

		register(locales, StandardLocaleSupport.of("lv")
			.withStopWords(LatvianAnalyzer.getDefaultStopSet())
			.withStemmer(LatvianStemFilter::new));

		/*
		 * Nepali writes Devanagari, so the same Indic normalization Hindi
		 * needs runs before its stopword list and stemmer see a word.
		 */
		register(locales, StandardLocaleSupport.of("ne")
			.withStopWords(NepaliAnalyzer.getDefaultStopSet())
			.withNormalizer(IndicNormalizationFilter::new)
			.withStemmer(stream -> new SnowballFilter(stream, new NepaliStemmer())));

		register(locales, StandardLocaleSupport.of("nl")
			.withStopWords(DutchAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(stream, new DutchStemmer()))
			.withDecompounder(Decompounder.forData("nl")));

		/*
		 * Norwegian is written as Bokmål and Nynorsk. `no` is the language as
		 * a whole and stems as Bokmål, which nearly all text is and what the
		 * filter defaults to; naming `nb` or `nn` picks the form outright.
		 * Nynorsk goes through the factory because the flag for it is not
		 * public.
		 */
		register(locales, StandardLocaleSupport.of("no")
			.withStopWords(NorwegianAnalyzer.getDefaultStopSet())
			.withStemmer(NorwegianLightStemFilter::new)
			.withDecompounder(Decompounder.forData("nb")));

		register(locales, StandardLocaleSupport.of("nb")
			.withStopWords(NorwegianAnalyzer.getDefaultStopSet())
			.withStemmer(NorwegianLightStemFilter::new)
			.withDecompounder(Decompounder.forData("nb")));

		var nynorsk = new NorwegianLightStemFilterFactory(
			new HashMap<>(Map.of("variant", "nn"))
		);
		register(locales, StandardLocaleSupport.of("nn")
			.withStopWords(NorwegianAnalyzer.getDefaultStopSet())
			.withStemmer(nynorsk::create)
			.withDecompounder(Decompounder.forData("nn")));

		/*
		 * Polish stems through a trained table rather than rules; the table is
		 * shared, the stemmer walking it carries state and is made per stream.
		 */
		register(locales, StandardLocaleSupport.of("pl")
			.withStopWords(PolishAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new StempelFilter(
				stream, new StempelStemmer(PolishAnalyzer.getDefaultTable())
			)));

		register(locales, StandardLocaleSupport.of("pt")
			.withStopWords(PortugueseAnalyzer.getDefaultStopSet())
			.withStemmer(PortugueseLightStemFilter::new));

		/*
		 * Romanian ș and ț are written with either a comma or a cedilla
		 * depending on the keyboard; the stopword list and stemmer know one
		 * spelling, so the other is folded onto it first.
		 */
		register(locales, StandardLocaleSupport.of("ro")
			.withStopWords(RomanianAnalyzer.getDefaultStopSet())
			.withNormalizer(RomanianNormalizationFilter::new)
			.withStemmer(stream -> new SnowballFilter(stream, new RomanianStemmer())));

		register(locales, StandardLocaleSupport.of("ru")
			.withStopWords(RussianAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(stream, new RussianStemmer())));

		/*
		 * Serbian is written in Cyrillic and Latin alike. The stopword list
		 * matches the word as written; stemming first regularizes both
		 * scripts to bald Latin, which is what the stemmer knows and what
		 * makes the two scripts meet as one term.
		 */
		register(locales, StandardLocaleSupport.of("sr")
			.withStopWords(SerbianAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(
				new SerbianNormalizationFilter(stream), new SerbianStemmer()
			)));

		register(locales, StandardLocaleSupport.of("sv")
			.withStopWords(SwedishAnalyzer.getDefaultStopSet())
			.withStemmer(stream -> new SnowballFilter(stream, new SwedishStemmer()))
			.withDecompounder(Decompounder.forData("sv")));

		register(locales, StandardLocaleSupport.of("ta")
			.withStopWords(TamilAnalyzer.getDefaultStopSet())
			.withNormalizer(IndicNormalizationFilter::new)
			.withStemmer(stream -> new SnowballFilter(stream, new TamilStemmer())));

		register(locales, StandardLocaleSupport.of("te")
			.withStopWords(TeluguAnalyzer.getDefaultStopSet())
			.withNormalizer(stream -> new TeluguNormalizationFilter(
				new IndicNormalizationFilter(stream)
			))
			.withStemmer(TeluguStemFilter::new));

		/*
		 * Thai writes no spaces either, but the Unicode segmentation the
		 * engine already uses finds its words through a dictionary, so only
		 * the stopword list is Thai's own. Thai has no stemming - words do
		 * not inflect.
		 */
		register(locales, StandardLocaleSupport.of("th")
			.withStopWords(ThaiAnalyzer.getDefaultStopSet()));

		/*
		 * Turkish tells dotted and dotless i apart, which plain Unicode case
		 * folding merges - I lowercases to ı, not i - so its own case folding
		 * runs first. The suffix an apostrophe attaches to a proper name -
		 * Türkiye'de - is grammar and is taken off the same way.
		 */
		register(locales, StandardLocaleSupport.of("tr")
			.withStopWords(TurkishAnalyzer.getDefaultStopSet())
			.withNormalizer(stream -> new TurkishLowerCaseFilter(
				new ApostropheFilter(stream)
			))
			.withStemmer(stream -> new SnowballFilter(stream, new TurkishStemmer())));

		/*
		 * Ukrainian stems through a dictionary of word forms; the apostrophes
		 * and stress accents its text writes in several ways are regularized
		 * first so the dictionary finds the form.
		 */
		register(locales, StandardLocaleSupport.of("uk")
			.withStopWords(UkrainianMorfologikAnalyzer.getDefaultStopwords())
			.withNormalizer(UkrainianNormalizeFilter::new)
			.withStemmer(stream -> new MorfologikFilter(
				stream, UkrainianDictionary.INSTANCE
			)));

		/*
		 * Chinese words come from a hidden Markov model, as nothing in the
		 * text marks where one ends. Chinese does not inflect, so there is
		 * nothing to stem - the Porter stemmer here serves the Latin words
		 * mixed into Chinese text, the way Lucene's own analyzer does.
		 */
		register(locales, StandardLocaleSupport.of("zh")
			.withStopWords(SmartChineseAnalyzer.getDefaultStopSet())
			.withTokenizer(HMMChineseTokenizer::new)
			.withStemmer(PorterStemFilter::new));

		/*
		 * Traditional Chinese is the same language written in the older forms
		 * of the characters. The model holds no entry for those forms, and
		 * text written in them comes out one token per character. The text is
		 * rewritten as Simplified before it is segmented, so both ways of
		 * writing reach the same words and the same terms.
		 */
		register(locales, StandardLocaleSupport.of("zh-Hant")
			.withStopWords(SmartChineseAnalyzer.getDefaultStopSet())
			.withRewriter(SimplifiedHanCharFilter::new)
			.withTokenizer(HMMChineseTokenizer::new)
			.withStemmer(PorterStemFilter::new));

		return locales.toImmutable();
	}

	/**
	 * Read the stopword list of a locale data set, for the locales whose list
	 * is not one Lucene ships.
	 */
	private static CharArraySet stopWords(String name) {
		var words = new CharArraySet(1 << 10, false);
		LocaleData.forName(name).read("stopwords.txt", words::add);
		return CharArraySet.unmodifiableSet(words);
	}

	private static void register(
		MutableMap<String, LocaleSupport> locales,
		StandardLocaleSupport.Builder builder
	) {
		var support = builder.build();
		locales.put(support.getLocale(), support);
	}

	/*
	 * The articles Lucene's analyzers for these languages elide, kept here
	 * because the analyzers do not expose them.
	 */
	private static final CharArraySet CATALAN_ARTICLES = articles(
		"d", "l", "m", "n", "s", "t"
	);

	private static final CharArraySet ITALIAN_ARTICLES = articles(
		"c", "l", "all", "dall", "dell", "nell", "sull", "coll", "pell",
		"gl", "agl", "dagl", "degl", "negl", "sugl", "un", "m", "t", "s", "v", "d"
	);

	private static final CharArraySet IRISH_ARTICLES = articles(
		"d", "m", "b"
	);

	private static CharArraySet articles(String... words) {
		return CharArraySet.unmodifiableSet(new CharArraySet(List.of(words), true));
	}

	/**
	 * The Ukrainian stemming dictionary, loaded once from the artifact that
	 * ships it and only when Ukrainian is first used.
	 */
	private static final class UkrainianDictionary {
		static final Dictionary INSTANCE;

		static {
			try {
				INSTANCE = Dictionary.read(
					UkrainianDictionary.class.getResource("/ua/net/nlp/ukrainian.dict")
				);
			} catch(IOException e) {
				throw new UncheckedIOException("Unable to load the Ukrainian dictionary", e);
			}
		}
	}

	/**
	 * The locale a tag is answered by when nothing holds the tag itself - the
	 * language a narrower one is a way of writing.
	 *
	 * Only for tags that name a variety of a language registered in its own
	 * right, which is what makes answering with the wider one the same
	 * language rather than a different one. Two languages are split this way.
	 * `nb` and `nn` are how `no` is written, so a field holding `no` answers a
	 * search for either. Chinese splits by script instead: a tag naming
	 * Taiwan, Hong Kong or Macao says Traditional without spelling out
	 * `zh-Hant`, and dropping the region alone would land on the Simplified
	 * `zh`.
	 */
	private static final ImmutableMap<String, String> BROADER = Maps.immutable.ofAll(Map.of(
		"nb", "no",
		"nn", "no",
		"zh-TW", "zh-Hant",
		"zh-HK", "zh-Hant",
		"zh-MO", "zh-Hant"
	));

	/**
	 * Get the support for a locale.
	 *
	 * @param locale
	 *   BCP-47 tag
	 * @return
	 */
	public static Optional<LocaleSupport> get(String locale) {
		if(locale == null) {
			return Optional.empty();
		}

		var support = SUPPORTED.get(locale);
		return support != null
			? Optional.of(support)
			: Optional.ofNullable(SUPPORTED.get(canonical(locale)));
	}

	/**
	 * Get a tag as BCP-47 writes it: a lowercase language, a titlecase script,
	 * an uppercase region. Case carries no meaning in a tag, so {@code zh-hant}
	 * and {@code zh-Hant} are one tag and have to become one spelling before
	 * either is stored, compared or turned into a feature name.
	 *
	 * A tag no language can be read out of comes back as it was given, so that
	 * whatever refuses it reports what was written.
	 *
	 * @param locale
	 *   BCP-47 tag
	 * @return
	 */
	public static String canonical(String locale) {
		if(locale == null || locale.isEmpty()) {
			return locale;
		}

		var canonical = Locale.forLanguageTag(locale).toLanguageTag();
		return UNDETERMINED.equals(canonical) ? locale : canonical;
	}

	/**
	 * Resolve a tag to the closest of the given locales.
	 *
	 * A tag says as much as whoever wrote it knew - a browser sends
	 * {@code nb-NO} where an index holds {@code no} - so it is matched by
	 * dropping what the available locales do not distinguish rather than
	 * exactly. The tag is tried whole, then with its trailing subtags dropped
	 * one at a time, and each of those against the wider language it is a way
	 * of writing, so {@code nb-NO} reaches {@code nb} and then {@code no}.
	 *
	 * Never widens the other way: a tag resolves to a locale that holds what
	 * it asked for, never to one that holds a narrower part of it.
	 *
	 * @param locale
	 *   BCP-47 tag
	 * @param available
	 *   the tags to resolve against, each one this build supports
	 * @return
	 *   the tag among {@code available} to read, or empty when the tag names
	 *   none of them
	 */
	public static Optional<String> resolve(String locale, SetIterable<String> available) {
		if(locale == null || locale.isEmpty()) {
			return Optional.empty();
		}

		if(available.contains(locale)) {
			return Optional.of(locale);
		}

		/*
		 * Case and subtag order are not part of what a tag says, so it is
		 * canonicalized before it is taken apart - `NB-no` and `nb-NO` are the
		 * same tag and have to resolve alike.
		 */
		var candidate = Locale.forLanguageTag(locale).toLanguageTag();
		if(UNDETERMINED.equals(candidate)) {
			// Nothing a tag was recognized in, such as punctuation
			return Optional.empty();
		}

		while(true) {
			if(available.contains(candidate)) {
				return Optional.of(candidate);
			}

			var broader = BROADER.get(candidate);
			if(broader != null && available.contains(broader)) {
				return Optional.of(broader);
			}

			var cut = candidate.lastIndexOf('-');
			if(cut < 0) {
				return Optional.empty();
			}

			candidate = candidate.substring(0, cut);
		}
	}

	/**
	 * Resolve a tag to the closest locale this build supports, see
	 * {@link #resolve(String, SetIterable)}.
	 *
	 * @param locale
	 *   BCP-47 tag
	 * @return
	 *   the tag of the locale to read, or empty when this build has none for
	 *   it
	 */
	public static Optional<String> resolve(String locale) {
		return resolve(locale, supported());
	}

	/**
	 * What {@link Locale#toLanguageTag()} answers for a tag it recognized no
	 * language in.
	 */
	private static final String UNDETERMINED = "und";

	/**
	 * Get if this build has support for a locale.
	 *
	 * @param locale
	 *   BCP-47 tag
	 * @return
	 */
	public static boolean isSupported(String locale) {
		if(locale == null) {
			return false;
		}

		return SUPPORTED.containsKey(locale)
			|| SUPPORTED.containsKey(canonical(locale));
	}

	/**
	 * Get the locale used when neither the value nor the definition names one.
	 *
	 * @return
	 */
	public static LocaleSupport getDefault() {
		return DEFAULT;
	}

	/**
	 * The tags of every supported locale, kept because resolving a tag walks
	 * them and a search resolves one per locale specific field it touches.
	 */
	private static final ImmutableSet<String> SUPPORTED_TAGS =
		SUPPORTED.keysView().toSet().toImmutable();

	/**
	 * Get the tags of every supported locale.
	 *
	 * @return
	 */
	public static SetIterable<String> supported() {
		return SUPPORTED_TAGS;
	}
}
