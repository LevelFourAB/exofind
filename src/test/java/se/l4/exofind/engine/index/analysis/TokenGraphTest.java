package se.l4.exofind.engine.index.analysis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.search.BoostAttribute;
import org.eclipse.collections.api.list.ListIterable;
import org.junit.jupiter.api.Test;

/**
 * Tests for reading an analyzed text as the segments a query is built from,
 * covering the shapes analysis leaves: one word per position, several words
 * stacked at one, a word standing for several, and the holes a dropped word
 * leaves.
 */
public class TokenGraphTest {
	/**
	 * One token as a test writes it.
	 *
	 * @param increment
	 *   positions since the token before, {@code 0} stacking it on that one
	 * @param length
	 *   positions the token covers
	 */
	private record Token(String term, int increment, int length, float boost) {
		static Token of(String term) {
			return new Token(term, 1, 1, 1f);
		}

		static Token stacked(String term) {
			return new Token(term, 0, 1, 1f);
		}
	}

	/**
	 * A stream of tokens written by hand, so a shape analysis rarely produces
	 * can be read as easily as one it always does.
	 */
	private static final class Canned extends TokenStream {
		private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
		private final PositionIncrementAttribute increment =
			addAttribute(PositionIncrementAttribute.class);
		private final PositionLengthAttribute length =
			addAttribute(PositionLengthAttribute.class);
		private final BoostAttribute boost = addAttribute(BoostAttribute.class);

		private final List<Token> tokens;
		private int next;

		Canned(List<Token> tokens) {
			this.tokens = tokens;
		}

		@Override
		public boolean incrementToken() {
			if(next >= tokens.size()) {
				return false;
			}

			var token = tokens.get(next++);

			clearAttributes();
			term.setEmpty().append(token.term());
			increment.setPositionIncrement(token.increment());
			length.setPositionLength(token.length());
			boost.setBoost(token.boost());

			return true;
		}
	}

	private static ListIterable<TokenGraph.Segment> read(Token... tokens) throws IOException {
		try(var stream = new Canned(List.of(tokens))) {
			return TokenGraph.read(stream);
		}
	}

	private static List<String> words(TokenGraph.Segment segment) {
		return segment.words().collect(TokenGraph.Term::text).toList();
	}

	/**
	 * One reading of a segment, written as the words it holds in order.
	 */
	private static List<String> reading(TokenGraph.Alternative alternative) {
		return alternative.terms().collect(placed -> placed.term().text()).toList();
	}

	@Test
	public void testTextOfNothingHasNoSegments() throws IOException {
		assertThat(read().toList(), is(empty()));
	}

	@Test
	public void testEachWordIsASegmentOfItsOwn() throws IOException {
		var segments = read(Token.of("silent"), Token.of("spring"));

		assertThat(segments.size(), is(2));
		assertThat(words(segments.get(0)), contains("silent"));
		assertThat(words(segments.get(1)), contains("spring"));
		assertThat(segments.get(0).position(), is(0));
		assertThat(segments.get(1).position(), is(1));
	}

	/**
	 * Words a filter stacked at one position are a choice between words, not a
	 * choice between readings, so they stay one segment of one position.
	 */
	@Test
	public void testStackedWordsAreOneSegment() throws IOException {
		var segments = read(Token.of("cafe"), Token.stacked("café"));

		assertThat(segments.size(), is(1));
		assertThat(segments.get(0).isSingleWord(), is(true));
		assertThat(words(segments.get(0)), contains("cafe", "café"));
	}

	/**
	 * A word standing for two makes one segment of the two positions, with a
	 * reading for each way of covering it.
	 */
	@Test
	public void testWordStandingForSeveralMakesOneSegment() throws IOException {
		var segments = read(
			new Token("ny", 1, 2, 0.8f),
			new Token("new", 0, 1, 1f),
			Token.of("york"),
			Token.of("pizza")
		);

		assertThat(segments.size(), is(2));

		var widened = segments.get(0);
		assertThat(widened.isSingleWord(), is(false));
		assertThat(widened.position(), is(0));
		assertThat(widened.length(), is(2));
		assertThat(
			widened.alternatives().collect(TokenGraphTest::reading).toList(),
			contains(List.of("ny"), List.of("new", "york"))
		);

		assertThat(words(segments.get(1)), contains("pizza"));
		assertThat(segments.get(1).position(), is(2));
	}

	/**
	 * A dropped word is carried by the positions of the segments around it,
	 * which is what keeps a phrase lined up with the value it was written from.
	 */
	@Test
	public void testDroppedWordLeavesAHoleBetweenSegments() throws IOException {
		var segments = read(Token.of("silent"), new Token("spring", 2, 1, 1f));

		assertThat(segments.size(), is(2));
		assertThat(segments.get(0).position(), is(0));
		assertThat(segments.get(1).position(), is(2));
	}

	/**
	 * A reading stepping over a dropped word keeps the places of its words, so
	 * the phrase it asks for has the same hole in it.
	 */
	@Test
	public void testReadingKeepsTheHoleInsideIt() throws IOException {
		var segments = read(
			new Token("usa", 1, 3, 0.8f),
			new Token("united", 0, 1, 1f),
			new Token("states", 2, 1, 1f)
		);

		assertThat(segments.size(), is(1));

		var readings = segments.get(0).alternatives();
		assertThat(readings.size(), is(2));

		var spelled = readings.get(1);
		assertThat(reading(spelled), contains("united", "states"));
		assertThat(spelled.terms().get(0).offset(), is(0));
		assertThat(spelled.terms().get(1).offset(), is(2));
	}

	/**
	 * What a word counts is read from the stream, so a query built from the
	 * segments can tell a word a rule added from one that was typed.
	 */
	@Test
	public void testWordsCarryWhatTheyCount() throws IOException {
		var segments = read(Token.of("trainers"), new Token("sneakers", 0, 1, 0.8f));

		var words = segments.get(0).words();
		assertThat(words.get(0).boost(), is(1f));
		assertThat(words.get(1).boost(), is(0.8f));
	}

	/**
	 * A text whose first word was dropped starts where its first surviving word
	 * sits, so nothing is asked for at a position holding no word.
	 */
	@Test
	public void testLeadingHoleIsNotASegment() throws IOException {
		var segments = read(new Token("spring", 2, 1, 1f));

		assertThat(segments.size(), is(1));
		assertThat(segments.get(0).position(), is(1));
		assertThat(words(segments.get(0)), contains("spring"));
	}
}
