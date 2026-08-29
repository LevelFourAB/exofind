package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
	@JsonSubTypes.Type(value = Clause.Boost.class, name = "boost"),
	@JsonSubTypes.Type(value = Clause.Fuse.class, name = "fuse")
})
@Schema(description = """
	A search condition, structured as a tagged union where `type` selects the \
	clause type. If `type` is omitted, the clause defaults to a `field` clause \
	containing `field` and `match`. See \
	[Clauses](https://exofind.dev/reference/search-api/#clauses).""")
public sealed interface Clause
	permits Clause.Field, Clause.Text, Clause.Knn, Clause.Fuse, Clause.Nested, Clause.And,
		Clause.Or, Clause.Not, Clause.Boost {
	/**
	 * Match documents by what a single field holds.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "FieldClause",
		description = """
			Matches documents by the value of a single field. The targeted \
			field must be indexed for the requested matcher usage; if it is \
			not configured for that usage, the request returns \
			`index:query:usage_not_enabled`."""
	)
	record Field(
		/**
		 * Name of the field, as it is called in the definition of the index.
		 */
		@Schema(
			description = "Target field, as named in the index definition.",
			required = true,
			examples = "category"
		)
		String field,

		/**
		 * What to look for in it.
		 */
		@Schema(description = "Criteria evaluated against the field's values.", required = true)
		Matcher match
	) implements Clause {
	}

	/**
	 * Match text that someone typed, against several fields at once. Carries
	 * the same options a {@code text} matcher does, flattened into the
	 * clause, plus {@code combine} for what a match is complete within.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "TextClause",
		description = """
			Matches query text across one or more fields. Phrase queries \
			operate within a single field and match terms exactly as typed, \
			regardless of a field's `typoTolerance`. Fields defined only for \
			`autocomplete` do not support phrase matching. See \
			[`text`](https://exofind.dev/reference/search-api/#text)."""
	)
	record Text(
		/**
		 * What was typed.
		 */
		@Schema(
			description = "The query text to match.",
			required = true,
			examples = "silent spr"
		)
		String text,

		/**
		 * The fields to look in and how much each of them counts, left out
		 * for every field that can be matched on. A field mapped to
		 * {@code null} counts as much as its definition says.
		 */
		@Schema(description = """
			Object mapping field names to score weights. A field mapped to \
			`null` uses the weight from its field definition. If omitted, \
			searches all searchable fields, skipping autocomplete-only \
			fields.""")
		Map<String, Float> fields,

		/**
		 * How the words are combined, left out for {@code all}.
		 */
		@Schema(
			description = """
				Term matching mode. `phrase` requires terms to appear in exact \
				order and adjacent; `user` parses search syntax such as quotes \
				and negation.""",
			defaultValue = "all"
		)
		Matcher.Text.Match match,

		/**
		 * How the word still being typed is treated, left out for
		 * {@code last_token}.
		 */
		@Schema(
			description = """
				Prefix matching behavior on the final query term. \
				`last_token` matches the trailing word as a prefix; `off` \
				requires an exact word match.""",
			defaultValue = "last_token"
		)
		Matcher.Text.Prefix prefix,

		/**
		 * Whether words may contain typing mistakes, left out for
		 * {@code auto}.
		 */
		@Schema(
			description = """
				Typo tolerance handling. `auto` follows each field's \
				`typoTolerance` configuration; `off` disables typo tolerance \
				for the clause.""",
			defaultValue = "auto"
		)
		Matcher.Text.Typos typos,

		/**
		 * How many other words may sit between the words of a phrase, left
		 * out for none. Only means something for a {@code phrase} or the
		 * quoted parts of a {@code user} text.
		 */
		@Schema(
			description = """
				Number of intervening words permitted between terms in a \
				phrase, without changing their relative order. Setting `slop` \
				above `0` with `"match": "all"` or `"match": "any"` returns \
				`search:clause:slop_not_applicable`.""",
			defaultValue = "0"
		)
		Integer slop,

		/**
		 * What may be let go of rather than find nothing, left out for
		 * {@code unmatched}.
		 */
		@Schema(
			description = """
				Query relaxation strategy applied only when the query returns \
				zero matches. See [Finding something rather than \
				nothing](https://exofind.dev/reference/search-api/#finding-something-rather-than-nothing).""",
			defaultValue = "unmatched"
		)
		Matcher.Text.Relax relax,

		/**
		 * What a match is complete within, left out for {@code term}.
		 */
		@Schema(
			description = """
				Scope for multi-field term matching. `term` evaluates each \
				term across all targeted fields, so terms may appear in \
				different fields; `field` requires a single field to satisfy \
				`match` on its own. Ignored by phrase queries.""",
			defaultValue = "term"
		)
		Combine combine
	) implements Clause {
		/**
		 * What a match is complete within when several fields are searched.
		 */
		@Schema(description = """
			Scope a multi-field text match is complete within: `term` or \
			`field`.""")
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
	@Schema(
		name = "KnnClause",
		description = """
			Matches the `k` nearest documents by vector distance in a \
			specified field, scored by proximity. Cannot be combined with \
			`hits` (`search:hits:with_knn`)."""
	)
	record Knn(
		/**
		 * Name of the vector field, as it is called in the definition of the
		 * index.
		 */
		@Schema(
			description = "The vector field to search.",
			required = true,
			examples = "embedding"
		)
		String field,

		/**
		 * The vector to find the neighbours of, with the dimensions the field
		 * declares.
		 */
		@Schema(
			description = """
				The query vector. Its length must match the dimensions \
				declared in the field definition.""",
			required = true
		)
		float[] vector,

		/**
		 * How many neighbours to return.
		 */
		@Schema(description = "Number of nearest documents to return.", required = true)
		Integer k,

		/**
		 * Clauses narrowing which documents may be neighbours before the
		 * nearest are picked, all of which have to be satisfied.
		 */
		@Schema(description = """
			Clauses that documents must satisfy before nearest-neighbor \
			evaluation.""")
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
	@Schema(
		name = "NestedClause",
		description = """
			Matches documents where a single element of a `nested` \
			[object field](https://exofind.dev/reference/field-types/#object) \
			satisfies all child clauses. A `nested` clause on a flattened \
			object field returns `index:query:nested:flattened`; on a \
			non-object field it returns an error. See \
			[`nested`](https://exofind.dev/reference/search-api/#nested)."""
	)
	record Nested(
		/**
		 * Name of the object field, as it is called in the definition of the
		 * index.
		 */
		@Schema(
			description = "Name of the nested object field.",
			required = true,
			examples = "variants"
		)
		String path,

		/**
		 * What has to hold inside a single value, all of it, naming fields by
		 * their dotted path.
		 */
		@Schema(description = """
			Clauses evaluated within a single nested object value, naming \
			fields by their dotted path. An empty array matches any document \
			where the object field is present. May contain `field`, `text`, \
			`and`, `or`, `not` and `boost`; a root-level clause such as \
			another `nested` or a `knn` returns \
			`index:query:nested:unsupported_clause`.""")
		List<Clause> clauses,

		/**
		 * Which of the values that matched decides what the document scores,
		 * left out for {@code max}. Only means something when something
		 * inside the clause ranks.
		 */
		@Schema(
			description = """
				Scoring mode for aggregating matching nested values. Only \
				means something when something inside the clause ranks.""",
			defaultValue = "max"
		)
		Score score
	) implements Clause {
		/**
		 * How the values that matched inside a document decide what it
		 * scores.
		 */
		@Schema(description = """
			How the matching nested values of a document combine into its \
			score: `max`, `min`, `avg` or `total`.""")
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
	@Schema(name = "AndClause", description = "Matches documents where all child clauses match.")
	record And(
		@Schema(description = "Child clauses, all of which must match.", required = true)
		List<Clause> clauses
	) implements Clause {
	}

	/**
	 * Match documents that satisfy at least one of the clauses.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "OrClause",
		description = "Matches documents where at least one child clause matches."
	)
	record Or(
		@Schema(
			description = "Child clauses, at least one of which must match.",
			required = true
		)
		List<Clause> clauses
	) implements Clause {
	}

	/**
	 * Match documents that satisfy none of the clauses.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(name = "NotClause", description = "Matches documents where no child clause matches.")
	record Not(
		@Schema(description = "Child clauses, none of which may match.", required = true)
		List<Clause> clauses
	) implements Clause {
	}

	/**
	 * Rank documents that satisfy all of the clauses higher, without leaving
	 * out the ones that do not.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "BoostClause",
		description = """
			Increases the relevance score of documents that satisfy the child \
			clauses, without excluding documents that do not."""
	)
	record Boost(
		/**
		 * How much satisfying the clauses counts, relative to the rest of the
		 * query. Above one lifts, below one holds back.
		 */
		@Schema(
			description = """
				Multiplier applied to matching documents. Values greater than \
				`1` increase the score; values between `0` and `1` decrease \
				it.""",
			examples = "2"
		)
		Float weight,

		@Schema(
			description = "Clauses required to apply the boost weight.",
			required = true
		)
		List<Clause> clauses
	) implements Clause {
	}

	/**
	 * Match what several rankings found, scored by where each of them put a
	 * document rather than by what any of them scored. This is what combines
	 * a text ranking with a vector ranking without adding two scales that
	 * have nothing in common.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "FuseClause",
		description = """
			Runs several rankings separately and merges them with reciprocal \
			rank fusion, scoring each document by the sum of \
			`weight / (rankConstant + rank)` over the rankings that reached \
			it. Only the position a ranking gave a document is read, so \
			scores on different scales - such as BM25 and vector similarity - \
			combine without normalization. Matches only the `depth` best \
			results of each ranking. See \
			[`fuse`](https://exofind.dev/reference/search-api/#fuse)."""
	)
	record Fuse(
		/**
		 * The rankings to fuse, at least two.
		 */
		@Schema(
			description = """
				Rankings to run and merge. At least two are required; fewer \
				returns `search:clause:rankings_invalid`.""",
			required = true
		)
		List<Ranking> rankings,

		/**
		 * How far down each ranking is read, left out for 100. This is the
		 * whole of what the clause can match.
		 */
		@Schema(
			description = """
				Number of results read from each ranking. This bounds what \
				the clause can match and how deep paging reaches, the way `k` \
				bounds a `knn` clause. Must be at least `1`.""",
			defaultValue = "100"
		)
		Integer depth,

		/**
		 * How much the difference between neighbouring ranks counts, left out
		 * for 60.
		 */
		@Schema(
			description = """
				Constant added to each rank before it is inverted. Lower \
				values make the first results of a ranking count for much \
				more than the rest; higher values flatten the difference, so \
				being found by several rankings matters more than being found \
				first by one. Must be above `0`.""",
			defaultValue = "60"
		)
		Float rankConstant,

		/**
		 * Clauses narrowing every ranking before it is cut to depth.
		 */
		@Schema(description = """
			Clauses that narrow every ranking before it is cut to `depth`. A \
			`knn` inside a ranking takes these as its own pre-filter, so a \
			narrowed vector ranking still returns `k` results. Conditions \
			placed beside the `fuse` clause instead only narrow the merged \
			list afterwards.""")
		List<Clause> filter
	) implements Clause {
		/**
		 * One of the rankings being fused.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "FuseRanking",
			description = """
				One ranking of a fusion: what it searches for, and how much \
				where it placed a document counts."""
		)
		public record Ranking(
			/**
			 * What the ranking searches for, all of which has to be
			 * satisfied.
			 */
			@Schema(
				description = """
					Clauses the ranking searches for, combined with an \
					implicit `AND`. At least one is required.""",
				required = true
			)
			List<Clause> clauses,

			/**
			 * How much where this ranking put a document counts against the
			 * other rankings, left out for 1.
			 */
			@Schema(
				description = """
					Multiplier for what this ranking contributes. It cannot \
					reorder the ranking itself, only weigh it against the \
					others.""",
				defaultValue = "1"
			)
			Float weight
		) {
		}
	}
}
