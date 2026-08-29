package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;
import org.eclipse.collections.api.factory.primitive.IntObjectMaps;

/**
 * Several rankings run on their own and merged by where each of them placed a
 * document - reciprocal rank fusion.
 *
 * <p>Each ranking is searched down to {@code depth} results. A document is
 * scored by the sum of {@code weight / (rankConstant + rank)} over the rankings
 * that reached it, counting the first result of a ranking as rank one. Only the
 * position a ranking gave a document is read, so a BM25 ranking and a vector
 * ranking combine without either score being normalized into the other.
 *
 * <p>The rankings run while the query is rewritten, the way a nearest-neighbour
 * query runs its graph search there. What is left is the documents they found
 * with the score the merge gave them, so paging, facets, counting and a second
 * pass all read an ordinary scoring query over a bounded set of documents. Each
 * pass that rewrites the query runs the rankings again, so a fused search with
 * facets searches more than once - the cost a search holding a {@code knn}
 * clause already pays.
 *
 * @see se.l4.exofind.engine.query.FuseQuery
 */
final class FusionQuery extends Query {
	private final Query[] rankings;
	private final float[] weights;
	private final int depth;
	private final float rankConstant;

	/**
	 * @param rankings
	 *   the compiled rankings, each already narrowed by whatever the clause
	 *   filters on
	 * @param weights
	 *   how much where each ranking put a document counts, one per ranking
	 * @param depth
	 *   how far down each ranking is read
	 * @param rankConstant
	 *   how much the difference between neighbouring ranks counts
	 */
	FusionQuery(Query[] rankings, float[] weights, int depth, float rankConstant) {
		if(rankings.length != weights.length) {
			throw new IllegalArgumentException("Every ranking of a fusion carries a weight");
		}

		this.rankings = rankings;
		this.weights = weights;
		this.depth = depth;
		this.rankConstant = rankConstant;
	}

	@Override
	public Query rewrite(IndexSearcher searcher) throws IOException {
		/*
		 * Where each ranking placed a document, kept per document so that a
		 * document several rankings found is one entry holding all of their
		 * ranks. A zero is a ranking that never reached it, ranks being
		 * counted from one.
		 */
		var placements = IntObjectMaps.mutable.<int[]>empty();

		for(var i = 0; i < rankings.length; i++) {
			var found = searcher.search(rankings[i], depth);

			for(var rank = 0; rank < found.scoreDocs.length; rank++) {
				var doc = found.scoreDocs[rank].doc;
				var placement = placements.get(doc);
				if(placement == null) {
					placement = new int[rankings.length];
					placements.put(doc, placement);
				}

				placement[i] = rank + 1;
			}
		}

		if(placements.isEmpty()) {
			return new MatchNoDocsQuery("no ranking of the fusion found anything");
		}

		/*
		 * Sorted, because what the merge produces is read back as a query over
		 * documents - which are visited in the order the index holds them.
		 */
		var docs = placements.keySet().toSortedArray();
		var scores = new float[docs.length];
		var placed = new int[docs.length * rankings.length];
		var maxScore = 0f;

		for(var i = 0; i < docs.length; i++) {
			var placement = placements.get(docs[i]);
			System.arraycopy(placement, 0, placed, i * rankings.length, rankings.length);

			var score = 0f;
			for(var ranking = 0; ranking < rankings.length; ranking++) {
				if(placement[ranking] > 0) {
					score += weights[ranking] / (rankConstant + placement[ranking]);
				}
			}

			scores[i] = score;
			maxScore = Math.max(maxScore, score);
		}

		return new FusedQuery(
			docs,
			scores,
			placed,
			weights,
			rankConstant,
			maxScore,
			searcher.getIndexReader().getContext().id()
		);
	}

	@Override
	public void visit(QueryVisitor visitor) {
		/*
		 * A document is a result when any one ranking found it, and the terms
		 * the rankings look for are what highlighting reads off a compiled
		 * search - so every ranking is visited as one of several ways to match.
		 */
		var sub = visitor.getSubVisitor(BooleanClause.Occur.SHOULD, this);
		for(var ranking : rankings) {
			ranking.visit(sub);
		}
	}

	@Override
	public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
		throws IOException
	{
		/*
		 * Reached only when something ran the query without rewriting it
		 * first, which is not how Lucene runs a query. The rankings have not
		 * been merged at this point and there is nothing to score by.
		 */
		throw new IllegalStateException("A fusion has to be rewritten before it is run");
	}

	@Override
	public String toString(String field) {
		var description = new StringBuilder("fuse(depth=")
			.append(depth)
			.append(", rankConstant=")
			.append(rankConstant);

		for(var i = 0; i < rankings.length; i++) {
			description.append(", ")
				.append(rankings[i].toString(field))
				.append('^')
				.append(weights[i]);
		}

		return description.append(')').toString();
	}

	@Override
	public boolean equals(Object other) {
		if(!sameClassAs(other)) {
			return false;
		}

		var o = (FusionQuery) other;
		return depth == o.depth
			&& Float.compare(rankConstant, o.rankConstant) == 0
			&& Arrays.equals(rankings, o.rankings)
			&& Arrays.equals(weights, o.weights);
	}

	@Override
	public int hashCode() {
		return Objects.hash(
			classHash(),
			depth,
			rankConstant,
			Arrays.hashCode(rankings),
			Arrays.hashCode(weights)
		);
	}

	/**
	 * What the rankings merged into: the documents they found, in the order
	 * the index holds them, each with the score the merge gave it.
	 *
	 * <p>The documents are Lucene ids over the reader the merge ran against, so
	 * this query only means anything there. The reader it was built for is
	 * carried along and checked before the query runs.
	 */
	private static final class FusedQuery extends Query {
		private final int[] docs;
		private final float[] scores;

		/**
		 * Where each ranking placed each document, {@code rankings} entries per
		 * document in the order the documents are held. Read by explanations
		 * alone - the score is already worked out.
		 */
		private final int[] placed;

		private final float[] weights;
		private final float rankConstant;
		private final float maxScore;
		private final Object readerId;

		FusedQuery(
			int[] docs,
			float[] scores,
			int[] placed,
			float[] weights,
			float rankConstant,
			float maxScore,
			Object readerId
		) {
			this.docs = docs;
			this.scores = scores;
			this.placed = placed;
			this.weights = weights;
			this.rankConstant = rankConstant;
			this.maxScore = maxScore;
			this.readerId = readerId;
		}

		@Override
		public void visit(QueryVisitor visitor) {
			visitor.visitLeaf(this);
		}

		@Override
		public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
			throws IOException
		{
			if(searcher.getIndexReader().getContext().id() != readerId) {
				throw new IllegalStateException(
					"A fusion names documents of the reader it was merged against, and can not be run on another"
				);
			}

			return new Weight(this) {
				@Override
				public boolean isCacheable(LeafReaderContext context) {
					/*
					 * The whole of this query is a list of documents already in
					 * hand, so caching it would spend memory to save the walk
					 * over that list.
					 */
					return false;
				}

				@Override
				public int count(LeafReaderContext context) {
					return to(context) - from(context);
				}

				@Override
				public Explanation explain(LeafReaderContext context, int doc) {
					var found = Arrays.binarySearch(docs, context.docBase + doc);
					if(found < 0) {
						return Explanation.noMatch(
							"no ranking of the fusion reached this document"
						);
					}

					var details = new Explanation[weights.length];
					for(var i = 0; i < weights.length; i++) {
						var rank = placed[found * weights.length + i];

						details[i] = rank == 0
							? Explanation.noMatch("ranking " + i + " did not reach this document")
							: Explanation.match(
								weights[i] / (rankConstant + rank),
								"ranking " + i + " placed this document at rank " + rank
									+ ", counted as " + weights[i] + " / (" + rankConstant
									+ " + " + rank + ")"
							);
					}

					return Explanation.match(
						scores[found] * boost,
						"sum of the reciprocal ranks of:",
						details
					);
				}

				@Override
				public ScorerSupplier scorerSupplier(LeafReaderContext context) {
					var from = from(context);
					var to = to(context);
					if(from == to) {
						return null;
					}

					return new ScorerSupplier() {
						@Override
						public Scorer get(long leadCost) {
							return new FusedScorer(
								docs,
								scores,
								from,
								to,
								context.docBase,
								boost,
								maxScore * boost
							);
						}

						@Override
						public long cost() {
							return to - from;
						}
					};
				}
			};
		}

		/**
		 * Where the documents of a segment start in the merged list.
		 */
		private int from(LeafReaderContext context) {
			return insertionPoint(context.docBase);
		}

		/**
		 * Where the documents of a segment end in the merged list.
		 */
		private int to(LeafReaderContext context) {
			return insertionPoint(context.docBase + context.reader().maxDoc());
		}

		/**
		 * Where a document id would be inserted into the merged list, which is
		 * where the documents at or above it begin whether or not it is there
		 * itself.
		 */
		private int insertionPoint(int doc) {
			var found = Arrays.binarySearch(docs, doc);
			return found < 0 ? -1 - found : found;
		}

		@Override
		public String toString(String field) {
			return "fused(" + docs.length + " documents)";
		}

		@Override
		public boolean equals(Object other) {
			if(!sameClassAs(other)) {
				return false;
			}

			var o = (FusedQuery) other;
			return readerId == o.readerId
				&& Arrays.equals(docs, o.docs)
				&& Arrays.equals(scores, o.scores);
		}

		@Override
		public int hashCode() {
			return Objects.hash(classHash(), readerId, Arrays.hashCode(docs));
		}
	}

	/**
	 * Walks the documents of one segment in the merged list, scoring each of
	 * them with what the merge worked out.
	 */
	private static final class FusedScorer extends Scorer {
		private final int[] docs;
		private final float[] scores;
		private final int from;
		private final int to;
		private final int docBase;
		private final float boost;
		private final float maxScore;
		private final DocIdSetIterator iterator;

		/**
		 * Where the walk stands in the merged list, one before the start until
		 * it is moved for the first time.
		 */
		private int at;

		FusedScorer(
			int[] docs,
			float[] scores,
			int from,
			int to,
			int docBase,
			float boost,
			float maxScore
		) {
			this.docs = docs;
			this.scores = scores;
			this.from = from;
			this.to = to;
			this.docBase = docBase;
			this.boost = boost;
			this.maxScore = maxScore;
			this.at = from - 1;

			this.iterator = new DocIdSetIterator() {
				@Override
				public int docID() {
					return FusedScorer.this.docID();
				}

				@Override
				public int nextDoc() {
					at = Math.min(at + 1, to);
					return docID();
				}

				@Override
				public int advance(int target) {
					var start = Math.min(Math.max(at + 1, from), to);
					var found = Arrays.binarySearch(docs, start, to, target + docBase);
					at = found < 0 ? -1 - found : found;

					return docID();
				}

				@Override
				public long cost() {
					return to - from;
				}
			};
		}

		@Override
		public int docID() {
			if(at < from) {
				return -1;
			}

			return at >= to ? DocIdSetIterator.NO_MORE_DOCS : docs[at] - docBase;
		}

		@Override
		public float score() {
			return scores[at] * boost;
		}

		@Override
		public float getMaxScore(int upTo) {
			return maxScore;
		}

		@Override
		public DocIdSetIterator iterator() {
			return iterator;
		}
	}
}
