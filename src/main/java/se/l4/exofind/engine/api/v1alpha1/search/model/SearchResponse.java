package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import se.l4.exofind.engine.index.Document;

/**
 * What a search found, as it is answered over the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The results of a search. See [Response](https://levelfourab.github.io/exofind/reference/search-api/#response).")
public record SearchResponse(
	/**
	 * The results asked for, in the order they were asked for.
	 */
	@Schema(description = "The matching hits, in the order that `sort` asked for.")
	List<Hit> hits,

	/**
	 * How many documents matched in total.
	 */
	@Schema(description = "How many documents matched in total.")
	Total total,

	/**
	 * The counts per value the request asked for, keyed by the name of each
	 * facet. Present whenever facets were asked for, and left out entirely
	 * when they were not.
	 */
	@Schema(description = """
		Facet results keyed by facet name. Omitted entirely when the request \
		asked for no facets.""")
	Map<String, Facet> facets,

	/**
	 * Where in the results this window sits and how to move from it.
	 */
	@Schema(description = "Where in the results this window sits, and how to move from it.")
	Page page,

	/**
	 * What the search let go of to find anything, left out entirely when it
	 * found what was asked for.
	 */
	@Schema(description = """
		What the search let go of to find anything. Omitted entirely when the \
		query was not relaxed, so its presence always means the results answer \
		less than what was asked for.""")
	Relaxed relaxed,

	/**
	 * How long answering took, measured around the whole call, in
	 * milliseconds and fractions of one - a search that answers faster than a
	 * millisecond still reports what it spent.
	 */
	@Schema(
		description = """
			Execution time for the search request in milliseconds, including \
			fractions of one.""",
		examples = "7.412"
	)
	double tookMs
) {
	/**
	 * A single result. Usually a document that matched; for a search whose
	 * `hits` names an object field, one matched value of that field, with
	 * `index` and `value` present and the document it belongs to under
	 * `document`.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		One result: usually a document that matched, or - for a search whose \
		`hits` names an object field - one matched value of that field, with \
		`index` and `value` present and the document holding it under \
		`document`.""")
	public record Hit(
		/**
		 * The primary key of the document, left out for an index that has no
		 * primary key. A hit standing for a value carries the key of the
		 * document holding it, so several hits share an `id` whenever several
		 * values of one document matched - the identity of such a hit is `id`
		 * together with `index`, and deduping by `id` alone is a mistake.
		 */
		@Schema(
			description = """
				Primary key of the document, omitted on an index that has \
				none. A value hit carries the key of the document holding it, \
				so several hits share an `id` when several values of one \
				document matched - the identity of such a hit is `id` together \
				with `index`.""",
			examples = "9781234567890"
		)
		Object id,

		/**
		 * The position of the value this hit stands for in the field's value
		 * array as the document gave it, counted from zero. Present only when
		 * the search asked for value hits.
		 */
		@Schema(description = """
			Zero-based position of the value this hit stands for in the \
			parent document's value array. Present only when the search asked \
			for value hits.""")
		Integer index,

		/**
		 * How well the hit matched, left out when the search computed no
		 * scores rather than defaulted to something that looks like a value.
		 * A hit standing for a value scores what its document scored plus
		 * what the value itself scored under the `nested` clauses of its
		 * path.
		 */
		@Schema(
			description = """
				How well the hit matched. Omitted when the search computed no \
				scores, rather than defaulted to something that looks like a \
				value. A value hit scores what its document scored plus what \
				the value itself scored under the `nested` clauses of its \
				path.""",
			examples = "8.42"
		)
		Float score,

		/**
		 * The value this hit stands for, as it was given. Present only when
		 * the search asked for value hits, and left out on an index that
		 * keeps no copy of its documents.
		 */
		/*
		 * Typed as a free-form object rather than by the engine's Document,
		 * which is a list of named values on the inside and is written out by
		 * DocumentSerializer as an object keyed by field name. Left to the
		 * scanner the document would describe the inside rather than the wire.
		 */
		@Schema(
			type = SchemaType.OBJECT,
			implementation = Object.class,
			description = """
				The matched nested value, as it was indexed, keyed by field \
				name. Present only when the search asked for value hits, and \
				omitted on an index whose `source` is `none`."""
		)
		@JsonSerialize(using = DocumentSerializer.class)
		Document value,

		/**
		 * The fields that were asked for, as they were given. A field with
		 * several values is an array, and a locale specific field is an
		 * object keyed by locale tag. For a hit standing for a value, the
		 * fields of the document holding it.
		 */
		@Schema(
			type = SchemaType.OBJECT,
			implementation = Object.class,
			description = """
				The fields the request asked for, keyed by field name and \
				shaped as they were indexed. A field declared `multiple` is an \
				array, and a locale-specific field is an object holding the \
				single variant read for the query locale. For a value hit, the \
				fields of the document holding the value."""
		)
		@JsonSerialize(using = DocumentSerializer.class)
		Document document,

		/**
		 * The highlighted fragments of the fields the search asked to
		 * highlight, keyed by field name. Present whenever highlighting was
		 * asked for, with fields the document holds no match in left out -
		 * and left out entirely when it was not.
		 */
		@Schema(description = """
			Highlighted fragments keyed by field name. Present whenever \
			highlighting was asked for, leaving out fields the document holds \
			no match in - and omitted entirely when it was not.""")
		Map<String, List<String>> highlights,

		/**
		 * Which values of each object field the request asked about matched,
		 * keyed by field name. Present whenever matched values were asked
		 * for, with an entry per field asked about - and left out entirely
		 * when they were not.
		 */
		@Schema(description = """
			Matched nested values keyed by field name, one entry per field the \
			request asked about. Omitted entirely when the request asked for \
			none.""")
		Map<String, MatchedValues> matched
	) {
	}

	/**
	 * Which values of one object field matched for one hit.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "Which values of one nested object field matched, for one hit.")
	public record MatchedValues(
		/**
		 * The values that matched, as they were given - at most as many as
		 * the request asked for, best first when the clauses on the field
		 * rank and in the order the document gave them otherwise. Left out
		 * for an index that keeps no copy of its documents.
		 */
		@Schema(
			type = SchemaType.ARRAY,
			implementation = Object.class,
			description = """
				The values that matched, each keyed by field name, at most as \
				many as `limit` asked for. Ordered by score when scoring \
				clauses exist within the `nested` clause, and in document order \
				otherwise. Omitted on an index whose `source` is `none`."""
		)
		@JsonSerialize(contentUsing = DocumentSerializer.class)
		List<Document> values,

		/**
		 * How many values matched in all, which is more than the number under
		 * `values` whenever the limit was reached.
		 */
		@Schema(description = """
			How many values matched in all, which is more than the number \
			under `values` whenever the limit was reached.""")
		int totalValues
	) {
	}

	/**
	 * How many matches held each value of one faceted field, for building the
	 * list of filters a user picks from.
	 *
	 * The counts are sideways of the filters on the facet's own field: what
	 * ticking a value would leave, with the other values still visible. Every
	 * other filter and the whole query narrow the counts the way they narrow
	 * the hits.
	 *
	 * The shape follows how the facet asked: counting per value answers
	 * {@code values} with {@code totalValues}, counting into ranges answers
	 * {@code buckets}, with the other shape left out.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Match counts for one faceted field. Counting per value answers \
		`values` with `totalValues`; counting into ranges answers `buckets`, \
		with the other shape omitted. Counts are sideways of the filters on \
		the facet's own field - what ticking a value would leave, with the \
		other values still visible.""")
	public record Facet(
		/**
		 * The values with their counts, in the order the facet asked for and
		 * at most as many as its limit.
		 */
		@Schema(description = """
			The values with their counts, in the order the facet asked for and \
			at most as many as its limit.""")
		List<FacetValue> values,

		/**
		 * How many distinct values the matches held in all, which is more
		 * than the number of values whenever the limit was reached.
		 */
		@Schema(description = """
			How many distinct values the matches held in all, which is more \
			than the number under `values` whenever the limit was reached.""")
		Integer totalValues,

		/**
		 * The buckets with their counts, one per range the facet asked for
		 * and in the same order.
		 */
		@Schema(description = """
			The buckets with their counts, one per range the facet asked for \
			and in the same order.""")
		List<FacetBucket> buckets
	) {
	}

	/**
	 * One value of a faceted field with how many matches held it.
	 *
	 * A field whose values are paths through a tree answers one of these per
	 * level, and the levels below it under `values`. Every other field
	 * leaves the shape of a tree out entirely.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		One value of a faceted field with how many matches held it. A \
		hierarchical field answers one of these per level, nesting the levels \
		below it under `values`; every other field leaves the shape of a tree \
		out entirely.""")
	public record FacetValue(
		/**
		 * The value, in the shape the field returns it in results - a string,
		 * boolean or number, and an ISO 8601 instant for a timestamp field.
		 * One level of the path for a field whose values are paths, so it
		 * reads as a label.
		 */
		@Schema(
			description = """
				The value, in the shape the field returns it in results - a \
				string, boolean or number, and an ISO 8601 instant for a \
				timestamp field. For a hierarchical field, the label of one \
				level rather than the whole path.""",
			examples = "fiction"
		)
		Object value,

		/**
		 * How many matches held the value.
		 */
		@Schema(description = "How many matches held the value.", examples = "87")
		long count,

		/**
		 * The whole path down to this level, which is what a filter on the
		 * field takes. Left out for a value that is not part of a tree.
		 */
		@Schema(
			description = """
				The whole path down to this level, which is what an `under` \
				matcher takes. Omitted for a value that is not part of a \
				tree.""",
			examples = "Men/Shoes"
		)
		String path,

		/**
		 * The levels below this one with their own counts, as far down as the
		 * facet asked to count. Left out at the deepest level counted, and
		 * for a value that is not part of a tree.
		 */
		@Schema(description = """
			The levels below this one with their own counts, as far down as \
			`depth` asked to count. Omitted at the deepest level counted, and \
			for a value that is not part of a tree.""")
		List<FacetValue> values,

		/**
		 * How many levels below this one the matches held in all, which is
		 * more than the number under `values` whenever the limit was reached.
		 * Left out for a value that is not part of a tree.
		 */
		@Schema(description = """
			How many values below this level the matches held in all, which is \
			more than the number under `values` whenever the limit was \
			reached. Omitted for a value that is not part of a tree.""")
		Integer totalValues
	) {
		/**
		 * A value standing on its own, which is what every field but one
		 * holding paths counts.
		 */
		public FacetValue(Object value, long count) {
			this(value, count, null, null, null);
		}
	}

	/**
	 * One bucket of a faceted field with how many matches fell in it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "One range bucket of a faceted field with how many matches fell in it.")
	public record FacetBucket(
		/**
		 * The lower bound of the bucket as the request gave it, itself
		 * included. Left out for an open one.
		 */
		@Schema(
			description = """
				The lower bound as the request gave it, itself included. \
				Omitted for an open-ended bucket.""",
			examples = "100"
		)
		Object from,

		/**
		 * The upper bound of the bucket as the request gave it, itself not
		 * included. Left out for an open one.
		 */
		@Schema(
			description = """
				The upper bound as the request gave it, itself not included. \
				Omitted for an open-ended bucket.""",
			examples = "200"
		)
		Object to,

		/**
		 * How many matches held a value in the bucket.
		 */
		@Schema(description = "How many matches held a value in the bucket.", examples = "17")
		long count
	) {
	}

	/**
	 * What a search let go of to find anything.
	 *
	 * Present only when the search would otherwise have come back empty, so it
	 * always means the results answer less than what was typed - which is the
	 * whole reason it is here. A page that quietly ignored half of a search is
	 * worse than an empty one, because the person reading it believes it.
	 */
	@Schema(description = """
		What a search let go of to find anything. Present only when the search \
		would otherwise have come back empty, so it always means the results \
		answer less than what was typed. Total counts and facet counts reflect \
		the relaxed search.""")
	public record Relaxed(
		/**
		 * The words that were let go, in the order they were typed.
		 */
		@Schema(description = "The words that were dropped, in the order they were typed.")
		List<Dropped> dropped,

		/**
		 * The text the search ran with in the end, for showing what the
		 * results actually answer.
		 */
		@Schema(
			description = "The query text the search ran with in the end.",
			examples = "running shoes"
		)
		String text
	) {
		/**
		 * One word a search let go of, and why it went.
		 */
		@Schema(description = "One word a search dropped, and why it went.")
		public record Dropped(
			/**
			 * The word as it was typed.
			 */
			@Schema(description = "The word as it was typed.", examples = "waterproof")
			String word,

			/**
			 * What made it the one to go.
			 */
			@Schema(description = "What made it the one to go.")
			Reason reason
		) {
			/**
			 * Why a word was let go.
			 */
			@Schema(description = """
				Why a word was dropped: `unmatched` when nothing in the index \
				holds it, `common` when it is one of the words the most \
				documents hold.""")
			public enum Reason {
				/**
				 * Nothing in the index holds the word, so keeping it could
				 * only ever have found nothing - the index holds nothing of
				 * the kind rather than nothing matching the rest.
				 */
				@JsonProperty("unmatched")
				UNMATCHED,

				/**
				 * The word is one the most documents hold, so it said the
				 * least about what was wanted.
				 */
				@JsonProperty("common")
				COMMON
			}
		}
	}

	/**
	 * How many documents matched.
	 */
	@Schema(description = "How many documents matched.")
	public record Total(
		/**
		 * The number of documents that matched.
		 */
		@Schema(description = "The number of documents that matched.", examples = "128")
		long count,

		/**
		 * If the count is the whole number rather than at least that many.
		 */
		@Schema(description = """
			Whether `count` is the whole number rather than a lower bound. \
			Always true for a search that asked for `"total": "exact"` or for \
			facets.""")
		boolean exact
	) {
	}

	/**
	 * Where in the results this window sits.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "Where in the results this window sits, and how to move from it.")
	public record Page(
		/**
		 * How many results the window holds at most.
		 */
		@Schema(description = "How many results the window holds at most.", examples = "20")
		int limit,

		/**
		 * How many results come before the window, left out when the window
		 * was reached through {@code next} or {@code previous} - following
		 * a hit does not count what it skips, which is what lets those go
		 * deeper than an offset may.
		 */
		@Schema(description = """
			How many results come before the window. Omitted when the window \
			was reached through a `next` or `previous` cursor, which encodes a \
			position rather than a count - which is what lets cursors go \
			deeper than `EXOFIND_SEARCH_MAX_PAGE_DEPTH` allows an offset to.""")
		Integer offset,

		/**
		 * Cursor for the preceding window, left out when this is the first.
		 */
		@Schema(description = """
			Cursor for the preceding window, to send as `before`. Omitted when \
			this is the first window.""")
		String previous,

		/**
		 * Cursor for the next window, left out when there is nothing after
		 * this one.
		 */
		@Schema(description = """
			Cursor for the next window, to send as `after`. Omitted when there \
			is nothing after this one.""")
		String next,

		/**
		 * The numbered pages, present when the request asked for them.
		 */
		@Schema(description = "The numbered pages, present when the request asked for them.")
		Pages pages
	) {
	}

	/**
	 * Numbered pages, shaped so a client renders {@code 1 2 3 … 7} with the
	 * ellipses exactly where a window boundary falls - between {@code start}
	 * and {@code middle}, and between {@code middle} and {@code end},
	 * whenever the numbers are not adjacent.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Numbered pages, split into `start`, `middle` and `end` so a pager \
		renders `1 2 3 … 7` with the ellipses exactly where a window boundary \
		falls. Page numbers are 1-based, and the cursors inside encode count \
		offsets, so they remain subject to `EXOFIND_SEARCH_MAX_PAGE_DEPTH`.""")
	public record Pages(
		/**
		 * How many pages there are in total.
		 */
		@Schema(description = "How many pages there are in total.", examples = "7")
		long count,

		/**
		 * The page before the current one, left out on the first.
		 */
		@Schema(description = "The page before the current one, omitted on the first.")
		PageRef previous,

		/**
		 * The page after the current one, left out on the last - or when it
		 * would be deeper than paging goes.
		 */
		@Schema(description = """
			The page after the current one. Omitted on the last, and when it \
			would sit deeper than paging goes.""")
		PageRef next,

		/**
		 * The pages at the start of the list.
		 */
		@Schema(description = "The pages at the start of the list.")
		List<PageRef> start,

		/**
		 * The pages around the current one, when they do not touch either
		 * end.
		 */
		@Schema(description = """
			The pages around the current one, present when they touch neither \
			end of the list.""")
		List<PageRef> middle,

		/**
		 * The pages at the end of the list, left out when the last page is
		 * deeper than paging goes - so a pager never offers a jump that
		 * would be refused.
		 */
		@Schema(description = """
			The pages at the end of the list. Omitted when the final page \
			sits deeper than paging goes, so a pager never offers a jump that \
			would be refused.""")
		List<PageRef> end
	) {
	}

	/**
	 * One numbered page.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "One numbered page.")
	public record PageRef(
		/**
		 * The number of the page, counted from one.
		 */
		@Schema(description = "The number of the page, counted from one.", examples = "3")
		long number,

		/**
		 * Cursor that fetches the page.
		 */
		@Schema(description = "Cursor that fetches the page, to send as `after`.")
		String cursor,

		/**
		 * Set on the page the response is showing.
		 */
		@Schema(description = """
			Set on the page the response is showing, and omitted on every \
			other.""")
		Boolean current
	) {
	}
}
