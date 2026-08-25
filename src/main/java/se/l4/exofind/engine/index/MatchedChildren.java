package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.primitive.FloatLists;
import org.eclipse.collections.api.factory.primitive.IntLists;
import org.eclipse.collections.api.factory.primitive.IntObjectMaps;
import org.eclipse.collections.api.map.primitive.IntObjectMap;

/**
 * Finds which values of one object field matched, for the documents of a page
 * of results.
 *
 * A value is identified by its position among the values the document gave the
 * field, counted from zero - the same order the values sit in the copy of the
 * document, which is what a caller reads them back out of. The block a
 * document is written as holds the values of all of its object fields, so the
 * position is counted over the children of the one path rather than over the
 * block.
 *
 * The walk is a single forward pass per segment: one scorer over the matched
 * values, advanced into the block of each document of the page, with the
 * {@link FieldNames#NESTED} doc values telling the children of the path apart
 * from the children of the document's other object fields. Asking Lucene per
 * document would open a scorer per hit instead.
 */
final class MatchedChildren {
	private static final int[] NO_ORDINALS = new int[0];
	private static final float[] NO_SCORES = new float[0];

	private MatchedChildren() {
	}

	/**
	 * The values of one path that matched for one document.
	 *
	 * @param ordinals
	 *   position of each matched value among the document's values of the
	 *   path, in the order the document gave them. Every match is here - how
	 *   many to hand back is the caller's to cut
	 * @param scores
	 *   what each matched value scored, aligned with {@code ordinals} - empty
	 *   when the search was walked without scores
	 */
	record Matches(int[] ordinals, float[] scores) {
		int count() {
			return ordinals.length;
		}
	}

	/**
	 * Find the values of one path that matched, for each of the given
	 * documents.
	 *
	 * @param searcher
	 * @param values
	 *   the query matching the values, compiled by
	 *   {@link QueryCompiler#compileMatchedValues}
	 * @param scores
	 *   whether to keep what each value scored, matching how {@code values}
	 *   was compiled
	 * @param parents
	 *   the documents of the index per segment, which is what tells where the
	 *   block of each one starts
	 * @param path
	 *   name of the object field, for telling its children apart from the
	 *   children of other paths
	 * @param docIds
	 *   Lucene ids of the documents to answer for, in any order
	 * @return
	 *   the matches keyed by Lucene id. A document with no matching value has
	 *   no entry
	 * @throws IOException
	 */
	static IntObjectMap<Matches> find(
		IndexSearcher searcher,
		org.apache.lucene.search.Query values,
		boolean scores,
		BitSetProducer parents,
		String path,
		int[] docIds
	) throws IOException {
		var result = IntObjectMaps.mutable.<Matches>empty();
		if(docIds.length == 0) {
			return result;
		}

		var ordered = docIds.clone();
		Arrays.sort(ordered);

		var weight = searcher.createWeight(
			searcher.rewrite(values),
			scores ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES,
			1f
		);

		var pathTerm = new BytesRef(path);
		var position = 0;

		for(var context : searcher.getIndexReader().leaves()) {
			var leafEnd = context.docBase + context.reader().maxDoc();
			var from = position;
			while(position < ordered.length && ordered[position] < leafEnd) {
				position++;
			}

			if(from == position) {
				continue;
			}

			var supplier = weight.scorerSupplier(context);
			var parentBits = parents.getBitSet(context);
			var nested = context.reader().getSortedDocValues(FieldNames.NESTED);
			if(supplier == null || parentBits == null || nested == null) {
				// The segment holds no matching value, or no values at all
				continue;
			}

			var pathOrd = nested.lookupTerm(pathTerm);
			if(pathOrd < 0) {
				// The segment holds values of other paths only
				continue;
			}

			var scorer = supplier.get(Long.MAX_VALUE);
			var matches = scorer.iterator();
			var match = matches.nextDoc();

			var ordinals = IntLists.mutable.empty();
			var matchScores = FloatLists.mutable.empty();

			for(var i = from; i < position; i++) {
				var parent = ordered[i] - context.docBase;
				var blockStart = parent == 0 ? 0 : parentBits.prevSetBit(parent - 1) + 1;
				if(blockStart == parent) {
					// The document was written without children
					continue;
				}

				if(match != DocIdSetIterator.NO_MORE_DOCS && match < blockStart) {
					match = matches.advance(blockStart);
				}

				if(match == DocIdSetIterator.NO_MORE_DOCS || match >= parent) {
					continue;
				}

				/*
				 * The position of a match counts the children of the path in
				 * front of it, matched or not, so the doc values are walked
				 * over every child of the block rather than only the matches.
				 */
				if(nested.docID() < blockStart) {
					nested.advance(blockStart);
				}

				var ordinal = 0;
				ordinals.clear();
				matchScores.clear();

				while(match < parent) {
					while(nested.docID() < match) {
						if(nested.ordValue() == pathOrd) {
							ordinal++;
						}

						nested.nextDoc();
					}

					ordinals.add(ordinal);
					if(scores) {
						matchScores.add(scorer.score());
					}

					// The match itself is a child of the path
					ordinal++;
					nested.nextDoc();

					match = matches.nextDoc();
				}

				result.put(
					ordered[i],
					new Matches(
						ordinals.toArray(),
						scores ? matchScores.toArray() : NO_SCORES
					)
				);
			}
		}

		return result;
	}

	/**
	 * What a document with no matching value answers.
	 */
	static Matches none() {
		return new Matches(NO_ORDINALS, NO_SCORES);
	}

	/**
	 * Where one value of a path sits: the document it belongs to, and its
	 * position among that document's values of the path.
	 *
	 * @param parent
	 *   Lucene id of the document holding the value
	 * @param ordinal
	 *   position of the value among the document's values of the path, counted
	 *   from zero in the order the document gave them - the index into what the
	 *   copy of the document holds for the path
	 */
	record Location(int parent, int ordinal) {
	}

	/**
	 * Work out where each of the given values sits, for a page of hits that
	 * are the values themselves.
	 *
	 * The walk is the same single forward pass per segment {@link #find} makes,
	 * turned around: instead of starting from documents and finding their
	 * matching values, it starts from the values and finds the document above
	 * each one, counting the children of the path passed on the way to know
	 * the position.
	 *
	 * @param searcher
	 * @param parents
	 *   the documents of the index per segment, which is what tells where the
	 *   block of each one starts and ends
	 * @param path
	 *   name of the object field the values belong to, for telling its
	 *   children apart from the children of other paths
	 * @param docIds
	 *   Lucene ids of the values to answer for, in any order. Every one has to
	 *   be a value of the path
	 * @return
	 *   where each value sits, keyed by its Lucene id
	 * @throws IOException
	 */
	static IntObjectMap<Location> locate(
		IndexSearcher searcher,
		BitSetProducer parents,
		String path,
		int[] docIds
	) throws IOException {
		var result = IntObjectMaps.mutable.<Location>empty();
		if(docIds.length == 0) {
			return result;
		}

		var ordered = docIds.clone();
		Arrays.sort(ordered);

		var pathTerm = new BytesRef(path);
		var position = 0;

		for(var context : searcher.getIndexReader().leaves()) {
			var leafEnd = context.docBase + context.reader().maxDoc();
			var from = position;
			while(position < ordered.length && ordered[position] < leafEnd) {
				position++;
			}

			if(from == position) {
				continue;
			}

			var parentBits = parents.getBitSet(context);
			var nested = context.reader().getSortedDocValues(FieldNames.NESTED);
			if(parentBits == null || nested == null) {
				// Only a segment holding values could have answered them as hits
				continue;
			}

			var pathOrd = nested.lookupTerm(pathTerm);
			if(pathOrd < 0) {
				continue;
			}

			var blockStart = -1;
			var ordinal = 0;

			for(var i = from; i < position; i++) {
				var value = ordered[i] - context.docBase;
				var parent = parentBits.nextSetBit(value);

				/*
				 * The position of a value counts the children of the path in
				 * front of it, matched or not. Values are visited in order, so
				 * within one block the count carries over from the value before
				 * and only the stretch between the two is walked.
				 */
				var start = parent == 0 ? 0 : parentBits.prevSetBit(parent - 1) + 1;
				if(start != blockStart) {
					blockStart = start;
					ordinal = 0;

					if(nested.docID() < blockStart) {
						nested.advance(blockStart);
					}
				}

				while(nested.docID() < value) {
					if(nested.ordValue() == pathOrd) {
						ordinal++;
					}

					nested.nextDoc();
				}

				result.put(ordered[i], new Location(parent + context.docBase, ordinal));

				// The value itself is a child of the path
				ordinal++;
				nested.nextDoc();
			}
		}

		return result;
	}
}
