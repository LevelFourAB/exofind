package se.l4.exofind.engine.query.matchers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.util.List;

import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

/**
 * Tests for taking apart what somebody typed into a search box - which parts
 * the punctuation marks out, and what each of them asks the index for.
 */
public class UserTextTest {
	@Test
	public void testWordsAreTheirOwnParts() {
		assertThat(
			parts("silent spring"),
			contains("silent", "spring")
		);
	}

	@Test
	public void testRunsOfWhitespaceSeparateWords() {
		assertThat(parts("  silent \t spring  "), contains("silent", "spring"));
	}

	@Test
	public void testQuotedWordsAreOnePart() {
		var parsed = UserText.parse("red \"apple watch\"");

		assertThat(parts(parsed), contains("red", "apple watch"));
		assertThat(
			parsed.parts().get(1).kind(),
			is(UserText.Kind.PHRASE)
		);
	}

	@Test
	public void testQuoteThatWasNeverClosedRunsToTheEnd() {
		var parsed = UserText.parse("red \"apple wat");
		var phrase = parsed.parts().get(1);

		assertThat(phrase.text(), is("apple wat"));
		assertThat(phrase.kind(), is(UserText.Kind.PHRASE));
		assertThat(phrase.open(), is(true));
	}

	@Test
	public void testClosedQuoteIsFinished() {
		var parsed = UserText.parse("\"apple watch\"");

		assertThat(parsed.parts().get(0).open(), is(false));
	}

	@Test
	public void testQuotesAroundNothingAskForNothing() {
		assertThat(UserText.parse("\"\"").isEmpty(), is(true));
		assertThat(UserText.parse("\"   \"").isEmpty(), is(true));
	}

	@Test
	public void testMinusExcludesTheWordAfterIt() {
		var parsed = UserText.parse("shoes -leather");

		assertThat(parsed.parts().get(0).exclude(), is(false));
		assertThat(parsed.parts().get(1).exclude(), is(true));
		assertThat(parsed.parts().get(1).text(), is("leather"));
	}

	@Test
	public void testMinusExcludesTheQuotedPhraseAfterIt() {
		var parsed = UserText.parse("-\"apple watch\"");
		var phrase = parsed.parts().get(0);

		assertThat(phrase.exclude(), is(true));
		assertThat(phrase.kind(), is(UserText.Kind.PHRASE));
		assertThat(phrase.text(), is("apple watch"));
	}

	/**
	 * Punctuation only means something where a person meant it to. Inside a
	 * word, or with nothing after it, it is a character somebody typed.
	 */
	@Test
	public void testPunctuationInsideAWordIsText() {
		assertThat(parts("e-mail"), contains("e-mail"));
		assertThat(parts("it\"s"), contains("it\"s"));
		assertThat(parts("spring - cleaning"), contains("spring", "-", "cleaning"));
		assertThat(parts("spring -"), contains("spring", "-"));

		assertThat(UserText.parse("e-mail").parts().get(0).exclude(), is(false));
		assertThat(UserText.parse("spring -").parts().get(1).exclude(), is(false));
	}

	@Test
	public void testNothingTypedIsNothingToSearchFor() {
		assertThat(UserText.parse("").isEmpty(), is(true));
		assertThat(UserText.parse("   ").isEmpty(), is(true));
		assertThat(UserText.parse(null).isEmpty(), is(true));
	}

	@Test
	public void testLooseWordsBecomeOneMatcher() {
		var required = UserText.parse("red \"apple watch\" nike")
			.required(TextMatcher.of("ignored"));

		assertThat(required.size(), is(2));

		var words = required.get(0);
		assertThat(words.text(), is("red nike"));
		assertThat(words.match(), is(TextMatcher.Match.ALL));

		var phrase = required.get(1);
		assertThat(phrase.text(), is("apple watch"));
		assertThat(phrase.match(), is(TextMatcher.Match.PHRASE));
	}

	/**
	 * Only the part the text ended in the middle of can hold a word somebody
	 * is still typing.
	 */
	@Test
	public void testOnlyTheLastPartIsStillBeingTyped() {
		var trailingWord = UserText.parse("\"apple watch\" nik")
			.required(TextMatcher.of("ignored"));
		assertThat(trailingWord.get(0).prefix(), is(TextMatcher.Prefix.LAST_TOKEN));
		assertThat(trailingWord.get(1).prefix(), is(TextMatcher.Prefix.OFF));

		var trailingOpenPhrase = UserText.parse("nike \"apple wat")
			.required(TextMatcher.of("ignored"));
		assertThat(trailingOpenPhrase.get(0).prefix(), is(TextMatcher.Prefix.OFF));
		assertThat(trailingOpenPhrase.get(1).prefix(), is(TextMatcher.Prefix.LAST_TOKEN));

		var trailingClosedPhrase = UserText.parse("nike \"apple watch\"")
			.required(TextMatcher.of("ignored"));
		assertThat(trailingClosedPhrase.get(0).prefix(), is(TextMatcher.Prefix.OFF));
		assertThat(trailingClosedPhrase.get(1).prefix(), is(TextMatcher.Prefix.OFF));
	}

	@Test
	public void testSearchTakingWordsAsCompleteKeepsThemComplete() {
		var required = UserText.parse("silent spr")
			.required(TextMatcher.of("ignored").withPrefix(TextMatcher.Prefix.OFF));

		assertThat(required.get(0).prefix(), is(TextMatcher.Prefix.OFF));
	}

	@Test
	public void testExcludedPartsAreLeftOut() {
		var base = TextMatcher.of("ignored");
		var parsed = UserText.parse("shoes -leather -\"apple watch\"");

		assertThat(parsed.required(base).collect(TextMatcher::text), contains("shoes"));

		var excluded = parsed.excluded(base);
		assertThat(excluded.collect(TextMatcher::text), contains("leather", "apple watch"));
		assertThat(excluded.get(0).match(), is(TextMatcher.Match.ALL));
		assertThat(excluded.get(1).match(), is(TextMatcher.Match.PHRASE));
	}

	/**
	 * What is thrown away is the one thing a search cannot show, so an
	 * exclusion is taken exactly as typed however the rest of the search is
	 * read.
	 */
	@Test
	public void testExclusionsAreNeverWidened() {
		var excluded = UserText.parse("-leath").excluded(TextMatcher.of("ignored"));

		assertThat(excluded.get(0).prefix(), is(TextMatcher.Prefix.OFF));
		assertThat(excluded.get(0).typos(), is(TextMatcher.Typos.OFF));
	}

	@Test
	public void testQuotedPartsCarryTheSlopOfTheSearch() {
		var base = TextMatcher.of("ignored").withSlop(2);
		var parsed = UserText.parse("red \"apple watch\" -\"nike air\"");

		assertThat(parsed.required(base).get(0).slop(), is(0));
		assertThat(parsed.required(base).get(1).slop(), is(2));
		assertThat(parsed.excluded(base).get(0).slop(), is(2));
	}

	@Test
	public void testTextOfOnlyExclusionsAsksForNothingToBeFound() {
		var base = TextMatcher.of("ignored");
		var parsed = UserText.parse("-leather");

		assertThat(parsed.required(base).toList(), is(empty()));
		assertThat(parsed.excluded(base).size(), is(1));
	}

	@Test
	public void testOnlyLooseWordsCanBeLetGoOf() {
		var parsed = UserText.parse("shoes \"apple watch\" -leather shoes");

		// The quoted phrase and the exclusion are not among them, and a word
		// typed twice is one word to let go of
		assertThat(parsed.words().toList(), contains("shoes"));
	}

	@Test
	public void testWordLetGoOfGoesEveryTimeItWasTyped() {
		var parsed = UserText.parse("red shoes red");

		assertThat(parts(parsed.without(Sets.immutable.of("red"))), contains("shoes"));
	}

	@Test
	public void testLettingAWordGoLeavesEverythingElseAsTyped() {
		var parsed = UserText.parse("mens \"running shoes\" -leather waterproof");

		assertThat(
			parsed.without(Sets.immutable.of("waterproof")).text(),
			is("mens \"running shoes\" -leather")
		);
	}

	@Test
	public void testTextReadsBackToTheSameParts() {
		var parsed = UserText.parse("  mens   \"running shoes\"  -leather  ");

		assertThat(parts(UserText.parse(parsed.text())), contains(parts(parsed).toArray()));
	}

	@Test
	public void testQuoteNobodyClosedIsLeftUnclosed() {
		var parsed = UserText.parse("mens \"running sho");

		// Written back closed it would stop being a phrase somebody is still typing
		assertThat(parsed.text(), is("mens \"running sho"));
		assertThat(UserText.parse(parsed.text()).parts().getLast().open(), is(true));
	}

	private static List<String> parts(String text) {
		return parts(UserText.parse(text));
	}

	private static List<String> parts(UserText text) {
		return text.parts().collect(UserText.Part::text).toList();
	}
}
