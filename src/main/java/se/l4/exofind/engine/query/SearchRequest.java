package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.set.ImmutableSet;

/**
 * What to search an index for, and how much of the answer to bring back.
 *
 * <pre>
 * var request = SearchRequest.create()
 *   .withQuery(
 *     Query.text("silent spr").withField("name", 3).withField("description"),
 *     Query.field("published", Matchers.equalTo(true))
 *   )
 *   .withSort(SortBy.score(), SortBy.field("name"))
 *   .withLimit(20)
 *   .build();
 * </pre>
 *
 * @param query
 *   the clauses a document has to satisfy, all of them. Empty matches every
 *   document, which is what an unfiltered listing is
 * @param filters
 *   the ticked refinements of a filtering UI, kept apart from the query
 *   because facets are counted sideways of them - a facet leaves the filters
 *   on its own field out of its counts, while a clause in the query narrows
 *   every count. Hits have to satisfy filters and query alike
 * @param facets
 *   what to count the matches per value of, empty for no counting. Each
 *   facet's counts are keyed by its name in the result
 * @param sort
 *   the order to return results in, empty for the best matches first
 * @param fields
 *   the fields to bring back with each result, empty for every stored field.
 *   A field inside an object is named by its dotted path and comes back inside
 *   the object, which then holds only the fields that were asked for. The
 *   primary key is always included, as it is what a result is identified by
 * @param highlight
 *   the fields to return highlighted fragments for, each with how to build
 *   them, empty for none. A field that was not defined for highlighting is
 *   refused when the search runs
 * @param locale
 *   the locale the search reads locale specific fields in (BCP-47), deciding
 *   which variant of each is matched and sorted by. {@code null} leaves every
 *   field to its own default locale. A field that holds no values in the
 *   locale falls back to its default, so a search across several fields does
 *   not fail on the ones that never held it
 * @param limit
 *   how many results to return. Zero returns how many there are without
 *   returning any of them
 * @param offset
 *   how many results to skip before the ones being returned
 * @param after
 *   the hit to continue past, so the results are the ones following it in
 *   sort order - or {@code null} to start where {@code offset} says. Unlike
 *   an offset this costs the same at any depth, as nothing before it is
 *   ranked. Can not be combined with an offset or {@code before}
 * @param before
 *   the hit to stop in front of, so the results are the ones preceding it -
 *   still in the sort order asked for, with the hit just before it last.
 *   Can not be combined with an offset or {@code after}
 * @param total
 *   how far the total is counted, see {@link Total}
 * @param signals
 *   the values of the documents themselves to take into their relevance,
 *   replacing the ones the index declares - which is how a ranking is tried
 *   out before it is adopted. {@code null} ranks by what the index declares,
 *   empty by how well documents match alone. Only read where relevance is the
 *   ordering, so a search that sorts by a field is unaffected
 */
public record SearchRequest(
	ImmutableList<Query> query,
	ImmutableList<FieldQuery> filters,
	ImmutableList<Facet> facets,
	ImmutableList<SortBy> sort,
	ImmutableSet<String> fields,
	ImmutableMap<String, Highlight> highlight,
	String locale,
	int limit,
	int offset,
	SortKey after,
	SortKey before,
	Total total,
	ImmutableList<RankingSignal> signals
) {
	/**
	 * How many results are returned when nothing else is asked for.
	 */
	public static final int DEFAULT_LIMIT = 10;

	/**
	 * How far the total of a search is counted.
	 *
	 * Counting every match costs more the more there are, so which of these a
	 * search asks for is a trade between knowing the number and answering
	 * quickly.
	 */
	public enum Total {
		/**
		 * Stop counting once it is known there are more matches than the
		 * search brings back, leaving the count a lower bound. Enough when
		 * the count is only shown as something like {@code 1000+}.
		 */
		ESTIMATE,

		/**
		 * Count every match, however many there are, so the count is the
		 * whole number. What numbering pages requires.
		 */
		EXACT
	}

	/**
	 * How to build the highlighted fragments of one field.
	 *
	 * Fragments are pieces of the text a field held, with what the text part
	 * of the search matched wrapped in the given pair of markers. What was
	 * only filtered on is never highlighted, and a word matched while half
	 * typed is highlighted whole.
	 *
	 * @param fragments
	 *   how many fragments to return at most, at least one
	 * @param length
	 *   how long a fragment aims to be in characters, at least one. Text
	 *   shorter than this comes back as a single fragment holding all of it
	 * @param pre
	 *   what to put in front of each match, may be empty
	 * @param post
	 *   what to put after each match, may be empty
	 */
	public record Highlight(int fragments, int length, String pre, String post) {
		/**
		 * How many fragments a field returns when nothing else is asked for.
		 */
		public static final int DEFAULT_FRAGMENTS = 3;

		/**
		 * How long a fragment aims to be when nothing else is asked for.
		 */
		public static final int DEFAULT_LENGTH = 150;

		/**
		 * What is put in front of a match when nothing else is asked for.
		 */
		public static final String DEFAULT_PRE = "<em>";

		/**
		 * What is put after a match when nothing else is asked for.
		 */
		public static final String DEFAULT_POST = "</em>";

		public Highlight {
			if(fragments < 1) {
				throw new IllegalArgumentException("A highlight can not return fewer than one fragment");
			}

			if(length < 1) {
				throw new IllegalArgumentException("A fragment can not aim to be shorter than one character");
			}

			if(pre == null || post == null) {
				throw new IllegalArgumentException("A match is wrapped in a pair of markers, even empty ones");
			}
		}

		/**
		 * Highlight the way the engine does when nothing else is asked for.
		 *
		 * @return
		 */
		public static Highlight defaults() {
			return new Highlight(DEFAULT_FRAGMENTS, DEFAULT_LENGTH, DEFAULT_PRE, DEFAULT_POST);
		}
	}

	public SearchRequest {
		if(query == null) {
			query = Lists.immutable.empty();
		}

		if(filters == null) {
			filters = Lists.immutable.empty();
		}

		if(facets == null) {
			facets = Lists.immutable.empty();
		}

		var facetNames = Sets.mutable.<String>empty();
		for(var facet : facets) {
			if(!facetNames.add(facet.name())) {
				throw new IllegalArgumentException(
					"Two facets can not be keyed by the same name: " + facet.name()
				);
			}
		}

		if(sort == null) {
			sort = Lists.immutable.empty();
		}

		if(fields == null) {
			fields = Sets.immutable.empty();
		}

		if(highlight == null) {
			highlight = Maps.immutable.empty();
		}

		if(total == null) {
			total = Total.ESTIMATE;
		}

		if(limit < 0) {
			throw new IllegalArgumentException("A search can not return fewer than no results");
		}

		if(offset < 0) {
			throw new IllegalArgumentException("A search can not skip fewer than no results");
		}

		if(after != null && before != null) {
			throw new IllegalArgumentException(
				"A search can continue after a hit or stop before one, not both"
			);
		}

		if((after != null || before != null) && offset != 0) {
			throw new IllegalArgumentException(
				"A search that continues from a hit says where it starts, so an offset can not also be given"
			);
		}
	}

	/**
	 * Get this search looking for the given clauses instead, with everything
	 * else about it left as it is.
	 *
	 * @param query
	 * @return
	 */
	public SearchRequest withQuery(ImmutableList<Query> query) {
		return new SearchRequest(
			query,
			filters,
			facets,
			sort,
			fields,
			highlight,
			locale,
			limit,
			offset,
			after,
			before,
			total,
			signals
		);
	}

	/**
	 * Start building a search.
	 *
	 * @return
	 */
	public static Builder create() {
		return new Builder(
			Lists.immutable.empty(),
			Lists.immutable.empty(),
			Lists.immutable.empty(),
			Lists.immutable.empty(),
			Sets.immutable.empty(),
			Maps.immutable.empty(),
			null,
			DEFAULT_LIMIT,
			0,
			null,
			null,
			Total.ESTIMATE,
			null
		);
	}

	/**
	 * Search for everything in an index, most recently indexed order not
	 * guaranteed.
	 *
	 * @return
	 */
	public static SearchRequest all() {
		return create().build();
	}

	public record Builder(
		ImmutableList<Query> query,
		ImmutableList<FieldQuery> filters,
		ImmutableList<Facet> facets,
		ImmutableList<SortBy> sort,
		ImmutableSet<String> fields,
		ImmutableMap<String, Highlight> highlight,
		String locale,
		int limit,
		int offset,
		SortKey after,
		SortKey before,
		Total total,
		ImmutableList<RankingSignal> signals
	) {
		/**
		 * Set the clauses a document has to satisfy, replacing any set before.
		 *
		 * @param query
		 * @return
		 */
		public Builder withQuery(Query... query) {
			return new Builder(
				Lists.immutable.of(query),
				filters, facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Set the clauses a document has to satisfy, replacing any set before.
		 *
		 * @param query
		 * @return
		 */
		public Builder withQuery(Iterable<? extends Query> query) {
			return new Builder(
				Lists.immutable.<Query>ofAll(query),
				filters, facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Add a clause a document has to satisfy.
		 *
		 * @param clause
		 * @return
		 */
		public Builder addQuery(Query clause) {
			return new Builder(
				query.newWith(clause),
				filters, facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Set the ticked refinements hits have to satisfy, replacing any set
		 * before. Facets are counted sideways of the filters on their own
		 * field, unlike clauses in the query which narrow every count.
		 *
		 * @param filters
		 * @return
		 */
		public Builder withFilters(FieldQuery... filters) {
			return new Builder(
				query,
				Lists.immutable.of(filters),
				facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Set the ticked refinements hits have to satisfy, replacing any set
		 * before. Facets are counted sideways of the filters on their own
		 * field, unlike clauses in the query which narrow every count.
		 *
		 * @param filters
		 * @return
		 */
		public Builder withFilters(Iterable<? extends FieldQuery> filters) {
			return new Builder(
				query,
				Lists.immutable.<FieldQuery>ofAll(filters),
				facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Add a ticked refinement hits have to satisfy.
		 *
		 * @param filter
		 * @return
		 */
		public Builder addFilter(FieldQuery filter) {
			return new Builder(
				query,
				filters.newWith(filter),
				facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Set what to count the matches per value of, replacing any set
		 * before.
		 *
		 * @param facets
		 * @return
		 */
		public Builder withFacets(Facet... facets) {
			return new Builder(
				query, filters,
				Lists.immutable.of(facets),
				sort, fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Set what to count the matches per value of, replacing any set
		 * before.
		 *
		 * @param facets
		 * @return
		 */
		public Builder withFacets(Iterable<? extends Facet> facets) {
			return new Builder(
				query, filters,
				Lists.immutable.<Facet>ofAll(facets),
				sort, fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Add something to count the matches per value of.
		 *
		 * @param facet
		 * @return
		 */
		public Builder addFacet(Facet facet) {
			return new Builder(
				query, filters,
				facets.newWith(facet),
				sort, fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Set the order results come back in, replacing any set before.
		 *
		 * @param sort
		 * @return
		 */
		public Builder withSort(SortBy... sort) {
			return new Builder(
				query, filters, facets,
				Lists.immutable.of(sort),
				fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Set the order results come back in, replacing any set before.
		 *
		 * @param sort
		 * @return
		 */
		public Builder withSort(Iterable<? extends SortBy> sort) {
			return new Builder(
				query, filters, facets,
				Lists.immutable.<SortBy>ofAll(sort),
				fields, highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Set the fields to bring back with each result.
		 *
		 * @param fields
		 * @return
		 */
		public Builder withFields(String... fields) {
			return new Builder(
				query, filters, facets, sort,
				Sets.immutable.of(fields),
				highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Set the fields to bring back with each result.
		 *
		 * @param fields
		 * @return
		 */
		public Builder withFields(Iterable<String> fields) {
			return new Builder(
				query, filters, facets, sort,
				Sets.immutable.ofAll(fields),
				highlight, locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Set the fields to return highlighted fragments for, replacing any
		 * set before.
		 *
		 * @param highlight
		 *   the fields to highlight, each with how to build its fragments
		 * @return
		 */
		public Builder withHighlight(MapIterable<String, Highlight> highlight) {
			var copied = Maps.mutable.<String, Highlight>empty();
			highlight.forEachKeyValue(copied::put);

			return new Builder(
				query, filters, facets, sort, fields,
				copied.toImmutable(),
				locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Add a field to return highlighted fragments for.
		 *
		 * @param field
		 *   name of the field, as it is called in the definition of the index
		 * @param options
		 *   how to build the fragments
		 * @return
		 */
		public Builder addHighlight(String field, Highlight options) {
			return new Builder(
				query, filters, facets, sort, fields,
				highlight.newWithKeyValue(field, options),
				locale, limit, offset, after, before, total, signals
			);
		}

		/**
		 * Add a field to return highlighted fragments for, built the way the
		 * engine does when nothing else is asked for.
		 *
		 * @param field
		 *   name of the field, as it is called in the definition of the index
		 * @return
		 */
		public Builder addHighlight(String field) {
			return addHighlight(field, Highlight.defaults());
		}

		/**
		 * Set the locale the search reads locale specific fields in.
		 *
		 * @param locale
		 *   BCP-47 tag, or {@code null} to leave every field to its own
		 *   default locale
		 * @return
		 */
		public Builder withLocale(String locale) {
			return new Builder(query, filters, facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals);
		}

		/**
		 * Set how many results to return.
		 *
		 * @param limit
		 * @return
		 */
		public Builder withLimit(int limit) {
			return new Builder(query, filters, facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals);
		}

		/**
		 * Set how many results to skip.
		 *
		 * @param offset
		 * @return
		 */
		public Builder withOffset(int offset) {
			return new Builder(query, filters, facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals);
		}

		/**
		 * Set the hit to continue past, so the results are the ones following
		 * it in sort order.
		 *
		 * @param after
		 *   the key a previous result carried, or {@code null} to not continue
		 *   from anywhere
		 * @return
		 */
		public Builder withAfter(SortKey after) {
			return new Builder(query, filters, facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals);
		}

		/**
		 * Set the hit to stop in front of, so the results are the ones
		 * preceding it in sort order.
		 *
		 * @param before
		 *   the key a previous result carried, or {@code null} to not stop
		 *   anywhere
		 * @return
		 */
		public Builder withBefore(SortKey before) {
			return new Builder(query, filters, facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals);
		}

		/**
		 * Set the values of the documents themselves to rank by, replacing the
		 * ones the index declares. Giving none at all ranks by how well
		 * documents match alone, which is how a search opts out of the ranking
		 * of the index.
		 *
		 * @param signals
		 * @return
		 */
		public Builder withSignals(RankingSignal... signals) {
			return new Builder(
				query, filters, facets, sort, fields, highlight, locale, limit, offset,
				after, before, total,
				Lists.immutable.of(signals)
			);
		}

		/**
		 * Set the values of the documents themselves to rank by, replacing the
		 * ones the index declares.
		 *
		 * @param signals
		 *   the signals, or {@code null} to rank by the ones the index declares
		 * @return
		 */
		public Builder withSignals(Iterable<? extends RankingSignal> signals) {
			return new Builder(
				query, filters, facets, sort, fields, highlight, locale, limit, offset,
				after, before, total,
				signals == null ? null : Lists.immutable.<RankingSignal>ofAll(signals)
			);
		}

		/**
		 * Set how far the total is counted.
		 *
		 * @param total
		 * @return
		 */
		public Builder withTotal(Total total) {
			return new Builder(query, filters, facets, sort, fields, highlight, locale, limit, offset, after, before, total, signals);
		}

		public SearchRequest build() {
			return new SearchRequest(
				query, filters, facets, sort, fields, highlight, locale, limit, offset,
				after, before, total, signals
			);
		}
	}
}
