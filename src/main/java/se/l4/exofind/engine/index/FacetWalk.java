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
 * resolving the document above each one. Only that resolution forces one
 * iteration to feed every facet; without it each leaf drains an iterator of
 * its own, through {@link FacetCount.Leaf#countAll}. What differs per facet,
 * the doc values it reads and what a count means, lives in each
 * {@link FacetCount.Leaf}.
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
		for(var docs : matches.hits().getMatchingDocs()) {
			totalMatches += docs.totalHits();
		}

		for(var facet : facets) {
			facet.begin(totalMatches);
		}

		for(var docs : matches.hits().getMatchingDocs()) {
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
				 * No document to resolve above a match, so the leaves share
				 * nothing: each drains an iterator of its own, and the bitset
				 * hands out iterators for the asking. One walk feeding every
				 * leaf per match would make each count a call the JIT cannot
				 * predict once a search counts a few kinds of facet.
				 *
				 * Matches collected as a bitset are handed over as one, which
				 * is what lets a leaf count a wide scope a value at a time
				 * instead of a match at a time.
				 */
				if(docs.bits().bits() instanceof FixedBitSet fixed) {
					for(var i = 0; i < count; i++) {
						leaves[i].countAll(fixed);
					}
				} else {
					leaves[0].countAll(iterator);
					for(var i = 1; i < count; i++) {
						leaves[i].countAll(docs.bits().iterator());
					}
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
