package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Matches;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;

/**
 * The wrapped query, carrying which clause of a request it was compiled from.
 *
 * <p>What matches and what it scores is entirely the wrapped query's. The one
 * addition is a node in every {@link Explanation} this produces, holding the
 * path and the type of the clause, which {@link Explanations} reads back off
 * the tree.
 *
 * <p>Only {@link Index#explain} compiles with these in place. Wrapping is
 * opaque to Lucene, so a query holding them rewrites less than the same query
 * without them.
 */
final class ClauseQuery extends Query {
	/**
	 * What the description of a marking node starts with. Never seen by a
	 * caller - {@link Explanations} takes it off the tree.
	 */
	static final String MARK = "exofind:clause:";

	private final Query in;
	private final String path;
	private final String type;

	/**
	 * @param in
	 *   the compiled clause
	 * @param path
	 *   where the clause sits in the request, such as
	 *   {@code query[0].clauses[2]}
	 * @param type
	 *   the kind of clause, as {@link se.l4.exofind.engine.query.Query#type()}
	 *   names it
	 */
	ClauseQuery(Query in, String path, String type) {
		this.in = Objects.requireNonNull(in);
		this.path = Objects.requireNonNull(path);
		this.type = Objects.requireNonNull(type);
	}

	/**
	 * Read the clause a description marks.
	 *
	 * @param description
	 * @return
	 *   the mark, or {@code null} when the description carries none
	 */
	static Mark markOf(String description) {
		if(description == null || !description.startsWith(MARK)) {
			return null;
		}

		var rest = description.substring(MARK.length());
		var separator = rest.lastIndexOf(':');
		if(separator < 0) {
			return null;
		}

		return new Mark(rest.substring(0, separator), rest.substring(separator + 1));
	}

	/**
	 * Where a clause sits in the request and what kind it is.
	 */
	record Mark(String path, String type) {
	}

	@Override
	public Query rewrite(IndexSearcher searcher) throws IOException {
		var rewritten = in.rewrite(searcher);
		if(rewritten == in) {
			return this;
		}

		return new ClauseQuery(rewritten, path, type);
	}

	@Override
	public void visit(QueryVisitor visitor) {
		in.visit(visitor.getSubVisitor(BooleanClause.Occur.MUST, this));
	}

	@Override
	public Weight createWeight(
		IndexSearcher searcher,
		ScoreMode scoreMode,
		float boost
	) throws IOException {
		var innerWeight = in.createWeight(searcher, scoreMode, boost);

		return new Weight(this) {
			@Override
			public boolean isCacheable(LeafReaderContext ctx) {
				return innerWeight.isCacheable(ctx);
			}

			@Override
			public int count(LeafReaderContext context) throws IOException {
				return innerWeight.count(context);
			}

			@Override
			public Matches matches(LeafReaderContext context, int doc) throws IOException {
				return innerWeight.matches(context, doc);
			}

			@Override
			public Explanation explain(LeafReaderContext context, int doc) throws IOException {
				var inner = innerWeight.explain(context, doc);
				var description = MARK + path + ":" + type;

				// Marked whether or not it matched: which clause left a document
				// out is what an explanation of a missing document is asked for
				return inner.isMatch()
					? Explanation.match(inner.getValue(), description, inner)
					: Explanation.noMatch(description, inner);
			}

			@Override
			public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
				return innerWeight.scorerSupplier(context);
			}
		};
	}

	@Override
	public String toString(String field) {
		return in.toString(field);
	}

	@Override
	public boolean equals(Object other) {
		return sameClassAs(other)
			&& in.equals(((ClauseQuery) other).in)
			&& path.equals(((ClauseQuery) other).path)
			&& type.equals(((ClauseQuery) other).type);
	}

	@Override
	public int hashCode() {
		return Objects.hash(classHash(), in, path, type);
	}
}
