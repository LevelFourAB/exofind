package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ConstantScoreScorerSupplier;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.util.BitSetIterator;

/**
 * The products of an index whose variants are documents of their own, read off
 * the set the block join already keeps per segment.
 *
 * <p>The same query the engine runs, so that the layout it uses today is
 * measured as it is rather than as the naive form of itself - saying which
 * documents are products by naming them, rather than by excluding the variants
 * and leaving a search to score the stretches between them.
 */
final class ParentsFilter extends Query {
	private final BitSetProducer parents;

	ParentsFilter(BitSetProducer parents) {
		this.parents = parents;
	}

	@Override
	public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
		return new ConstantScoreWeight(this, boost) {
			@Override
			public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
				var documents = parents.getBitSet(context);
				if(documents == null) {
					return null;
				}

				return ConstantScoreScorerSupplier.fromIterator(
					new BitSetIterator(documents, documents.approximateCardinality()),
					score(),
					scoreMode,
					context.reader().maxDoc()
				);
			}

			@Override
			public boolean isCacheable(LeafReaderContext ctx) {
				return false;
			}
		};
	}

	@Override
	public void visit(QueryVisitor visitor) {
		visitor.visitLeaf(this);
	}

	@Override
	public boolean equals(Object obj) {
		return sameClassAs(obj) && parents.equals(((ParentsFilter) obj).parents);
	}

	@Override
	public int hashCode() {
		return classHash() * 31 + parents.hashCode();
	}

	@Override
	public String toString(String field) {
		return "ParentsFilter(" + parents + ")";
	}
}
