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
 *   because facets are counted sideways of them - a facet leaves the entries
 *   it {@link Facet#excludes(String) excludes} out of its counts, by default
 *   the ones on its own field, while a clause in the query narrows every
 *   count. Only {@link FieldQuery field} and {@link NestedQuery nested}
 *   clauses may sit here - a condition on a field inside an object is a
 *   {@code nested} clause naming it - and no entry may rank, so ticking a
 *   filter never reshuffles the results. Exclusion is per entry, so tick
 *   each facet's field as an entry of its own, several values through one
 *   matcher. Hits have to satisfy filters and query alike
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
 * @param matched
 *   the object fields to say which values of matched with each hit, each with
 *   how many values to bring back, empty for none. A field that is not a
 *   nested object is refused when the search runs
 * @param hits
 *   what a hit of this search stands for, or {@code null} for a document -
 *   see {@link Hits}. With it set, each matched value of the named object
 *   field is a hit of its own, and the total, the facets and the cursors are
 *   all about values
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
 *   the values of the documents themselves to take into their relevance, and
 *   how they meet the ones the index ranks by - see {@link Signals}.
 *   {@code null} ranks by the index's alone. Only read where relevance is the
 *   ordering, so a search that sorts by a field is unaffected
 * @param rescore
 *   a second pass reordering the best results, or {@code null} to answer in
 *   the order the ranking gave. Read under the same rule the signals are, and
 *   refused for a search whose hits are the values of an object field. A
 *   search continuing from {@code after} or {@code before} has left the window
 *   behind, so the second pass does not reach it
 */
public record SearchRequest(
	ImmutableList<Query> query,
	ImmutableList<Query> filters,
	ImmutableList<Facet> facets,
	ImmutableList<SortBy> sort,
	ImmutableSet<String> fields,
	ImmutableMap<String, Highlight> highlight,
	ImmutableMap<String, Matched> matched,
	Hits hits,
	String locale,
	int limit,
	int offset,
	SortKey after,
	SortKey before,
	Total total,
	Signals signals,
	Rescore rescore
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

	/**
	 * How the matched values of one object field come back with each hit.
	 *
	 * The values are the ones the {@code nested} clauses every result had to
	 * satisfy asked for - the same values a sort or a facet on the field reads.
	 * A search that asked nothing of the values matched all of them.
	 *
	 * @param limit
	 *   how many values to return at most, at least one. How many matched in
	 *   all is reported besides the values, so a limit never hides the number
	 * @param fields
	 *   the fields of each value to bring back, named by their dotted paths,
	 *   empty for all of them. A name that is not a field inside the object,
	 *   or one on an index that keeps no copy of its documents, is refused
	 *   when the search runs
	 */
	public record Matched(int limit, ImmutableSet<String> fields) {
		/**
		 * How many values come back when nothing else is asked for.
		 */
		public static final int DEFAULT_LIMIT = 3;

		/**
		 * The most values one field may be asked to bring back.
		 */
		public static final int MAX_LIMIT = 100;

		public Matched {
			if(limit < 1) {
				throw new IllegalArgumentException("Matched values can not return fewer than one value");
			}

			if(fields == null) {
				fields = Sets.immutable.empty();
			}
		}

		/**
		 * Bring back the values whole, however many the limit allows.
		 *
		 * @param limit
		 *   how many values to return at most, at least one
		 */
		public Matched(int limit) {
			this(limit, null);
		}

		/**
		 * Bring back matched values the way the engine does when nothing else
		 * is asked for.
		 *
		 * @return
		 */
		public static Matched defaults() {
			return new Matched(DEFAULT_LIMIT);
		}
	}

	/**
	 * What a hit of a search stands for: each matched value of one object
	 * field, instead of the document holding it.
	 *
	 * The clauses of the search keep their meaning - clauses on the fields of
	 * the index still say which documents take part, and {@code nested} clauses
	 * on the path still say which of their values matched. What changes is the
	 * unit of the answer: every matched value is a hit of its own, and the
	 * total, the facets and the cursors are all about values. The identity of
	 * such a hit is the pair of {@link SearchResult.Hit#id()} and
	 * {@link SearchResult.Hit#index()} - several hits share an {@code id}
	 * whenever several values of one document matched.
	 *
	 * With {@code when} given, only the documents satisfying it answer as their
	 * values; every other document answers as itself, so one page holds both
	 * kinds of hit. The total then counts hits - a document that expanded
	 * counts once per value - while the facets and
	 * {@link SearchResult#documents()} count documents. Every hit scores what
	 * its document scored, so what a document answers as never decides where
	 * it ranks.
	 *
	 * A search whose hits are values can not also ask for {@code highlight} or
	 * {@code matched} - once the hits are the matched values, {@code matched}
	 * would ask a hit about itself - and can only be ordered by score or by
	 * fields inside the path. Ordering by a field is refused as soon as
	 * {@code when} is given, as a page holding both kinds of hit has no one
	 * level to read a sort field at.
	 *
	 * @param path
	 *   name of the object field whose matched values are the hits. The field
	 *   has to be an object in {@code nested} mode, which is refused when the
	 *   search runs
	 * @param fields
	 *   the fields of each value to bring back, named by their dotted paths,
	 *   empty for all of them. A name that is not a field inside the object,
	 *   or one on an index that keeps no copy of its documents, is refused
	 *   when the search runs
	 * @param when
	 *   the clauses a document has to satisfy to answer as its values, empty
	 *   for all of them. Only {@link FieldQuery field} and {@link NestedQuery
	 *   nested} clauses may sit here, and none of them may rank: which hit a
	 *   document answers as says nothing about how well it matched. A document
	 *   satisfying these with no matching value under {@code path} answers with
	 *   nothing at all
	 */
	public record Hits(
		String path,
		ImmutableSet<String> fields,
		ImmutableList<Query> when
	) {
		public Hits {
			if(path == null || path.isBlank()) {
				throw new IllegalArgumentException(
					"Hits that stand for values need the object field they are values of"
				);
			}

			if(fields == null) {
				fields = Sets.immutable.empty();
			}

			if(when == null) {
				when = Lists.immutable.empty();
			}

			for(var clause : when) {
				if(!(clause instanceof FieldQuery) && !(clause instanceof NestedQuery)) {
					throw new IllegalArgumentException(
						"Which documents answer as their values is a field or nested clause - a `"
							+ clause.type() + "` clause scopes the whole search and belongs in the query"
					);
				}

				if(clause.scores()) {
					throw new IllegalArgumentException(
						"Which documents answer as their values is decided without ranking - clauses that score belong in the query"
					);
				}
			}
		}

		/**
		 * Bring back the values whole, of every document the search matches.
		 *
		 * @param path
		 *   name of the object field whose matched values are the hits
		 */
		public Hits(String path) {
			this(path, null, null);
		}

		/**
		 * Bring back the named fields of the values, of every document the
		 * search matches.
		 *
		 * @param path
		 *   name of the object field whose matched values are the hits
		 * @param fields
		 *   the fields of each value to bring back, empty for all of them
		 */
		public Hits(String path, ImmutableSet<String> fields) {
			this(path, fields, null);
		}

		/**
		 * Get whether hits of this search stand for values whatever the
		 * document, rather than only for the documents {@code when} names.
		 */
		public boolean isEveryDocument() {
			return when.isEmpty();
		}
	}

	/**
	 * The values of the documents themselves a search ranks by, and how they
	 * meet the ones the index ranks by.
	 *
	 * <p>An index declares a ranking, which its search settings can replace.
	 * A search bringing signals of its own adds them to that ranking unless it
	 * asks for {@link Mode#REPLACE}, so an affinity boost about the person
	 * searching leaves the ranking of the index in force.
	 *
	 * <pre>
	 * SearchRequest.create()
	 *   .withQuery(Query.text("running shoes"))
	 *   .withSignals(RankingSignal.saturation("brandAffinity", 5))
	 *   .build()
	 * </pre>
	 *
	 * @param mode
	 *   how these meet the signals the index ranks by, {@code null} for
	 *   {@link Mode#ADD}
	 * @param signals
	 *   the signals themselves, {@code null} for none
	 */
	public record Signals(Mode mode, ImmutableList<RankingSignal> signals) {
		/**
		 * How the signals a search brings meet the ones the index ranks by.
		 */
		public enum Mode {
			/**
			 * Rank by both. A signal on a field the index also ranks by stands
			 * in for the index's, so a search moves one weight and leaves the
			 * rest of the ranking alone.
			 */
			ADD,

			/**
			 * Rank by these alone, leaving the signals of the index out.
			 * Bringing none then ranks by how well documents match and nothing
			 * else.
			 */
			REPLACE
		}

		public Signals {
			if(mode == null) {
				mode = Mode.ADD;
			}

			if(signals == null) {
				signals = Lists.immutable.empty();
			}
		}

		/**
		 * Rank by the given signals together with the ones the index declares.
		 */
		public static Signals add(RankingSignal... signals) {
			return new Signals(Mode.ADD, Lists.immutable.of(signals));
		}

		/**
		 * Rank by the given signals alone, leaving out the ones the index
		 * declares. Called with nothing, this ranks by how well documents
		 * match and nothing else.
		 */
		public static Signals replace(RankingSignal... signals) {
			return new Signals(Mode.REPLACE, Lists.immutable.of(signals));
		}
	}

	public SearchRequest {
		if(query == null) {
			query = Lists.immutable.empty();
		}

		if(filters == null) {
			filters = Lists.immutable.empty();
		}

		for(var filter : filters) {
			if(!(filter instanceof FieldQuery) && !(filter instanceof NestedQuery)) {
				throw new IllegalArgumentException(
					"A filter is a field or nested clause - a `" + filter.type()
						+ "` clause scopes the whole search and belongs in the query"
				);
			}

			if(filter.scores()) {
				throw new IllegalArgumentException(
					"A filter narrows without ranking - clauses that score belong in the query"
				);
			}
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

		if(matched == null) {
			matched = Maps.immutable.empty();
		}

		if(hits != null && matched.notEmpty()) {
			throw new IllegalArgumentException(
				"A search whose hits are matched values can not also ask for matched values - they are the hits"
			);
		}

		if(hits != null && highlight.notEmpty()) {
			throw new IllegalArgumentException(
				"A search whose hits are matched values can not highlight"
			);
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

		if(rescore != null && hits != null) {
			throw new IllegalArgumentException(
				"A search whose hits are matched values can not rescore - a second pass scores documents"
			);
		}

		if(rescore != null
			&& after == null
			&& before == null
			&& (long) offset + limit > rescore.window())
		{
			throw new IllegalArgumentException(
				"A rescore has to reach the results being returned - its window is shorter than offset plus limit"
			);
		}
	}

	/**
	 * A search that answers in the order its ranking gave, without a second
	 * pass over the best of them.
	 */
	public SearchRequest(
		ImmutableList<Query> query,
		ImmutableList<Query> filters,
		ImmutableList<Facet> facets,
		ImmutableList<SortBy> sort,
		ImmutableSet<String> fields,
		ImmutableMap<String, Highlight> highlight,
		ImmutableMap<String, Matched> matched,
		Hits hits,
		String locale,
		int limit,
		int offset,
		SortKey after,
		SortKey before,
		Total total,
		Signals signals
	) {
		this(
			query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset,
			after, before, total, signals, null
		);
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
			matched,
			hits,
			locale,
			limit,
			offset,
			after,
			before,
			total,
			signals,
			rescore
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
			Maps.immutable.empty(),
			null,
			null,
			DEFAULT_LIMIT,
			0,
			null,
			null,
			Total.ESTIMATE,
			null,
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
		ImmutableList<Query> filters,
		ImmutableList<Facet> facets,
		ImmutableList<SortBy> sort,
		ImmutableSet<String> fields,
		ImmutableMap<String, Highlight> highlight,
		ImmutableMap<String, Matched> matched,
		Hits hits,
		String locale,
		int limit,
		int offset,
		SortKey after,
		SortKey before,
		Total total,
		Signals signals,
		Rescore rescore
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
				filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
			);
		}

		/**
		 * Set the ticked refinements hits have to satisfy, replacing any set
		 * before. Facets are counted sideways of the filters they exclude,
		 * unlike clauses in the query which narrow every count. Only
		 * {@code field} and {@code nested} clauses may sit here, none of them
		 * ranking.
		 *
		 * @param filters
		 * @return
		 */
		public Builder withFilters(Query... filters) {
			return new Builder(
				query,
				Lists.immutable.of(filters),
				facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
			);
		}

		/**
		 * Set the ticked refinements hits have to satisfy, replacing any set
		 * before. Facets are counted sideways of the filters they exclude,
		 * unlike clauses in the query which narrow every count. Only
		 * {@code field} and {@code nested} clauses may sit here, none of them
		 * ranking.
		 *
		 * @param filters
		 * @return
		 */
		public Builder withFilters(Iterable<? extends Query> filters) {
			return new Builder(
				query,
				Lists.immutable.<Query>ofAll(filters),
				facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
			);
		}

		/**
		 * Add a ticked refinement hits have to satisfy.
		 *
		 * @param filter
		 * @return
		 */
		public Builder addFilter(Query filter) {
			return new Builder(
				query,
				filters.newWith(filter),
				facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
				matched, hits, locale, limit, offset, after, before, total, signals, rescore
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
		 * Set the object fields to say which values of matched with each hit,
		 * replacing any set before.
		 *
		 * @param matched
		 *   the fields, each with how many values to bring back
		 * @return
		 */
		public Builder withMatched(MapIterable<String, Matched> matched) {
			var copied = Maps.mutable.<String, Matched>empty();
			matched.forEachKeyValue(copied::put);

			return new Builder(
				query, filters, facets, sort, fields, highlight,
				copied.toImmutable(),
				hits, locale, limit, offset, after, before, total, signals, rescore
			);
		}

		/**
		 * Add an object field to say which values of matched with each hit.
		 *
		 * @param field
		 *   name of the object field, as it is called in the definition of the
		 *   index
		 * @param options
		 *   how the values come back
		 * @return
		 */
		public Builder addMatched(String field, Matched options) {
			return new Builder(
				query, filters, facets, sort, fields, highlight,
				matched.newWithKeyValue(field, options),
				hits, locale, limit, offset, after, before, total, signals, rescore
			);
		}

		/**
		 * Add an object field to say which values of matched with each hit,
		 * answered the way the engine does when nothing else is asked for.
		 *
		 * @param field
		 *   name of the object field, as it is called in the definition of the
		 *   index
		 * @return
		 */
		public Builder addMatched(String field) {
			return addMatched(field, Matched.defaults());
		}

		/**
		 * Set what a hit of this search stands for, replacing anything set
		 * before.
		 *
		 * @param hits
		 *   the object field whose matched values are the hits, or {@code null}
		 *   for hits that are documents
		 * @return
		 */
		public Builder withHits(Hits hits) {
			return new Builder(query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore);
		}

		/**
		 * Make each matched value of an object field a hit of its own.
		 *
		 * @param path
		 *   name of the object field, as it is called in the definition of the
		 *   index
		 * @return
		 */
		public Builder withHits(String path) {
			return withHits(new Hits(path));
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
			return new Builder(query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore);
		}

		/**
		 * Set how many results to return.
		 *
		 * @param limit
		 * @return
		 */
		public Builder withLimit(int limit) {
			return new Builder(query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore);
		}

		/**
		 * Set how many results to skip.
		 *
		 * @param offset
		 * @return
		 */
		public Builder withOffset(int offset) {
			return new Builder(query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore);
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
			return new Builder(query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore);
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
			return new Builder(query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore);
		}

		/**
		 * Add the given values of the documents themselves to the ones the
		 * index ranks by, replacing any signals set before.
		 *
		 * @param signals
		 */
		public Builder withSignals(RankingSignal... signals) {
			return withSignals(Signals.add(signals));
		}

		/**
		 * Add the given values of the documents themselves to the ones the
		 * index ranks by, replacing any signals set before.
		 *
		 * @param signals
		 *   the signals, or {@code null} to rank by the ones the index declares
		 */
		public Builder withSignals(Iterable<? extends RankingSignal> signals) {
			return withSignals(
				signals == null
					? null
					: new Signals(
						Signals.Mode.ADD,
						Lists.immutable.<RankingSignal>ofAll(signals)
					)
			);
		}

		/**
		 * Set the values of the documents themselves to rank by, and how they
		 * meet the ones the index ranks by, replacing any signals set before.
		 *
		 * @param signals
		 *   the signals, or {@code null} to rank by the ones the index declares
		 */
		public Builder withSignals(Signals signals) {
			return new Builder(
				query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset,
				after, before, total, signals, rescore
			);
		}

		/**
		 * Set the second pass over the best results, replacing any set before.
		 *
		 * @param rescore
		 *   the second pass, or {@code null} to answer in the order the ranking
		 *   gave
		 * @return
		 */
		public Builder withRescore(Rescore rescore) {
			return new Builder(
				query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset,
				after, before, total, signals, rescore
			);
		}

		/**
		 * Set how far the total is counted.
		 *
		 * @param total
		 * @return
		 */
		public Builder withTotal(Total total) {
			return new Builder(query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset, after, before, total, signals, rescore);
		}

		public SearchRequest build() {
			return new SearchRequest(
				query, filters, facets, sort, fields, highlight, matched, hits, locale, limit, offset,
				after, before, total, signals, rescore
			);
		}
	}
}
