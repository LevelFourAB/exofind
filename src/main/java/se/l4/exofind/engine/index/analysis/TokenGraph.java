package se.l4.exofind.engine.index.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.search.BoostAttribute;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;

/**
 * What analysis left of a text, read as the positions its words can occupy
 * rather than as a sequence of words.
 *
 * <p>A chain that only rewrites words leaves one word per position, which is
 * the shape a query is built from word by word. A chain that widens the text -
 * a synonym of several words standing where one was typed - leaves a token for
 * every reading of it, and the readings only meet again further along. Reading
 * the stream as {@link Segment segments} says where they meet: within a segment
 * the readings are alternatives, and between segments they are the same text
 * however it was read, so a query is built by joining what each segment asks
 * for.
 *
 * <p>A segment holding one alternative of one word is the whole of an ordinary
 * text, so a query built from segments is the query built word by word wherever
 * nothing widened the text.
 *
 * <p>At most {@link #MAX_ALTERNATIVES} readings of a segment are kept. Beyond
 * that a search would ask for more shapes of the same text than it can answer
 * in time, and the readings that are kept are the ones the rules were written
 * in.
 */
public final class TokenGraph {
	/**
	 * How many readings of one segment are kept. Rules widen a word into a
	 * handful of alternatives at a time, so this is reached by rules that widen
	 * a word into what other rules widen again, several words over.
	 */
	public static final int MAX_ALTERNATIVES = 64;

	/**
	 * One word analysis left, and what a document holding it counts against
	 * one holding the word that was typed.
	 *
	 * @param text
	 *   the word as it is written in the index
	 * @param boost
	 *   the multiplier on what the word contributes, {@code 1} for a word the
	 *   text itself held
	 */
	public record Term(String text, float boost) {
	}

	/**
	 * One word of a reading, and where it sits from the start of the segment.
	 * The offsets of a reading can have holes in them, left by a word the chain
	 * dropped.
	 *
	 * @param offset
	 *   positions from the start of the segment
	 * @param term
	 */
	public record Placed(int offset, Term term) {
	}

	/**
	 * One reading of a segment: the words it holds, in the order they are
	 * searched for.
	 *
	 * @param terms
	 *   never empty
	 */
	public record Alternative(ImmutableList<Placed> terms) {
	}

	/**
	 * A stretch of the text that every reading of it passes through whole.
	 *
	 * @param position
	 *   where the segment starts, counted in positions from the start of the
	 *   text, so that the segments of a phrase keep the holes between them
	 * @param length
	 *   how many positions the segment covers, which every alternative spans
	 * @param alternatives
	 *   the readings, never empty
	 */
	public record Segment(
		int position,
		int length,
		ImmutableList<Alternative> alternatives
	) {
		/**
		 * Get whether the segment is one position holding single words, which
		 * is what a text nothing widened leaves. Such a segment is a choice
		 * between words rather than between readings, and is asked of a field
		 * as one term or as a choice of terms.
		 *
		 * @return
		 */
		public boolean isSingleWord() {
			return length == 1 && alternatives.allSatisfy(a -> a.terms().size() == 1);
		}

		/**
		 * Get the words of a segment that {@link #isSingleWord() holds single
		 * words}, in the order they came out of analysis.
		 *
		 * @return
		 * @throws IllegalStateException
		 *   if the segment holds a reading of several words
		 */
		public ImmutableList<Term> words() {
			if(!isSingleWord()) {
				throw new IllegalStateException(
					"A segment of several positions has readings, not words"
				);
			}

			return alternatives.collect(a -> a.terms().get(0).term());
		}
	}

	/**
	 * One token as analysis placed it.
	 */
	private record Token(Term term, int position, int length) {
	}

	private TokenGraph() {
	}

	/**
	 * Read what an analyzer made of a text.
	 *
	 * <p>The stream is reset, read to its end and ended, and is left for the
	 * caller to close.
	 *
	 * @param stream
	 * @return
	 *   the segments in the order the text was written, empty when analysis
	 *   left nothing - a text of only stopwords, or of nothing at all
	 * @throws IOException
	 *   if the stream could not be read
	 */
	public static ImmutableList<Segment> read(TokenStream stream) throws IOException {
		var tokens = tokens(stream);
		if(tokens.isEmpty()) {
			return Lists.immutable.empty();
		}

		var end = 0;
		for(var token : tokens) {
			end = Math.max(end, token.position() + token.length());
		}

		var segments = Lists.mutable.<Segment>empty();

		var start = tokens.get(0).position();
		for(var cut = start + 1; cut <= end; cut++) {
			if(cut < end && spans(tokens, cut)) {
				continue;
			}

			var readings = readings(tokens, start, cut);
			if(readings.notEmpty()) {
				/*
				 * A stretch holding no words at all is what a dropped word
				 * leaves, and it is carried by the positions of the segments
				 * around it rather than as a segment of its own.
				 */
				segments.add(new Segment(start, cut - start, readings));
			}

			start = cut;
		}

		return segments.toImmutable();
	}

	/**
	 * Read the tokens of a stream, placing each one by the position increments
	 * that came before it.
	 */
	private static MutableList<Token> tokens(TokenStream stream) throws IOException {
		var tokens = Lists.mutable.<Token>empty();

		var term = stream.addAttribute(CharTermAttribute.class);
		var increment = stream.addAttribute(PositionIncrementAttribute.class);
		var length = stream.addAttribute(PositionLengthAttribute.class);

		/*
		 * Added rather than asked for: a chain that widens nothing carries no
		 * boosts, and an attribute that is never set answers with the value a
		 * word the text itself held has.
		 */
		var boost = stream.addAttribute(BoostAttribute.class);

		var position = -1;

		stream.reset();
		while(stream.incrementToken()) {
			position += increment.getPositionIncrement();

			tokens.add(new Token(
				new Term(term.toString(), boost.getBoost()),
				position,
				Math.max(1, length.getPositionLength())
			));
		}
		stream.end();

		return tokens;
	}

	/**
	 * Whether any token covers a position without starting or ending there,
	 * which is what makes it a place the readings have not met again.
	 */
	private static boolean spans(MutableList<Token> tokens, int position) {
		return tokens.anySatisfy(
			token -> token.position() < position
				&& token.position() + token.length() > position
		);
	}

	/**
	 * Work out the readings of one segment, by walking every way of covering
	 * it from its start to its end.
	 */
	private static ImmutableList<Alternative> readings(
		MutableList<Token> tokens,
		int start,
		int end
	) {
		var alternatives = Lists.mutable.<Alternative>empty();
		walk(tokens, start, end, start, Lists.mutable.empty(), alternatives);

		return alternatives.toImmutable();
	}

	private static void walk(
		MutableList<Token> tokens,
		int start,
		int end,
		int position,
		MutableList<Placed> taken,
		MutableList<Alternative> alternatives
	) {
		if(alternatives.size() >= MAX_ALTERNATIVES) {
			return;
		}

		if(position == end) {
			if(taken.notEmpty()) {
				alternatives.add(new Alternative(taken.toImmutable()));
			}

			return;
		}

		var moved = false;
		for(var token : tokens) {
			if(token.position() != position) {
				continue;
			}

			moved = true;
			taken.add(new Placed(position - start, token.term()));
			walk(tokens, start, end, position + token.length(), taken, alternatives);
			taken.remove(taken.size() - 1);
		}

		if(!moved) {
			// A word the chain dropped, which every reading of the segment steps over
			walk(tokens, start, end, position + 1, taken, alternatives);
		}
	}
}
