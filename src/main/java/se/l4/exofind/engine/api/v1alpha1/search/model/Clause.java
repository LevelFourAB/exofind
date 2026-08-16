package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One clause of a search, as it is written on the wire.
 *
 * Clauses are a tagged union where {@code type} selects the kind, using the
 * {@link se.l4.exofind.engine.query.Query#type() type} the engine gives each
 * clause. A clause with no {@code type} is a field clause - it is the only
 * kind carrying {@code field} together with {@code match}, so leaving the tag
 * out of the common case is never ambiguous:
 *
 * <pre>
 * { "field": "published", "match": { "value": true } }
 * { "type": "text", "text": "silent spr", "fields": { "name": 3 } }
 * { "type": "or", "clauses": [ ... ] }
 * </pre>
 */
@JsonTypeInfo(
	use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "type",
	defaultImpl = Clause.Field.class
)
@JsonSubTypes({
	@JsonSubTypes.Type(value = Clause.Field.class, name = "field"),
	@JsonSubTypes.Type(value = Clause.Text.class, name = "text"),
	@JsonSubTypes.Type(value = Clause.Knn.class, name = "knn"),
	@JsonSubTypes.Type(value = Clause.Nested.class, name = "nested"),
	@JsonSubTypes.Type(value = Clause.And.class, name = "and"),
	@JsonSubTypes.Type(value = Clause.Or.class, name = "or"),
	@JsonSubTypes.Type(value = Clause.Not.class, name = "not"),
	@JsonSubTypes.Type(value = Clause.Boost.class, name = "boost")
})
public sealed interface Clause
	permits Clause.Field, Clause.Text, Clause.Knn, Clause.Nested, Clause.And, Clause.Or,
		Clause.Not, Clause.Boost {
	/**
	 * Match documents by what a single field holds.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Field(
		/**
		 * Name of the field, as it is called in the definition of the index.
		 */
		String field,

		/**
		 * What to look for in it.
		 */
		Matcher match
	) implements Clause {
	}

	/**
	 * Match text that someone typed, against several fields at once. Carries
	 * the same options a {@code text} matcher does, flattened into the
	 * clause, plus {@code combine} for what a match is complete within.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Text(
		/**
		 * What was typed.
		 */
		String text,

		/**
		 * The fields to look in and how much each of them counts, left out
		 * for every field that can be matched on. A field mapped to
		 * {@code null} counts as much as its definition says.
		 */
		Map<String, Float> fields,

		/**
		 * How the words are combined, left out for {@code all}.
		 */
		Matcher.Text.Match match,

		/**
		 * How the word still being typed is treated, left out for
		 * {@code last_token}.
		 */
		Matcher.Text.Prefix prefix,

		/**
		 * Whether words may contain typing mistakes, left out for
		 * {@code auto}.
		 */
		Matcher.Text.Typos typos,

		/**
		 * How many other words may sit between the words of a phrase, left
		 * out for none. Only means something for a {@code phrase} or the
		 * quoted parts of a {@code user} text.
		 */
		Integer slop,

		/**
		 * What may be let go of rather than find nothing, left out for
		 * {@code unmatched}.
		 */
		Matcher.Text.Relax relax,

		/**
		 * What a match is complete within, left out for {@code term}.
		 */
		Combine combine
	) implements Clause {
		/**
		 * What a match is complete within when several fields are searched.
		 */
		public enum Combine {
			/**
			 * Each word has to be found in some searched field - the words
			 * may sit in different fields of the same document.
			 */
			@JsonProperty("term")
			TERM,

			/**
			 * A single field has to satisfy {@code match} on its own, with a
			 * document ranked by the field it matched best.
			 */
			@JsonProperty("field")
			FIELD
		}
	}

	/**
	 * Match the {@code k} documents whose vector in a field is nearest to the
	 * given one, scored by how near they are.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Knn(
		/**
		 * Name of the vector field, as it is called in the definition of the
		 * index.
		 */
		String field,

		/**
		 * The vector to find the neighbours of, with the dimensions the field
		 * declares.
		 */
		float[] vector,

		/**
		 * How many neighbours to return.
		 */
		Integer k,

		/**
		 * Clauses narrowing which documents may be neighbours before the
		 * nearest are picked, all of which have to be satisfied.
		 */
		List<Clause> filter
	) implements Clause {
	}

	/**
	 * Match documents where a single value of an object field satisfies all
	 * of the clauses - a condition on several fields of the same value,
	 * rather than on the document as a whole. Anything that runs against a
	 * single value may sit inside: {@code field}, {@code text}, {@code and},
	 * {@code or}, {@code not} and {@code boost}.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Nested(
		/**
		 * Name of the object field, as it is called in the definition of the
		 * index.
		 */
		String path,

		/**
		 * What has to hold inside a single value, all of it, naming fields by
		 * their dotted path.
		 */
		List<Clause> clauses,

		/**
		 * Which of the values that matched decides what the document scores,
		 * left out for {@code max}. Only means something when something
		 * inside the clause ranks.
		 */
		Score score
	) implements Clause {
		/**
		 * How the values that matched inside a document decide what it
		 * scores.
		 */
		public enum Score {
			/**
			 * The best value decides, so a document is as relevant as the one
			 * value that answered the search best.
			 */
			@JsonProperty("max")
			MAX,

			/**
			 * The worst value decides, for asking that a document is relevant
			 * throughout rather than in one place.
			 */
			@JsonProperty("min")
			MIN,

			/**
			 * The values that matched average out.
			 */
			@JsonProperty("avg")
			AVG,

			/**
			 * The values that matched add up, so a document ranks by how much
			 * of it answered the search as well as by how well.
			 */
			@JsonProperty("total")
			TOTAL
		}
	}

	/**
	 * Match documents that satisfy all of the clauses.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record And(
		List<Clause> clauses
	) implements Clause {
	}

	/**
	 * Match documents that satisfy at least one of the clauses.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Or(
		List<Clause> clauses
	) implements Clause {
	}

	/**
	 * Match documents that satisfy none of the clauses.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Not(
		List<Clause> clauses
	) implements Clause {
	}

	/**
	 * Rank documents that satisfy all of the clauses higher, without leaving
	 * out the ones that do not.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Boost(
		/**
		 * How much satisfying the clauses counts, relative to the rest of the
		 * query. Above one lifts, below one holds back.
		 */
		Float weight,

		List<Clause> clauses
	) implements Clause {
	}
}
