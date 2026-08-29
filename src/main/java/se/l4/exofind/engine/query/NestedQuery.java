package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * Match documents where a single value of an object field satisfies all of the
 * clauses.
 *
 * The clauses run against one value at a time, so several conditions hold
 * inside the same value - a search for a red variant under 20 finds a product
 * with such a variant, not one whose variants are red and cheap in different
 * places. Fields are named by their dotted path, the same way they are outside
 * the clause. A text clause inside one covers the fields of the path when it
 * names none, and its words have to be found in a single value, for the same
 * reason the other clauses hold inside one.
 *
 * Anything that runs against a single value may sit inside - {@code field},
 * {@code text}, {@code knn}, {@code and}, {@code or}, {@code not} and
 * {@code boost}. A clause that only means something for the documents of the
 * index, such as another {@code nested} or a {@code fuse}, is refused.
 *
 * A {@link KnnQuery} inside the clause names a vector field of the path and
 * picks the {@code k} nearest values. {@code k} counts values, so a document
 * holding several of the nearest takes up several of them, and a search wanting
 * {@code k} documents asks for more than {@code k} values.
 *
 * The clause ranks the document when something inside it ranks, and
 * {@link #score()} says which of the values that matched decides - by default
 * the best of them, which is what makes a product as relevant as its most
 * relevant variant. Nothing inside it that scores leaves the document ranked
 * exactly as the rest of the search ranked it.
 *
 * @param path
 *   name of the object field, as it is called in the definition of the index
 * @param clauses
 *   what has to hold inside a single value, all of it
 * @param score
 *   how the values that matched decide what the document scores
 */
public record NestedQuery(
	String path,
	ImmutableList<Query> clauses,
	Score score
) implements Query {
	/**
	 * How the values that matched inside a document decide what it scores.
	 * Only means something when something inside the clause ranks.
	 */
	public enum Score {
		/**
		 * The best value decides, so a document is as relevant as the one value
		 * that answered the search best. What a search for a product through
		 * its variants usually wants.
		 */
		MAX,

		/**
		 * The worst value decides, for asking that a document is relevant
		 * throughout rather than in one place.
		 */
		MIN,

		/**
		 * The values that matched average out, so a document holding one good
		 * value among many poor ones ranks below one that is good throughout.
		 */
		AVG,

		/**
		 * The values that matched add up, so a document ranks by how much of it
		 * answered the search as well as by how well.
		 */
		TOTAL
	}

	public NestedQuery {
		if(clauses == null) {
			clauses = Lists.immutable.empty();
		}

		if(score == null) {
			score = Score.MAX;
		}
	}

	@Override
	public String type() {
		return "nested";
	}

	@Override
	public boolean scores() {
		return clauses.anySatisfy(Query::scores);
	}

	/**
	 * Get this clause with the values that matched deciding the score in the
	 * given way.
	 *
	 * @param score
	 * @return
	 */
	public NestedQuery withScore(Score score) {
		return new NestedQuery(path, clauses, score);
	}

	/**
	 * Match documents where a single value of the object field satisfies all
	 * of the given clauses.
	 *
	 * @param path
	 * @param clauses
	 * @return
	 */
	public static NestedQuery of(String path, Query... clauses) {
		return new NestedQuery(path, Lists.immutable.of(clauses), null);
	}

	/**
	 * Match documents where a single value of the object field satisfies all
	 * of the given clauses.
	 *
	 * @param path
	 * @param clauses
	 * @return
	 */
	public static NestedQuery of(String path, Iterable<? extends Query> clauses) {
		return new NestedQuery(path, Lists.immutable.<Query>ofAll(clauses), null);
	}
}
