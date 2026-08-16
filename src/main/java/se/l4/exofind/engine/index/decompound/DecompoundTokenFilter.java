package se.l4.exofind.engine.index.decompound;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

/**
 * Splits compound tokens into their parts: every token passes through
 * unchanged, and where the hyphenation grammar allows a break and the word
 * list knows the piece, the piece follows at the same position with the
 * offsets of the whole token.
 *
 * A part runs between two break positions and is kept when the list contains
 * it - or contains it one character shorter, which is how a linking letter
 * like the German {@code s} in {@code arbeitszimmer} is shaved off. Needing
 * both the grammar and the list is what keeps arbitrary substrings out; it
 * also means a compound whose boundary the grammar misses stays whole rather
 * than being split wrongly.
 */
final class DecompoundTokenFilter extends TokenFilter {
	private final Hyphenator hyphenator;
	private final CharArraySet words;
	private final int minWordSize;
	private final int minPartSize;
	private final int maxPartSize;

	private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
	private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
	private final PositionIncrementAttribute posIncAtt =
		addAttribute(PositionIncrementAttribute.class);

	private final Deque<String> parts = new ArrayDeque<>();
	private State state;

	DecompoundTokenFilter(
		TokenStream input,
		Hyphenator hyphenator,
		CharArraySet words,
		int minWordSize,
		int minPartSize,
		int maxPartSize
	) {
		super(input);
		this.hyphenator = hyphenator;
		this.words = words;
		this.minWordSize = minWordSize;
		this.minPartSize = minPartSize;
		this.maxPartSize = maxPartSize;
	}

	@Override
	public boolean incrementToken() throws IOException {
		if(!parts.isEmpty()) {
			// The other attributes stay those of the whole token
			restoreState(state);
			termAtt.setEmpty().append(parts.removeFirst());
			posIncAtt.setPositionIncrement(0);
			return true;
		}

		state = null;
		if(!input.incrementToken()) {
			return false;
		}

		if(termAtt.length() >= minWordSize) {
			decompose();
			if(!parts.isEmpty()) {
				state = captureState();
			}
		}

		return true;
	}

	private void decompose() {
		var buffer = termAtt.buffer();
		var length = termAtt.length();

		var breaks = hyphenator.breaks(buffer, length);
		if(breaks.isEmpty()) {
			return;
		}

		/*
		 * The ends of the token bound the first and last part, and a part is
		 * always shorter than the whole so the token is never re-added.
		 */
		var points = new int[breaks.size() + 2];
		for(var i = 0; i < breaks.size(); i++) {
			points[i + 1] = breaks.get(i);
		}
		points[points.length - 1] = length;

		var maxSize = Math.min(maxPartSize, length - 1);

		/*
		 * Distinct spans can shave down to the same part - `huse` and `hus`
		 * both give `hus` - and one copy per token is what matching needs,
		 * so the parts are gathered as a set before they are queued.
		 */
		var found = new LinkedHashSet<String>();

		for(var i = 0; i < points.length - 1; i++) {
			for(var j = points.length - 1; j > i; j--) {
				var partLength = points[j] - points[i];
				if(partLength > maxSize) {
					continue;
				}
				if(partLength < minPartSize) {
					break;
				}

				if(words.contains(buffer, points[i], partLength)) {
					found.add(new String(buffer, points[i], partLength));
				} else if(words.contains(buffer, points[i], partLength - 1)) {
					// A part with a linking letter on the end
					found.add(new String(buffer, points[i], partLength - 1));
				}
			}
		}

		parts.addAll(found);
	}

	@Override
	public void reset() throws IOException {
		super.reset();
		parts.clear();
		state = null;
	}
}
