package se.l4.exofind.engine.index;

import java.io.IOException;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;
import org.eclipse.collections.api.list.ListIterable;

/**
 * Walks the matches of one scope once and feeds every facet counting that
 * scope.
 *
 * The walk owns what every facet of a scope shares: the collected matches of
 * each segment, and - where the matches are values of an object field -
 * resolving the document above each one. What differs per facet, the doc
 * values it reads and what a count means, lives in each
 * {@link FacetCount.Leaf}.
 *
 * The matches of a segment are read once and handed to the leaves in batches,
 * through {@link FacetCount.Leaf#countAll(int[], int)}. A batch keeps both
 * halves of that cheap. The call into a leaf cannot be predicted once a search
 * counts several kinds of facet, and a batch pays it once for a thousand
 * matches; the loop inside the leaf reads an array and the JIT can inline what
 * it calls. Matches collected as a bitset are offered whole first, through
 * {@link FacetCount.Leaf#countWhole(FixedBitSet)}, so a leaf can count a wide
 * scope a value at a time; a leaf that takes them that way is left out of the
 * batches.
 *
 * The values of one document sit together and end just before it, so the
 * document a value belongs to is the next one at or after it, and walking the
 * matches in order visits a document's values one block at a time. That is
 * what lets a leaf hold per-document state and be told once, through
 * {@link FacetCount.Leaf#beginDocument}, when the document changes. Read the
 * other way, the values of a document are the block right after the document
 * before it - see {@link #valuesFrom} - which is how a leaf fed documents
 * finds every value they hold.
 */
final class FacetWalk {
	/**
	 * How many matches one batch holds. Large enough that the call into each
	 * leaf is paid once per thousand matches or so, and small enough that the
	 * batch stays in the innermost cache beside the columns the leaves read.
	 */
	private static final int BATCH = 1024;

	private FacetWalk() {
	}

	/**
	 * Walk the matches of the scope and feed every facet.
	 *
	 * @param matches
	 *   the scope: what matched, and what finds the document above a value
	 * @param facets
	 *   the facets counting this scope
	 * @throws IOException
	 */
	static void walk(FacetMatches matches, ListIterable<FacetCount> facets)
		throws IOException
	{
		var totalMatches = 0;
		for(var docs : matches.hits()) {
			totalMatches += docs.totalHits();
		}

		for(var facet : facets) {
			facet.begin(totalMatches);
		}

		// One buffer for the whole walk, filled again per batch
		int[] batch = null;

		for(var docs : matches.hits()) {
			var context = docs.context();
			var iterator = docs.bits() == null ? null : docs.bits().iterator();
			if(iterator == null) {
				continue;
			}

			BitSet parents = null;
			if(matches.resolvesDocuments()) {
				parents = matches.parents().getBitSet(context);
				if(parents == null) {
					continue;
				}
			}

			var leaves = new FacetCount.Leaf[facets.size()];
			var count = 0;
			for(var facet : facets) {
				var leaf = facet.leaf(context, docs.totalHits());
				if(leaf != null) {
					leaves[count++] = leaf;
				}
			}

			if(count == 0) {
				continue;
			}

			if(parents == null) {
				/*
				 * No document to resolve above a match, so the matches go to
				 * the leaves as they are read, a batch at a time. Matches
				 * collected as a bitset are offered whole first: a leaf that
				 * counts a wide scope a value at a time takes them there and
				 * is left out of the batches.
				 */
				var batched = leaves;
				var batchedCount = count;

				if(docs.bits().bits() instanceof FixedBitSet fixed) {
					batched = new FacetCount.Leaf[count];
					batchedCount = 0;

					for(var i = 0; i < count; i++) {
						if(!leaves[i].countWhole(fixed)) {
							batched[batchedCount++] = leaves[i];
						}
					}

					if(batchedCount > 0) {
						if(batch == null) {
							batch = new int[BATCH];
						}

						countInBatches(fixed, batch, batched, batchedCount);
					}
				} else {
					if(batch == null) {
						batch = new int[BATCH];
					}

					countInBatches(iterator, batch, batched, batchedCount);
				}
			} else {
				var document = -1;

				for(
					var doc = iterator.nextDoc();
					doc != DocIdSetIterator.NO_MORE_DOCS;
					doc = iterator.nextDoc()
				) {
					if(doc > document) {
						document = doc < parents.length()
							? parents.nextSetBit(doc)
							: DocIdSetIterator.NO_MORE_DOCS;

						// A value with no document after it answers for nobody
						if(document == DocIdSetIterator.NO_MORE_DOCS) {
							break;
						}

						for(var i = 0; i < count; i++) {
							leaves[i].beginDocument(document);
						}
					}

					for(var i = 0; i < count; i++) {
						leaves[i].count(doc);
					}
				}
			}

			for(var i = 0; i < count; i++) {
				leaves[i].finish();
			}
		}
	}

	/**
	 * Read the set bits of a bitset into batches and count each batch with
	 * every leaf.
	 *
	 * The words are scanned directly rather than through an iterator, so that
	 * reading a match costs one instruction over the word it sits in.
	 *
	 * @param matches
	 *   the matches of the segment
	 * @param batch
	 *   the buffer to fill, at least {@link Long#SIZE} long
	 * @param leaves
	 *   the leaves to count with, in the first {@code count} entries
	 * @param count
	 *   how many leaves there are
	 * @throws IOException
	 */
	private static void countInBatches(
		FixedBitSet matches,
		int[] batch,
		FacetCount.Leaf[] leaves,
		int count
	) throws IOException {
		var words = matches.getBits();
		var length = 0;

		for(int word = 0, end = FixedBitSet.bits2words(matches.length()); word < end; word++) {
			// A word holds at most Long.SIZE matches, so the batch is emptied ahead of it
			if(batch.length - length < Long.SIZE) {
				countBatch(batch, length, leaves, count);
				length = 0;
			}

			var base = word << 6;
			for(var bits = words[word]; bits != 0; bits &= bits - 1) {
				batch[length++] = base + Long.numberOfTrailingZeros(bits);
			}
		}

		if(length > 0) {
			countBatch(batch, length, leaves, count);
		}
	}

	/**
	 * Read the matches of an iterator into batches and count each batch with
	 * every leaf.
	 *
	 * @param matches
	 *   the matches of the segment, in document order
	 * @param batch
	 *   the buffer to fill
	 * @param leaves
	 *   the leaves to count with, in the first {@code count} entries
	 * @param count
	 *   how many leaves there are
	 * @throws IOException
	 */
	private static void countInBatches(
		DocIdSetIterator matches,
		int[] batch,
		FacetCount.Leaf[] leaves,
		int count
	) throws IOException {
		var length = 0;

		for(
			var doc = matches.nextDoc();
			doc != DocIdSetIterator.NO_MORE_DOCS;
			doc = matches.nextDoc()
		) {
			batch[length++] = doc;

			if(length == batch.length) {
				countBatch(batch, length, leaves, count);
				length = 0;
			}
		}

		if(length > 0) {
			countBatch(batch, length, leaves, count);
		}
	}

	/**
	 * Count one batch of matches with every leaf.
	 */
	private static void countBatch(
		int[] batch,
		int length,
		FacetCount.Leaf[] leaves,
		int count
	) throws IOException {
		for(var i = 0; i < count; i++) {
			leaves[i].countAll(batch, length);
		}
	}

	/**
	 * Get where the values of a document start: right after the document
	 * before it, or at the start of the segment for the first document. The
	 * values run from there up to but not including the document itself.
	 *
	 * @param documents
	 *   the documents of the index in the segment
	 * @param document
	 *   one of them
	 * @return
	 */
	static int valuesFrom(BitSet documents, int document) {
		return document == 0 ? 0 : documents.prevSetBit(document - 1) + 1;
	}
}
