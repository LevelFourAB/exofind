package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.LongConsumer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntroSorter;
import org.apache.lucene.util.automaton.ByteRunAutomaton;

/**
 * The values of one field in one segment, folded by an analyzer and sorted by
 * their folded form, for finding every value that starts with a folded prefix.
 *
 * <p>A facet field holds each value as it was given, so a prefix typed in one
 * case finds nothing in the term dictionary itself. Folding each value the way
 * the prefix is folded and sorting the results puts every value a prefix
 * matches next to each other, and {@link #forEachStartingWith} finds the run
 * by binary search. Built once per segment and kept, see
 * {@link FacetStates#foldedTermsOf}; an instance is immutable and safe to
 * read from any thread.
 *
 * <p>Held as one byte array with the start of each value, at about the size
 * of the folded values themselves, plus one ordinal per value for the sorted
 * order.
 */
final class FoldedTerms {
	/**
	 * The folded values, one after the other, in ordinal order.
	 */
	private final byte[] bytes;

	/**
	 * Where each ordinal's folded value starts in {@link #bytes}, with one
	 * entry past the last for its end.
	 */
	private final int[] starts;

	/**
	 * The ordinals in the order of their folded values.
	 */
	private final int[] sorted;

	private FoldedTerms(byte[] bytes, int[] starts, int[] sorted) {
		this.bytes = bytes;
		this.starts = starts;
		this.sorted = sorted;
	}

	/**
	 * Fold and sort every value of the given doc values.
	 *
	 * @param field
	 *   the name the analyzer folds under
	 * @param normalizer
	 *   what folds a value, through {@link Analyzer#normalize(String, String)}
	 * @param values
	 *   the values of the segment, read from the first ordinal to the last
	 * @return
	 * @throws IOException
	 *   if the values cannot be read
	 */
	static FoldedTerms build(String field, Analyzer normalizer, SortedSetDocValues values)
		throws IOException
	{
		var count = Math.toIntExact(values.getValueCount());
		var starts = new int[count + 1];
		var bytes = new byte[Math.max(16, count * 8)];
		var length = 0;

		for(var ord = 0; ord < count; ord++) {
			var folded = fold(field, normalizer, values.lookupOrd(ord));
			if(bytes.length - length < folded.length) {
				bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, length + folded.length));
			}

			System.arraycopy(folded.bytes, folded.offset, bytes, length, folded.length);
			starts[ord] = length;
			length += folded.length;
		}

		starts[count] = length;

		var sorted = new int[count];
		for(var ord = 0; ord < count; ord++) {
			sorted[ord] = ord;
		}

		var terms = new FoldedTerms(Arrays.copyOf(bytes, length), starts, sorted);
		terms.sort();
		return terms;
	}

	/**
	 * Fold one value. A value the analyzer cannot fold into one token - a char
	 * filter can rewrite it into nothing - is kept as it was given, so that it
	 * still matches a prefix spelled the way it is stored.
	 */
	private static BytesRef fold(String field, Analyzer normalizer, BytesRef value) {
		try {
			return normalizer.normalize(field, value.utf8ToString());
		} catch(IllegalStateException e) {
			return value;
		}
	}

	private void sort() {
		new IntroSorter() {
			private int pivotStart;
			private int pivotEnd;

			@Override
			protected void setPivot(int i) {
				pivotStart = starts[sorted[i]];
				pivotEnd = starts[sorted[i] + 1];
			}

			@Override
			protected int comparePivot(int j) {
				return Arrays.compareUnsigned(
					bytes, pivotStart, pivotEnd,
					bytes, starts[sorted[j]], starts[sorted[j] + 1]
				);
			}

			@Override
			protected int compare(int i, int j) {
				return Arrays.compareUnsigned(
					bytes, starts[sorted[i]], starts[sorted[i] + 1],
					bytes, starts[sorted[j]], starts[sorted[j] + 1]
				);
			}

			@Override
			protected void swap(int i, int j) {
				var ord = sorted[i];
				sorted[i] = sorted[j];
				sorted[j] = ord;
			}
		}.sort(0, sorted.length);
	}

	/**
	 * Hand every ordinal whose folded value starts with the given bytes to
	 * the consumer, in the order of the folded values.
	 *
	 * @param prefix
	 *   the folded prefix; empty selects every value
	 * @param consumer
	 *   given each ordinal once
	 */
	void forEachStartingWith(BytesRef prefix, LongConsumer consumer) {
		var from = firstAtOrPast(prefix, false);
		var to = firstAtOrPast(prefix, true);

		for(var i = from; i < to; i++) {
			consumer.accept(sorted[i]);
		}
	}

	/**
	 * Hand every ordinal whose folded value the given automaton accepts to
	 * the consumer, in the order of the folded values.
	 *
	 * <p>The values are walked in sorted order, so the automaton is stepped
	 * over the bytes a value shares with the one before it once, and a value
	 * whose prefix the automaton has already refused is skipped together with
	 * every other value under that prefix. What a walk costs is then the
	 * number of distinct prefixes the automaton lets in, never the whole
	 * dictionary once the automaton is at all selective.
	 *
	 * @param automaton
	 *   what accepts a folded value, stepped over its UTF-8 bytes
	 * @param consumer
	 *   given each ordinal once
	 */
	void forEachAccepted(ByteRunAutomaton automaton, LongConsumer consumer) {
		// The automaton's state after each byte of the value before, so a shared prefix is stepped once
		var states = new int[16];
		var sharedWithPrevious = 0;

		var i = 0;
		while(i < sorted.length) {
			var ord = sorted[i];
			var start = starts[ord];
			var end = starts[ord + 1];
			var length = end - start;

			if(states.length < length + 1) {
				states = Arrays.copyOf(states, Math.max(length + 1, states.length * 2));
			}

			states[0] = 0;
			var state = states[sharedWithPrevious];
			var refusedAt = -1;
			for(var at = sharedWithPrevious; at < length; at++) {
				state = automaton.step(state, bytes[start + at] & 0xFF);
				if(state == -1) {
					refusedAt = at;
					break;
				}

				states[at + 1] = state;
			}

			if(refusedAt >= 0) {
				/*
				 * Every value under the refused prefix sorts next to this one,
				 * so the walk jumps past the run instead of refusing each.
				 */
				var refused = new BytesRef(bytes, start, refusedAt + 1);
				var next = firstAtOrPast(refused, true);
				sharedWithPrevious = next < sorted.length
					? sharedPrefix(ord, sorted[next], refusedAt)
					: 0;
				i = next;
				continue;
			}

			if(automaton.isAccept(state)) {
				consumer.accept(ord);
			}

			i++;
			if(i < sorted.length) {
				sharedWithPrevious = sharedPrefix(ord, sorted[i], length);
			}
		}
	}

	/**
	 * How many leading bytes two values share, at most the given number.
	 */
	private int sharedPrefix(int ord, int other, int atMost) {
		var start = starts[ord];
		var otherStart = starts[other];
		var length = Math.min(atMost, Math.min(starts[ord + 1] - start, starts[other + 1] - otherStart));

		var shared = 0;
		while(shared < length && bytes[start + shared] == bytes[otherStart + shared]) {
			shared++;
		}

		return shared;
	}

	/**
	 * Hand every ordinal whose folded value is exactly the given bytes to the
	 * consumer. Several ordinals fold to the same bytes when the field holds
	 * the same value in different spellings.
	 *
	 * @param folded
	 *   the folded value to find
	 * @param consumer
	 *   given each ordinal once
	 */
	void forEachEqualTo(BytesRef folded, LongConsumer consumer) {
		/*
		 * A value sorts before every longer value it is a prefix of, so the
		 * values equal to the bytes are the first of the run that starts with
		 * them.
		 */
		var from = firstAtOrPast(folded, false);
		for(var i = from; i < sorted.length; i++) {
			var start = starts[sorted[i]];
			var end = starts[sorted[i] + 1];
			if(end - start != folded.length) {
				break;
			}

			var compared = Arrays.compareUnsigned(
				bytes, start, end,
				folded.bytes, folded.offset, folded.offset + folded.length
			);
			if(compared != 0) {
				break;
			}

			consumer.accept(sorted[i]);
		}
	}

	/**
	 * Find the first position in the sorted order whose value does not sort
	 * before the prefix - or, past it, whose value neither sorts before it nor
	 * starts with it.
	 */
	private int firstAtOrPast(BytesRef prefix, boolean past) {
		var low = 0;
		var high = sorted.length;

		while(low < high) {
			var middle = (low + high) >>> 1;
			var start = starts[sorted[middle]];
			var end = starts[sorted[middle] + 1];

			// Compared over the length of the prefix alone: equal there means it starts with it
			var compared = Arrays.compareUnsigned(
				bytes, start, Math.min(end, start + prefix.length),
				prefix.bytes, prefix.offset, prefix.offset + prefix.length
			);

			if(compared < 0 || (past && compared == 0)) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}

		return low;
	}

	/**
	 * Estimate what this takes on the heap.
	 */
	long bytesHeld() {
		return 3 * 16L + bytes.length + 4L * starts.length + 4L * sorted.length;
	}
}
