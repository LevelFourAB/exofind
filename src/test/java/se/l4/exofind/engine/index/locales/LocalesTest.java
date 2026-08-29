package se.l4.exofind.engine.index.locales;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.analysis.AnalyzerMode;
import se.l4.exofind.engine.index.analysis.Analyzers;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;

/**
 * Tests that every supported locale actually behaves like the language it
 * stands for - its stopwords are dropped, its word forms meet at one term,
 * and the quirks that made a locale need more than Unicode case folding do
 * what they are there for.
 *
 * Everything goes through the engine-built matching chain, the same one a
 * field without an explicit analyzer gets, so what is tested here is what an
 * index does by default.
 */
public class LocalesTest {
	/**
	 * Two forms of the same word per locale. The exact stem an algorithm
	 * produces is its own business - what matters, and what is asserted, is
	 * that both forms come out as the same term, because that is what makes a
	 * search for one find the other.
	 */
	private static final Map<String, List<String>> INFLECTED_FORMS = Map.ofEntries(
		// The definite article is a prefix
		Map.entry("ar", List.of("الكتاب", "كتاب")),
		Map.entry("bg", List.of("книгата", "книга")),
		Map.entry("bn", List.of("মানুষেরা", "মানুষ")),
		Map.entry("fa", List.of("کتابها", "کتاب")),
		Map.entry("hi", List.of("लड़के", "लड़का")),
		// The plural is a suffix - keṭāharū is the plural of keṭā
		Map.entry("ne", List.of("केटाहरू", "केटा")),
		// The definite plural is a suffix - kitêbekan is the books
		Map.entry("ckb", List.of("کتێبەکان", "کتێب")),
		// Indonesian inflects with prefixes - membaca is to read, baca read
		Map.entry("id", List.of("membaca", "baca")),
		// The conjugated verb reduces to its dictionary form
		Map.entry("ja", List.of("食べました", "食べる")),
		// The particle glued onto the noun is taken apart and dropped
		Map.entry("ko", List.of("학교에", "학교")),
		Map.entry("ta", List.of("புத்தகங்கள்", "புத்தகம்")),
		Map.entry("te", List.of("పిల్లలు", "పిల్ల")),
		/*
		 * Chinese does not inflect - what its support stems is the Latin
		 * words mixed into Chinese text.
		 */
		Map.entry("zh", List.of("running", "run")),
		Map.entry("zh-Hant", List.of("running", "run")),
		Map.entry("ca", List.of("portes", "porta")),
		Map.entry("cs", List.of("ženám", "žena")),
		Map.entry("da", List.of("husene", "hus")),
		Map.entry("de", List.of("Häuser", "Haus")),
		Map.entry("el", List.of("μαθητές", "μαθητής")),
		Map.entry("en", List.of("running", "runs")),
		Map.entry("es", List.of("libros", "libro")),
		Map.entry("et", List.of("raamatud", "raamat")),
		Map.entry("eu", List.of("etxeak", "etxe")),
		Map.entry("fi", List.of("taloissa", "talo")),
		Map.entry("fr", List.of("chevaux", "cheval")),
		// Irish stems grammar off the front - the mutated bhean is bean
		Map.entry("ga", List.of("bhean", "bean")),
		Map.entry("gl", List.of("casas", "casa")),
		Map.entry("hu", List.of("házak", "ház")),
		Map.entry("hy", List.of("երեխաներ", "երեխա")),
		/*
		 * Icelandic changes the stem as often as the ending - the plural of
		 * bók is bækur - so its forms are looked up rather than cut.
		 */
		Map.entry("is", List.of("hestinum", "hestur")),
		Map.entry("it", List.of("ragazzi", "ragazzo")),
		Map.entry("lt", List.of("namai", "namas")),
		Map.entry("lv", List.of("grāmatas", "grāmata")),
		Map.entry("nl", List.of("katten", "kat")),
		Map.entry("no", List.of("husene", "hus")),
		Map.entry("nb", List.of("husene", "hus")),
		Map.entry("nn", List.of("husa", "hus")),
		Map.entry("pl", List.of("książki", "książka")),
		Map.entry("pt", List.of("livros", "livro")),
		Map.entry("ro", List.of("elevii", "elev")),
		Map.entry("ru", List.of("книги", "книга")),
		Map.entry("sr", List.of("kuće", "kuća")),
		Map.entry("sv", List.of("bilarna", "bil")),
		Map.entry("tr", List.of("kitaplar", "kitap")),
		Map.entry("uk", List.of("книги", "книга"))
	);

	/**
	 * Thai words do not inflect, so there are no two forms to bring together
	 * and nothing for a stemmer to do.
	 */
	private static final List<String> NOT_STEMMED = List.of("th");

	/**
	 * Locales whose stopword list is not made of words - Korean drops its
	 * grammar by part of speech instead of by a list, and the Chinese list
	 * holds the punctuation its tokenizer emits. Both have tests of their
	 * own below.
	 */
	private static final List<String> NO_WORD_LIST = List.of("ko", "zh", "zh-Hant");

	private List<String> terms(String locale, String value) {
		var support = Locales.get(locale).orElseThrow();

		/*
		 * These tests probe the locale's own pieces - stemming, stopwords,
		 * normalization - so compound splitting, which has tests of its own,
		 * is turned off to keep its parts out of the comparisons.
		 */
		var analyzer = Analyzers.matching(
			StringFieldTypeDef.TextUsageConfig.newBuilder()
				.setDecompound(
					StringFieldTypeDef.TextUsageConfig.DecompoundMode.DECOMPOUND_MODE_NONE
				)
				.build(),
			ResourcesDef.getDefaultInstance(),
			support,
			AnalyzerMode.INDEXING
		);

		var terms = new ArrayList<String>();
		try(var stream = analyzer.tokenStream("field", value)) {
			var term = stream.addAttribute(CharTermAttribute.class);
			stream.reset();
			while(stream.incrementToken()) {
				terms.add(term.toString());
			}
			stream.end();
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}

		return terms;
	}

	/**
	 * Where each term of a value started and ended, as the same chain reports
	 * it to a highlighter.
	 */
	private List<Integer> offsets(String locale, String value) {
		var support = Locales.get(locale).orElseThrow();

		var analyzer = Analyzers.matching(
			StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
			ResourcesDef.getDefaultInstance(),
			support,
			AnalyzerMode.INDEXING
		);

		var offsets = new ArrayList<Integer>();
		try(var stream = analyzer.tokenStream("field", value)) {
			var offset = stream.addAttribute(OffsetAttribute.class);
			stream.reset();
			while(stream.incrementToken()) {
				offsets.add(offset.startOffset());
				offsets.add(offset.endOffset());
			}
			stream.end();
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}

		return offsets;
	}

	/**
	 * Run a check against every supported locale and report every locale that
	 * fails rather than the first, so a sweep over the languages reads as one
	 * list.
	 */
	private void forEverySupportedLocale(LocaleCheck check) {
		var failures = new ArrayList<String>();

		for(var locale : Locales.supported().toSortedList()) {
			try {
				check.run(locale);
			} catch(AssertionError e) {
				failures.add(locale + ": " + e.getMessage());
			}
		}

		assertThat(failures, is(empty()));
	}

	private interface LocaleCheck {
		void run(String locale);
	}

	@Test
	public void testEverySupportedLocaleHasAnInflectionPairOrAReasonNotTo() {
		var covered = new ArrayList<>(INFLECTED_FORMS.keySet());
		covered.addAll(NOT_STEMMED);

		assertThat(
			covered,
			containsInAnyOrder(Locales.supported().toArray(new String[0]))
		);
	}

	@Test
	public void testTwoFormsOfAWordMeetAtTheSameTerm() {
		forEverySupportedLocale(locale -> {
			var forms = INFLECTED_FORMS.get(locale);
			if(forms == null) {
				return;
			}

			var first = terms(locale, forms.get(0));
			var second = terms(locale, forms.get(1));

			assertThat(
				"`" + forms.get(0) + "` and `" + forms.get(1) + "`",
				first,
				is(second)
			);
			assertThat(first, is(not(empty())));
		});
	}

	/**
	 * Words are taken from each locale's own stopword list, so the check is
	 * that the list actually takes effect in the chain rather than which
	 * words are on it.
	 */
	@Test
	public void testStopwordsOfTheLocaleAreDropped() {
		forEverySupportedLocale(locale -> {
			if(NO_WORD_LIST.contains(locale)) {
				return;
			}

			var support = Locales.get(locale).orElseThrow();

			var checked = 0;
			for(var entry : support.getStopWords()) {
				var word = new String((char[]) entry);

				/*
				 * A word with punctuation - an elided d' - may not survive
				 * tokenization as itself, which says nothing about the list.
				 */
				if(!word.chars().allMatch(Character::isLetter)) {
					continue;
				}

				assertThat("`" + word + "`", terms(locale, word), is(empty()));
				if(++checked == 3) {
					break;
				}
			}

			assertThat("no stopwords checked", checked, is(not(0)));
		});
	}

	@Test
	public void testEveryLocaleOrdersByItsCollation() {
		forEverySupportedLocale(locale -> {
			var support = Locales.get(locale).orElseThrow();

			assertThat(
				support.getCollationKey("a"),
				is(lessThan(support.getCollationKey("b")))
			);
			assertThat(
				support.getCollationKey("a"),
				is(support.getCollationKey("a"))
			);
		});
	}

	/**
	 * Swedish sorts å, ä and ö after z; German sorts them among a and o. The
	 * same values, ordered differently per locale, is what locale collation
	 * is for.
	 */
	@Test
	public void testCollationFollowsTheLocale() {
		var swedish = Locales.get("sv").orElseThrow();
		var german = Locales.get("de").orElseThrow();

		assertThat(
			swedish.getCollationKey("zebra"),
			is(lessThan(swedish.getCollationKey("äpple")))
		);
		assertThat(
			german.getCollationKey("äpfel"),
			is(lessThan(german.getCollationKey("zebra")))
		);
	}

	/**
	 * Unicode case folding merges I with i, which is wrong in Turkish - I is
	 * the capital of dotless ı. The locale's own folding runs first, so both
	 * spellings of the same word meet and different words stay apart.
	 */
	@Test
	public void testTurkishTellsDottedAndDotlessIApart() {
		assertThat(terms("tr", "KISA"), is(terms("tr", "kısa")));
		assertThat(terms("tr", "KISA"), is(not(terms("tr", "kisa"))));
	}

	@Test
	public void testTurkishDropsTheSuffixAfterAnApostrophe() {
		assertThat(terms("tr", "Türkiye'de"), is(terms("tr", "türkiye")));
	}

	/**
	 * Modern Greek writes accents that its stopword list and stemmer are
	 * written without, so accented and unaccented spellings meet.
	 */
	@Test
	public void testGreekFoldsAccents() {
		assertThat(terms("el", "μαθητές"), is(terms("el", "μαθητες")));
	}

	@Test
	public void testGreekDropsAccentedStopwords() {
		assertThat(terms("el", "από"), is(empty()));
	}

	/**
	 * Icelandic is stemmed by looking each form up, which is what reaches the
	 * plurals no ending describes - bók to bækur, maður to menn - and the
	 * vowel the stem changes to before a dative plural.
	 */
	@Test
	public void testIcelandicBringsIrregularFormsToTheSameTerm() {
		assertThat(terms("is", "bækur"), is(terms("is", "bók")));
		assertThat(terms("is", "menn"), is(terms("is", "maður")));
		assertThat(terms("is", "börnum"), is(terms("is", "barn")));
	}

	/**
	 * A word the lookup does not hold comes out as it was written. A rule
	 * stemmer would cut it at a guess; there is no guess to make here.
	 */
	@Test
	public void testIcelandicLeavesAnUnknownWordAlone() {
		assertThat(terms("is", "blorkurinn"), contains("blorkurinn"));
	}

	@Test
	public void testFrenchElidesArticles() {
		assertThat(terms("fr", "l'homme"), is(terms("fr", "homme")));
	}

	@Test
	public void testItalianElidesArticles() {
		assertThat(terms("it", "l'amico"), is(terms("it", "amico")));
	}

	@Test
	public void testCatalanElidesArticles() {
		assertThat(terms("ca", "l'home"), is(terms("ca", "home")));
	}

	/**
	 * German umlauts are also written as two letters when a keyboard has
	 * none, and the stemmer's normalization makes both spellings one term.
	 */
	@Test
	public void testGermanUmlautsMatchTheirTwoLetterSpelling() {
		assertThat(terms("de", "Häuser"), is(terms("de", "Haeuser")));
	}

	/**
	 * Serbian is written in Cyrillic and Latin alike, and a search in one
	 * script should find documents in the other.
	 */
	@Test
	public void testSerbianScriptsMeetAtTheSameTerm() {
		assertThat(terms("sr", "књига"), is(terms("sr", "knjiga")));
	}

	/**
	 * The apostrophe inside Ukrainian words is typed several ways depending
	 * on the keyboard.
	 */
	@Test
	public void testUkrainianApostrophesAreRegularized() {
		assertThat(terms("uk", "п’ять"), is(terms("uk", "п'ять")));
	}

	/**
	 * Romanian ș and ț are written with a comma below or, on older systems,
	 * a cedilla.
	 */
	@Test
	public void testRomanianCedillaSpellingsMeetAtTheSameTerm() {
		assertThat(terms("ro", "țară"), is(terms("ro", "ţară")));
	}

	/**
	 * The two written forms of Norwegian inflect differently - the Nynorsk
	 * plural `husa` has to be asked for as Nynorsk.
	 */
	@Test
	public void testNorwegianFormsStemTheirOwnWay() {
		assertThat(terms("nn", "husa"), is(terms("nn", "hus")));
	}

	@Test
	public void testEnglishStemsPossessives() {
		assertThat(terms("en", "Carson's"), is(contains("carson")));
	}

	/**
	 * Chinese writes no spaces, so finding the words at all is the locale's
	 * work - here the model has to see two words in four characters.
	 */
	@Test
	public void testChineseSegmentsWords() {
		assertThat(terms("zh", "自来水公司"), is(contains("自来水", "公司")));
	}

	@Test
	public void testChineseDropsPunctuation() {
		assertThat(terms("zh", "你好，世界"), is(contains("你好", "世界")));
	}

	/**
	 * Traditional and Simplified are the same words written differently, so
	 * the same sentence in either comes out as the same terms.
	 */
	@Test
	public void testTraditionalChineseReachesTheSameTermsAsSimplified() {
		assertThat(
			terms("zh-Hant", "我們去臺灣旅行"),
			is(terms("zh", "我们去台湾旅行"))
		);
		assertThat(
			terms("zh-Hant", "資訊科技管理學院"),
			is(terms("zh", "资讯科技管理学院"))
		);
	}

	/**
	 * The model that finds Chinese words holds the Simplified forms only.
	 * Without the rewriting it matches nothing in Traditional text and falls
	 * back to one token per character, so a search for a word finds no
	 * document holding it.
	 */
	@Test
	public void testTraditionalChineseIsSegmentedIntoWords() {
		assertThat(terms("zh-Hant", "資訊科技"), is(contains("资讯", "科技")));
	}

	/**
	 * Highlighting reports where a term sat in the text as it was given, so
	 * rewriting the characters may not move anything.
	 */
	@Test
	public void testTraditionalChineseKeepsTheOffsetsOfTheText() {
		var text = "我們去臺灣旅行";

		assertThat(
			offsets("zh-Hant", text),
			is(offsets("zh", "我们去台湾旅行"))
		);

		for(var offset : offsets("zh-Hant", text)) {
			assertThat(offset, is(lessThanOrEqualTo(text.length())));
		}
	}

	/**
	 * A tag says the same thing however it is cased, so a definition naming
	 * `zh-hant` names the locale registered as `zh-Hant`.
	 */
	@Test
	public void testATagIsFoundWhateverItsCase() {
		assertThat(Locales.isSupported("zh-hant"), is(true));
		assertThat(
			Locales.get("ZH-HANT").orElseThrow().getLocale(),
			is("zh-Hant")
		);
		assertThat(Locales.canonical("zh-hant"), is("zh-Hant"));
	}

	/**
	 * A tag holding no language comes back as it was given, so that whatever
	 * refuses it reports what was written rather than `und`.
	 */
	@Test
	public void testATagWithNoLanguageIsLeftAsWritten() {
		assertThat(Locales.canonical("!!!"), is("!!!"));
		assertThat(Locales.isSupported("!!!"), is(false));
	}

	/**
	 * Every registered tag has to be spelled the way BCP-47 canonicalizes it.
	 * A tag stored in any other spelling would be rewritten on its way into a
	 * definition and then match nothing this build offers.
	 */
	@Test
	public void testEverySupportedTagIsSpelledCanonically() {
		forEverySupportedLocale(locale ->
			assertThat(Locales.canonical(locale), is(locale))
		);
	}

	/**
	 * Taiwan, Hong Kong and Macao write Traditional without saying so in the
	 * tag. Dropping the region alone would land on Simplified Chinese.
	 */
	@Test
	public void testAChineseRegionResolvesToTheScriptItWrites() {
		var held = Sets.immutable.of("zh", "zh-Hant");

		assertThat(Locales.resolve("zh-TW", held), is(Optional.of("zh-Hant")));
		assertThat(Locales.resolve("zh-HK", held), is(Optional.of("zh-Hant")));
		assertThat(Locales.resolve("zh-MO", held), is(Optional.of("zh-Hant")));
		assertThat(Locales.resolve("zh-CN", held), is(Optional.of("zh")));
	}

	/**
	 * A field that holds Chinese in one script answers a search in the other,
	 * because there is no closer variant to read.
	 */
	@Test
	public void testAChineseRegionFallsBackToTheOnlyScriptHeld() {
		var held = Sets.immutable.of("zh");

		assertThat(Locales.resolve("zh-TW", held), is(Optional.of("zh")));
		assertThat(Locales.resolve("zh-Hant", held), is(Optional.of("zh")));
	}

	/**
	 * Japanese segments through its morphological dictionary, drops the
	 * particles that are grammar rather than meaning, and reduces conjugated
	 * verbs to their dictionary form.
	 */
	@Test
	public void testJapaneseSegmentsAndDropsParticles() {
		assertThat(terms("ja", "日本語の勉強"), is(contains("日本語", "勉強")));
	}

	/**
	 * Korean spaces separate phrases rather than words, so the particles
	 * glued onto a noun have to be taken off for the noun to be found.
	 */
	@Test
	public void testKoreanDropsParticles() {
		assertThat(terms("ko", "학교에 갔다"), is(terms("ko", "학교 갔다")));
	}

	/**
	 * A Korean compound noun is indexed whole as well as in parts, so that a
	 * search for the compound as written finds it and a search for a part
	 * finds the compounds built from it.
	 */
	@Test
	public void testKoreanKeepsCompoundsBesideTheirParts() {
		assertThat(terms("ko", "가곡역"), is(contains("가곡역", "가곡", "역")));
	}

	/**
	 * Thai writes no spaces but needs no tokenizer of its own - the Unicode
	 * segmentation the engine already uses finds Thai words by dictionary.
	 */
	@Test
	public void testThaiSegmentsWords() {
		assertThat(terms("th", "แมวกินปลา"), is(contains("แมว", "กิน", "ปลา")));
	}

	@Test
	public void testResolveFindsAnExactTag() {
		assertThat(Locales.resolve("sv"), is(Optional.of("sv")));
	}

	/**
	 * A tag says as much as whoever wrote it knew - a browser sends a region
	 * where the engine has rules for the language - so the region is dropped
	 * rather than the tag refused.
	 */
	@Test
	public void testResolveDropsSubtagsTheRegistryDoesNotDistinguish() {
		assertThat(Locales.resolve("en-GB"), is(Optional.of("en")));
		assertThat(Locales.resolve("pt-BR"), is(Optional.of("pt")));
	}

	/**
	 * Case and subtag order are not part of what a tag says, so a tag written
	 * either way resolves alike.
	 */
	@Test
	public void testResolveIgnoresCase() {
		assertThat(Locales.resolve("EN-gb"), is(Optional.of("en")));
	}

	/**
	 * Bokmål and Nynorsk are how Norwegian is written, so a tag naming one
	 * reaches the language itself when only that is registered - through the
	 * region first, which is how a browser writes it.
	 */
	@Test
	public void testResolveReachesTheWiderLanguage() {
		assertThat(Locales.resolve("nb-NO", Sets.immutable.of("no")), is(Optional.of("no")));
		assertThat(Locales.resolve("nn", Sets.immutable.of("no")), is(Optional.of("no")));
	}

	/**
	 * Only ever towards the wider language: an index holding Bokmål alone does
	 * not answer for Norwegian as a whole, which includes Nynorsk it has
	 * nothing for.
	 */
	@Test
	public void testResolveDoesNotNarrow() {
		assertThat(Locales.resolve("no", Sets.immutable.of("nb")), is(Optional.empty()));
	}

	/**
	 * An exact match wins over the wider language, so a field holding both
	 * reads the one that was asked for.
	 */
	@Test
	public void testResolvePrefersTheTagItself() {
		assertThat(Locales.resolve("nb", Sets.immutable.of("no", "nb")), is(Optional.of("nb")));
	}

	@Test
	public void testResolveOfATagWithNoRulesIsEmpty() {
		assertThat(Locales.resolve("xx"), is(Optional.empty()));
		assertThat(Locales.resolve("!!"), is(Optional.empty()));
		assertThat(Locales.resolve(""), is(Optional.empty()));
		assertThat(Locales.resolve(null), is(Optional.empty()));
	}
}
