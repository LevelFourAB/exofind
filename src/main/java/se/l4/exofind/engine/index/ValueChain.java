package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.util.BitSet;
import org.eclipse.collections.api.list.ImmutableList;

import se.l4.exofind.engine.query.ValueTarget;

/**
 * The steps of a {@link ValueTarget} resolved against the index, for reading
 * the values that stand for each document of it.
 *
 * <p>A document is read on the first step it holds any value on, and on
 * nothing after it. Where a step reads a field inside a {@code nested} list,
 * its values are the ones the step's clauses hold for, read off the block of
 * values below the document - see {@link NestedDocuments}. Where it reads a
 * field of the index, its value is the one on the document itself, and its
 * clauses have to hold for the document.
 *
 * <p>Built by {@link QueryCompiler}, which resolves the names and compiles
 * the clauses. What is kept here is only what reading a segment needs: the
 * Lucene field each step reads and what finds the Lucene documents that may
 * hold its values.
 *
 * @param documents
 *   finds the documents of the index in a segment. The block of values of a
 *   document starts after the document before it
 * @param steps
 *   the steps, in the order they are tried - never empty
 */
record ValueChain(
	BitSetProducer documents,
	ImmutableList<Step> steps
) {
	/**
	 * One step of a chain.
	 *
	 * @param field
	 *   the Lucene field the values of the step were written under, as sorted
	 *   numeric doc values or as single numeric doc values
	 * @param holders
	 *   the Lucene documents whose values count: the values of the path the
	 *   clauses of the step hold for, for a step inside a list, or the
	 *   documents of the index they hold for otherwise. {@code null} for a
	 *   step outside a list with no clauses, where every document counts
	 * @param nested
	 *   whether the values sit in the block below each document. A step
	 *   outside a list reads the document itself
	 */
	record Step(
		String field,
		BitSetProducer holders,
		boolean nested
	) {
		Step {
			Objects.requireNonNull(field);

			if(nested && holders == null) {
				throw new IllegalArgumentException("A step inside a list needs what finds its values");
			}
		}
	}

	ValueChain {
		Objects.requireNonNull(documents);

		if(steps == null || steps.isEmpty()) {
			throw new IllegalArgumentException("A chain needs at least one step");
		}
	}

	/**
	 * Start reading the values of one segment.
	 *
	 * @param context
	 *   the segment
	 * @return
	 *   the values, or {@code null} when the segment holds no document of the
	 *   index
	 * @throws IOException
	 */
	Values values(LeafReaderContext context) throws IOException {
		var parents = documents.getBitSet(context);
		if(parents == null) {
			return null;
		}

		var reader = context.reader();
		var values = new SortedNumericDocValues[steps.size()];
		var holders = new BitSet[steps.size()];
		var read = new boolean[steps.size()];
		var nested = new boolean[steps.size()];

		for(var i = 0; i < steps.size(); i++) {
			var step = steps.get(i);

			/*
			 * Every step reads its own copy of the doc values, even where two
			 * steps read the same field: a step walks the block of each
			 * document from its start, and doc values only move forwards.
			 */
			values[i] = DocValues.getSortedNumeric(reader, step.field());
			nested[i] = step.nested();

			if(step.holders() == null) {
				read[i] = true;
			} else {
				holders[i] = step.holders().getBitSet(context);

				// Nothing in the segment satisfies the clauses of the step
				read[i] = holders[i] != null;
			}
		}

		return new Values(parents, values, holders, read, nested);
	}

	/**
	 * The values of a chain in one segment, read a document at a time.
	 *
	 * <p>Documents have to be asked for in increasing order, the way doc
	 * values are read. Asking for the same document again answers what it
	 * answered the first time, which a comparator that reads the same
	 * document to copy it and to compare it relies on.
	 */
	static final class Values {
		private final BitSet documents;
		private final SortedNumericDocValues[] values;
		private final BitSet[] holders;
		private final boolean[] read;
		private final boolean[] nested;

		private long[] found;
		private int count;
		private int document;

		private Values(
			BitSet documents,
			SortedNumericDocValues[] values,
			BitSet[] holders,
			boolean[] read,
			boolean[] nested
		) {
			this.documents = documents;
			this.values = values;
			this.holders = holders;
			this.read = read;
			this.nested = nested;

			this.found = new long[4];
			this.document = -1;
		}

		/**
		 * Get the documents of the index in this segment.
		 *
		 * @return
		 */
		BitSet documents() {
			return documents;
		}

		/**
		 * Move to a document and read the values that stand for it.
		 *
		 * @param document
		 *   a document of the index, at or after the one asked for before
		 * @return
		 *   whether any step holds a value for the document
		 * @throws IOException
		 */
		boolean advanceExact(int document) throws IOException {
			if(document == this.document) {
				return count > 0;
			}

			this.document = document;
			count = 0;

			for(var i = 0; i < values.length; i++) {
				if(!read[i]) {
					continue;
				}

				if(nested[i]) {
					readValues(i, document);
				} else if(holders[i] == null || holders[i].get(document)) {
					readValue(i, document);
				}

				if(count > 0) {
					return true;
				}
			}

			return false;
		}

		/**
		 * Get how many values stand for the document last moved to.
		 *
		 * @return
		 */
		int count() {
			return count;
		}

		/**
		 * Get one of the values that stand for the document last moved to, as
		 * the doc values hold it.
		 *
		 * @param index
		 *   which of them, below {@link #count()}
		 * @return
		 */
		long value(int index) {
			return found[index];
		}

		/**
		 * Read every value of a step inside a list, off the block of values
		 * below the document.
		 */
		private void readValues(int step, int document) throws IOException {
			var blockStart = document == 0 ? 0 : documents.prevSetBit(document - 1) + 1;
			if(blockStart >= document) {
				// The document was written without values
				return;
			}

			var stepHolders = holders[step];
			var stepValues = values[step];

			for(
				var value = stepHolders.nextSetBit(blockStart);
				value < document;
				value = value + 1 < stepHolders.length()
					? stepHolders.nextSetBit(value + 1)
					: DocIdSetIterator.NO_MORE_DOCS
			) {
				if(stepValues.advanceExact(value)) {
					add(stepValues);
				}
			}
		}

		/**
		 * Read the value of a step outside a list, off the document itself.
		 */
		private void readValue(int step, int document) throws IOException {
			var stepValues = values[step];
			if(stepValues.advanceExact(document)) {
				add(stepValues);
			}
		}

		private void add(SortedNumericDocValues from) throws IOException {
			var size = from.docValueCount();
			if(count + size > found.length) {
				found = Arrays.copyOf(found, Math.max(found.length * 2, count + size));
			}

			for(var i = 0; i < size; i++) {
				found[count++] = from.nextValue();
			}
		}
	}
}
