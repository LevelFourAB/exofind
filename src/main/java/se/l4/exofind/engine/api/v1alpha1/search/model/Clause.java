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
 * <p>Clauses are a tagged union where {@code type} selects the kind, using the
 * {@link se.l4.exofind.engine.query.Query#type() type} the engine gives each
 * clause. A clause with no {@code type} defaults to a field clause containing
 * {@code field} together with {@code match}:
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
	 * Matches documents by the value of a single field.
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
		 * Target field, as named in the index definition.
		 */
		@Schema(
			description = "Target field, as named in the index definition.",
			required = true,
			examples = "category"
		)
		String field,

		/**
		 * Criteria evaluated against the field's values.
		 */
		@Schema(description = "Criteria evaluated against the field's values.", required = true)
		Matcher match
	) implements Clause {
	}

	/**
	 * Matches query text across one or more fields. Accepts the same options as
	 * a {@code text} matcher, plus {@code combine} for multi-field term
	 * matching.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "TextClause",
		description = """
			Matches query text across one or more fields. Phrase queries \
			operate within a single field and match terms exactly as typed, \
			regardless of field `typoTolerance`. Fields defined only for \
			`autocomplete` do not support phrase matching. See \
			[`text`](https://exofind.dev/reference/search-api/#text)."""
	)
	record Text(
		/**
		 * The query text to match.
		 */
		@Schema(
			description = "The query text to match.",
			required = true,
			examples = "silent spr"
		)
		String text,

		/**
		 * Object mapping field names to score weights. A field mapped to
		 * {@code null} uses the weight from its field definition. If omitted,
		 * searches all searchable fields.
		 */
		@Schema(description = """
			Object mapping field names to score weights. A field mapped to \
			`null` uses the weight from its field definition. If omitted, \
			searches all searchable fields, skipping autocomplete-only \
			fields.""")
		Map<String, Float> fields,

		/**
		 * Term matching mode. Defaults to {@code all}.
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
		 * Prefix matching behavior on the final query term. Defaults to
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
		 * Typo tolerance handling. Defaults to {@code auto}.
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
		 * Number of intervening words permitted between terms in a phrase. Only
		 * applies to {@code phrase} queries or quoted phrases in {@code user}
		 * mode. Defaults to none.
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
		 * Query relaxation strategy when no documents match. Defaults to
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
		 * Scope for multi-field term matching. Defaults to {@code term}.
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
		 * Scope for multi-field term matching across multiple fields.
		 */
		@Schema(description = """
			Scope for multi-field term matching: `term` or `field`.""")
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
	 * Matches the {@code k} nearest documents by vector distance in a specified
	 * field, scored by proximity.
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
		 * The vector field to search, as named in the index definition.
		 */
		@Schema(
			description = "The vector field to search.",
			required = true,
			examples = "embedding"
		)
		String field,

		/**
		 * The query vector, matching the dimensions declared in the field
		 * definition.
		 */
		@Schema(
			description = """
				The query vector. Its length must match the dimensions \
				declared in the field definition.""",
			required = true
		)
		float[] vector,

		/**
		 * Number of nearest documents to return.
		 */
		@Schema(
			description = """
				Number of nearest documents to return, at most \
				`EXOFIND_SEARCH_MAX_KNN_K`.""",
			required = true
		)
		Integer k,

		/**
		 * Clauses that documents must satisfy before nearest-neighbor
		 * evaluation.
		 */
		@Schema(description = """
			Clauses that documents must satisfy before nearest-neighbor \
			evaluation.""")
		List<Clause> filter
	) implements Clause {
	}

	/**
	 * Matches documents where a single value of an object field satisfies all
	 * child clauses. Evaluates conditions against a single nested value using
	 * child clauses: {@code field}, {@code text}, {@code and}, {@code or},
	 * {@code not}, and {@code boost}.
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
		 * Name of the nested object field, as named in the index definition.
		 */
		@Schema(
			description = "Name of the nested object field.",
			required = true,
			examples = "variants"
		)
		String path,

		/**
		 * Clauses evaluated within a single nested object value, naming fields
		 * by their dotted path.
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
		 * Scoring mode for aggregating matching nested values, defaulting to
		 * {@code max}. Only applies when child clauses score results.
		 */
		@Schema(
			description = """
				Scoring mode for aggregating matching nested values. Only \
				applies when scoring clauses exist within the nested clause.""",
			defaultValue = "max"
		)
		Score score
	) implements Clause {
		/**
		 * Scoring mode for aggregating matching nested values into a document
		 * score.
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
	 * Matches documents where all child clauses match.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(name = "AndClause", description = "Matches documents where all child clauses match.")
	record And(
		@Schema(description = "Child clauses, all of which must match.", required = true)
		List<Clause> clauses
	) implements Clause {
	}

	/**
	 * Matches documents where at least one child clause matches.
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
	 * Matches documents where no child clause matches.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(name = "NotClause", description = "Matches documents where no child clause matches.")
	record Not(
		@Schema(description = "Child clauses, none of which may match.", required = true)
		List<Clause> clauses
	) implements Clause {
	}

	/**
	 * Increases the relevance score of documents that satisfy child clauses
	 * without excluding non-matching documents.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "BoostClause",
		description = """
			Increases the relevance score of documents that satisfy child \
			clauses without excluding non-matching documents."""
	)
	record Boost(
		/**
		 * Multiplier applied to matching documents. Values greater than 1
		 * increase score; values between 0 and 1 decrease score.
		 */
		@Schema(
			description = """
				Multiplier applied to matching documents. Values greater than \
				`1` increase score; values between `0` and `1` decrease score.""",
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
	 * Matches documents across several rankings, scored and merged by rank.
	 *
	 * <p><p>Because the clause reads only result positions, scores from
	 * different scales combine without normalization.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "FuseClause",
		description = """
			Matches documents across several rankings, scored and merged by \
			rank. Documents are scored by the sum of `weight / (rankConstant + \
			rank)` across the rankings that reached them. Because the clause \
			reads only result positions, scores from different scales (such as \
			BM25 text relevance and vector similarity) combine without \
			normalization. Matches at most `depth` results per ranking. See \
			[`fuse`](https://exofind.dev/reference/search-api/#fuse)."""
	)
	record Fuse(
		/**
		 * Rankings to run and merge. At least two rankings are required.
		 */
		@Schema(
			description = """
				Rankings to run and merge. Specifying fewer than two rankings \
				returns `search:clause:rankings_invalid`.""",
			required = true
		)
		List<Ranking> rankings,

		/**
		 * Number of results read from each ranking. Defaults to 100.
		 */
		@Schema(
			description = """
				Number of results read from each ranking. Pagination cannot \
				exceed the merged list, similar to `k` in a `knn` clause. Must \
				be at least `1`, and at most \
				`EXOFIND_SEARCH_MAX_FUSE_DEPTH`.""",
			defaultValue = "100"
		)
		Integer depth,

		/**
		 * Constant added to each rank before it is inverted. Defaults to 60.
		 */
		@Schema(
			description = """
				Constant added to each rank before it is inverted. Lower \
				values increase the weight of the highest-ranked results in \
				each ranking; higher values flatten the difference across \
				ranks, giving more weight to documents found by multiple \
				rankings. Must be above `0`.""",
			defaultValue = "60"
		)
		Float rankConstant,

		/**
		 * Clauses that narrow every ranking before it is cut to depth.
		 */
		@Schema(description = """
			Clauses that narrow every ranking before it is cut to `depth`. A \
			`knn` clause inside a ranking applies `filter` entries as a \
			pre-filter, ensuring the vector ranking returns `k` results. \
			Clauses placed beside the `fuse` clause filter the merged list \
			after each ranking is cut to `depth`.""")
		List<Clause> filter
	) implements Clause {
		/**
		 * One ranking to run and merge.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "FuseRanking",
			description = """
				One ranking of a fusion: search clauses to evaluate and the \
				ranking's relative weight."""
		)
		public record Ranking(
			/**
			 * Clauses the ranking searches for, combined with an implicit AND.
			 * At least one clause is required.
			 */
			@Schema(
				description = """
					Clauses the ranking searches for, combined with an \
					implicit `AND`. At least one clause is required.""",
				required = true
			)
			List<Clause> clauses,

			/**
			 * Multiplier that scales this ranking's contribution relative to
			 * other rankings. Defaults to 1.
			 */
			@Schema(
				description = """
					Multiplier that scales the ranking's contribution relative \
					to other rankings. It cannot reorder results within the \
					ranking.""",
				defaultValue = "1"
			)
			Float weight
		) {
		}
	}
}
