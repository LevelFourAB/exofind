package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A search as it is received over the API.
 *
 * Everything is optional - an empty request matches every document and brings
 * back the first few of them. The clause list is an implicit AND, so a search
 * box together with the scope it searches in is a flat list rather than a
 * nested tree. The refinements a user ticks go in {@code filters} instead,
 * which is what lets a facet count sideways of the filter on its own field:
 *
 * <pre>
 * {
 *   "query": [
 *     { "type": "text", "text": "silent spr", "fields": { "name": 3 } },
 *     { "field": "published", "match": { "value": true } }
 *   ],
 *   "filters": [
 *     { "field": "category", "match": { "type": "in", "values": ["fiction", "poetry"] } }
 *   ],
 *   "facets": [ { "field": "category" } ],
 *   "sort": [ { "type": "score" }, { "field": "name", "order": "asc" } ],
 *   "fields": ["name", "price"],
 *   "limit": 20
 * }
 * </pre>
 *
 * Where in the results the answer starts is said with {@code limit} together
 * with at most one of {@code offset}, {@code after} and {@code before}. The
 * cursors are the opaque tokens a previous response handed out - {@code after}
 * continues past the window it came from, {@code before} is the window
 * preceding it, and hits always come back in sort order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchRequest(
	/**
	 * The clauses a document has to satisfy, all of them. Left out to match
	 * every document. A clause here narrows every facet count - scope a
	 * search here, tick filters in {@code filters}.
	 */
	List<Clause> query,

	/**
	 * The ticked refinements of a filtering UI, each narrowing the results
	 * the way a {@code query} clause does. Kept apart from the query because
	 * facets are counted sideways of them: a facet leaves the entries it
	 * excludes - by default the ones on its own field - out of its counts,
	 * so ticking a category still shows what the other categories would
	 * hold.
	 *
	 * Only {@code field} and {@code nested} clauses may sit here - a
	 * condition on a field inside an object is a {@code nested} clause
	 * naming it - and no entry may rank, so ticking a filter never
	 * reshuffles the results. Exclusion is per entry: send one entry per
	 * facet field, several ticked values through one matcher.
	 */
	List<Clause> filters,

	/**
	 * What to count the matches per value of, left out for no counting. The
	 * response keys each facet's counts by its name.
	 */
	List<Facet> facets,

	/**
	 * The order results come back in, left out for the best matches first.
	 */
	List<Sort> sort,

	/**
	 * The locale the search reads locale specific fields in (BCP-47), left
	 * out to leave every field to its own default locale.
	 */
	String locale,

	/**
	 * The fields to bring back with each result, left out for every stored
	 * field. The primary key is always included. A field inside an object is
	 * named by its dotted path and comes back inside the object, which then
	 * holds only the fields that were asked for.
	 */
	List<String> fields,

	/**
	 * Ask for highlighted fragments with each hit, left out for none.
	 */
	Highlight highlight,

	/**
	 * Ask each hit which values of an object field matched, left out for
	 * none.
	 */
	Matched matched,

	/**
	 * Change what a hit stands for: each matched value of an object field
	 * becomes a hit of its own, instead of the document holding it. Left out
	 * for hits that are documents. See {@link Hits}.
	 */
	Hits hits,

	/**
	 * How many results to return. Zero returns how many there are without
	 * returning any of them.
	 */
	Integer limit,

	/**
	 * How many results to skip before the ones being returned.
	 */
	Integer offset,

	/**
	 * Cursor to continue after, from the {@code next} of a previous response.
	 */
	String after,

	/**
	 * Cursor to read the window preceding, from the {@code previous} of a
	 * previous response.
	 */
	String before,

	/**
	 * Ask for numbered pages in the response, so a pager can be rendered.
	 * Being present is what asks, and implies {@code total} being
	 * {@code exact} - pages can not be numbered against a lower bound.
	 */
	Pages pages,

	/**
	 * How far the total is counted, left out for {@code estimate}.
	 */
	Total total,

	/**
	 * The values of the documents themselves to take into their relevance,
	 * left out to rank by the ones the index declares. Given, they replace
	 * those - an empty list ranks by how well documents match alone. Only read
	 * where relevance is the ordering, so a search that gives a {@code sort}
	 * of its own is unaffected.
	 */
	List<Signal> signals
) {
	/**
	 * How far the total of a search is counted.
	 */
	public enum Total {
		/**
		 * Stop counting once it is known there are more matches than the
		 * search brings back, leaving the count a lower bound.
		 */
		@JsonProperty("estimate")
		ESTIMATE,

		/**
		 * Count every match, however many there are.
		 */
		@JsonProperty("exact")
		EXACT
	}

	/**
	 * A request to count the matches per value of one field, for building the
	 * list of filters a user picks from - or into buckets, when {@code ranges}
	 * is given.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Facet(
		/**
		 * What the counts are keyed by in the response. Left out to key them
		 * by the field - only needed when one search counts the same field
		 * twice.
		 */
		String name,

		/**
		 * Name of the field to count, as it is called in the definition of
		 * the index. The field has to be defined for faceting.
		 */
		String field,

		/**
		 * How many values to bring back at most, left out for 10. Capped at
		 * 1000. Does not combine with {@code ranges}.
		 */
		Integer limit,

		/**
		 * The order values come back in, left out for {@code count}. Does not
		 * combine with {@code ranges}.
		 */
		Order order,

		/**
		 * The buckets to count the matches into instead of per value - what a
		 * price or date facet shows. Being present is what asks for it; the
		 * counts come back one per bucket, in this order.
		 */
		List<Range> ranges,

		/**
		 * The level of the tree to count the children of, left out to count
		 * from the top. Only a field whose values are read as paths can
		 * answer it, and the value to send is the `path` of a level a
		 * previous response answered with.
		 */
		String path,

		/**
		 * How many levels below `path` to count, left out for one. At most
		 * 10, and `limit` and `order` apply per level.
		 */
		Integer depth,

		/**
		 * The field paths whose filter entries are left out of this facet's
		 * counts - an entry is left out when the path it names equals one of
		 * these or falls under it. Left out for the facet's own field, which
		 * is the sideways rule a filtering UI wants. An empty list leaves
		 * nothing out, so the counts are exactly the results; more paths
		 * widen the scope, for one control backed by several fields.
		 */
		List<String> excludeFilters
	) {
		/**
		 * The order the values of a facet come back in.
		 */
		public enum Order {
			/**
			 * The most common values first.
			 */
			@JsonProperty("count")
			COUNT,

			/**
			 * Ascending by the value itself.
			 */
			@JsonProperty("value")
			VALUE
		}

		/**
		 * One bucket, holding the values from {@code from} up to but not
		 * including {@code to} - so adjacent buckets sharing a bound count no
		 * value twice. At least one bound has to be given.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Range(
			/**
			 * The lowest value the bucket holds, itself included. Left out
			 * for no lower end.
			 */
			Object from,

			/**
			 * Where the bucket ends, itself not included. Left out for no
			 * upper end.
			 */
			Object to
		) {
		}
	}

	/**
	 * How numbered pages are asked for.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Pages(
		/**
		 * How many page entries the response may hold at most, left out for
		 * a window of nine.
		 */
		Integer max
	) {
	}

	/**
	 * What to highlight. Fragments are built from the text part of the
	 * search - what only narrows the results is never highlighted.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Highlight(
		/**
		 * The fields to return fragments for, keyed by the name a field has
		 * in the definition of the index. An empty options object asks for
		 * the defaults. A field that was not defined for highlighting is
		 * refused.
		 */
		Map<String, HighlightField> fields
	) {
	}

	/**
	 * What to ask about matched values. The values are the ones the `nested`
	 * clauses every result had to satisfy asked for - the same values a sort
	 * or a facet on the field reads. A search that asked nothing of the
	 * values matched all of them.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Matched(
		/**
		 * The object fields to answer for, keyed by the name a field has in
		 * the definition of the index. An empty options object asks for the
		 * defaults. A field that is not a `nested` object is refused.
		 */
		Map<String, MatchedField> fields
	) {
	}

	/**
	 * What a hit stands for, when it is not a document.
	 *
	 * With a path given, every matched value of that object field is a hit of
	 * its own: the total counts values, facets count value hits, and the
	 * cursors move through values. The query keeps its meaning - clauses on
	 * the fields of the index still say which documents take part, and
	 * `nested` clauses on the path still say which of their values matched.
	 *
	 * A search whose hits are values can not also ask for `matched` or
	 * `highlight`, hold a `knn` clause, or sort by distance or by a field of
	 * the index - it is ordered by score or by fields inside the path.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Hits(
		/**
		 * Name of the object field whose matched values are the hits, as it
		 * is called in the definition of the index. The field has to be an
		 * object in `nested` mode.
		 */
		String path,

		/**
		 * The fields of each value to bring back, named by their dotted
		 * paths, left out for all of them. A name that is not inside the
		 * object, or any name on an index that keeps no copy of its
		 * documents, is refused.
		 */
		List<String> fields
	) {
	}

	/**
	 * How the matched values of one field come back.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MatchedField(
		/**
		 * How many values to bring back at most, between 1 and 100. Left out
		 * for three. How many matched in all always comes back beside them.
		 */
		Integer limit,

		/**
		 * The fields of each value to bring back, named by their dotted
		 * paths, left out for all of them. A name that is not inside the
		 * object, or any name on an index that keeps no copy of its
		 * documents, is refused.
		 */
		List<String> fields
	) {
	}

	/**
	 * How to build the fragments of one field.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record HighlightField(
		/**
		 * How many fragments to return at most, left out for three.
		 */
		Integer fragments,

		/**
		 * How long a fragment aims to be in characters, left out for 150.
		 * Text shorter than this comes back as a single fragment holding all
		 * of it.
		 */
		Integer length,

		/**
		 * What to put in front of each match, left out for {@code <em>}. May
		 * be empty.
		 */
		String pre,

		/**
		 * What to put after each match, left out for {@code </em>}. May be
		 * empty.
		 */
		String post
	) {
	}
}
