package se.l4.exofind.engine.query;

import se.l4.exofind.engine.query.matchers.Matcher;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * A clause in a query, narrowing down or ranking the documents a search
 * returns.
 *
 * A search is a list of clauses, all of which have to be satisfied, and every
 * clause is one of a fixed set of kinds. Nesting is done with {@link AndQuery},
 * {@link OrQuery} and {@link NotQuery}, so any combination can be built out of
 * the same few pieces:
 *
 * <pre>
 * SearchRequest.create()
 *   .withQuery(
 *     Query.text("silent spr"),
 *     Query.field("published", Matchers.equalTo(true)),
 *     Query.or(
 *       Query.field("category", Matchers.equalTo("fiction")),
 *       Query.field("category", Matchers.equalTo("poetry"))
 *     )
 *   )
 *   .build()
 * </pre>
 *
 * Whether a clause takes part in ranking follows from what it is rather than
 * from where it is put, see {@link #scores()}. The type is the name a clause
 * goes by outside the engine, so it is never renamed or reused.
 */
public sealed interface Query
	permits FieldQuery, TextQuery, KnnQuery, FuseQuery, AndQuery, OrQuery, NotQuery, BoostQuery,
		NestedQuery {
	/**
	 * Get the unique identifier for this kind of clause.
	 *
	 * @return
	 */
	String type();

	/**
	 * Get if this clause has an opinion about the order results come back in.
	 *
	 * Looking a value up in a field is exact - a document either has it or it
	 * does not - so a field clause narrows the results without disturbing how
	 * they are ranked. Ticking a filter can then never reshuffle what is left.
	 * Text, vectors, boosts and fusions are the clauses that rank, and a clause
	 * that nests others ranks if anything inside it does.
	 *
	 * @return
	 */
	boolean scores();

	/**
	 * Match documents where a field satisfies the given matcher.
	 *
	 * @param field
	 *   name of the field, as it is called in the definition of the index
	 * @param matcher
	 *   what to look for in it
	 * @return
	 */
	static FieldQuery field(String field, Matcher matcher) {
		return new FieldQuery(field, matcher);
	}

	/**
	 * Match text that someone typed, against every field that can be matched
	 * on. Use {@link TextQuery#withField(String, float)} to search named fields
	 * instead, and to say how much each of them counts.
	 *
	 * @param text
	 * @return
	 */
	static TextQuery text(String text) {
		return TextQuery.of(text);
	}

	/**
	 * Match text that someone typed, matching it in the given way.
	 *
	 * @param matcher
	 * @return
	 */
	static TextQuery text(TextMatcher matcher) {
		return TextQuery.of(matcher);
	}

	/**
	 * Match the {@code k} documents whose vector in a field is nearest to the
	 * given one, scored by how near they are. Use
	 * {@link KnnQuery#withFilter(Query...)} to narrow which documents may be
	 * neighbours before the nearest are picked.
	 *
	 * @param field
	 *   name of the vector field, as it is called in the definition of the
	 *   index
	 * @param vector
	 *   the vector to find the neighbours of
	 * @param k
	 *   how many neighbours to return
	 * @return
	 */
	static KnnQuery knn(String field, float[] vector, int k) {
		return KnnQuery.of(field, vector, k);
	}

	/**
	 * Match what several rankings found, scored by where each of them put a
	 * document rather than by what any of them scored - which is how a text
	 * ranking and a vector ranking are combined without adding up two scales
	 * that have nothing in common. Use {@link FuseQuery#withFilter(Query...)}
	 * to narrow every ranking before it is cut to depth.
	 *
	 * @param rankings
	 *   the rankings to fuse, at least two - see
	 *   {@link FuseQuery#ranking(Query...)}
	 * @return
	 */
	static FuseQuery fuse(FuseQuery.Ranking... rankings) {
		return FuseQuery.of(rankings);
	}

	/**
	 * Match documents where a single value of an object field satisfies all of
	 * the given clauses - a condition on several fields of the same value,
	 * rather than on the document as a whole.
	 *
	 * The clause ranks the document when something inside it ranks, by the best
	 * of the values that matched unless
	 * {@link NestedQuery#withScore(NestedQuery.Score)} says otherwise.
	 *
	 * @param path
	 *   name of the object field, as it is called in the definition of the
	 *   index
	 * @param clauses
	 *   what has to hold inside a single value, naming fields by their dotted
	 *   path
	 * @return
	 */
	static NestedQuery nested(String path, Query... clauses) {
		return NestedQuery.of(path, clauses);
	}

	/**
	 * Match documents that satisfy all of the given clauses.
	 *
	 * @param clauses
	 * @return
	 */
	static AndQuery and(Query... clauses) {
		return AndQuery.of(clauses);
	}

	/**
	 * Match documents that satisfy at least one of the given clauses.
	 *
	 * @param clauses
	 * @return
	 */
	static OrQuery or(Query... clauses) {
		return OrQuery.of(clauses);
	}

	/**
	 * Match documents that satisfy none of the given clauses.
	 *
	 * @param clauses
	 * @return
	 */
	static NotQuery not(Query... clauses) {
		return NotQuery.of(clauses);
	}

	/**
	 * Rank documents that satisfy all of the given clauses higher, without
	 * leaving out the ones that do not. This is how a promotion or a rule such
	 * as showing what is in stock first is expressed.
	 *
	 * @param weight
	 *   how much to count the clauses for, relative to the rest of the query
	 * @param clauses
	 * @return
	 */
	static BoostQuery boost(float weight, Query... clauses) {
		return BoostQuery.of(weight, clauses);
	}
}
