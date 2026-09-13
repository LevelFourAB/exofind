package se.l4.exofind.engine.index;

import java.util.Optional;

import org.apache.lucene.search.LRUQueryCache;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The caches a search reads through that every index of the node shares,
 * sized once for the node.
 *
 * <p>A node holds an unbounded number of indexes and nothing can say ahead
 * of time which of them are searched, so each cache here is one for the node
 * with one budget, and the indexes that are searched hold the space. Every
 * budget is a setting, see
 * <a href="https://exofind.dev/reference/configuration/#search-caches">Search
 * caches</a> in the configuration reference:
 *
 * <ul>
 *   <li>{@link #queryCache()} keeps which documents a narrowing clause
 *   matched per segment, Lucene's own cache sized for the node</li>
 *   <li>{@link #termStates()} keeps where a term sits in a reader</li>
 *   <li>{@link #automata()} keeps the compiled automata of typo tolerant and
 *   half typed words</li>
 *   <li>{@link #facetScopes()} keeps what a facet answered over a scope</li>
 * </ul>
 *
 * <p>The cache of stored fields is sized apart from these, see
 * {@link DocumentCache}.
 *
 * <p>Safe for concurrent use; every cache here is.
 */
@ApplicationScoped
public class SearchCaches {
	private static final Log logger = Log.of(SearchCaches.class);

	/**
	 * How many queries the query cache holds when nothing says otherwise.
	 */
	public static final int DEFAULT_QUERY_CACHE_QUERIES = 10_000;

	/**
	 * What share of the heap the query cache may hold when nothing says
	 * otherwise, as a divisor: one twentieth.
	 */
	private static final int DEFAULT_QUERY_CACHE_HEAP_SHARE = 20;

	/**
	 * How much more a cached clause may cost than the query it sits in before
	 * caching it is skipped, so that a clause far wider than what it narrows
	 * does not slow the search that would cache it. Lucene's own default.
	 */
	private static final float SKIP_CACHE_FACTOR = 10f;

	private final LRUQueryCache queryCache;
	private final TermStatesCache termStates;
	private final AutomatonCache automata;
	private final FacetScopeCache facetScopes;

	@Inject
	public SearchCaches(
		@ConfigProperty(name = "exofind.search.query-cache.max-queries", defaultValue = "10000")
		int queryCacheQueries,
		@ConfigProperty(name = "exofind.search.query-cache.max-size")
		Optional<String> queryCacheSize,
		@ConfigProperty(name = "exofind.search.term-cache.max-size", defaultValue = "32M")
		String termCacheSize,
		@ConfigProperty(name = "exofind.search.typo-cache.max-entries", defaultValue = "1024")
		int typoEntries,
		@ConfigProperty(name = "exofind.search.prefix-cache.max-entries", defaultValue = "8192")
		int prefixEntries,
		@ConfigProperty(name = "exofind.search.facet-cache.max-size", defaultValue = "64M")
		String facetCacheSize
	) {
		this(
			queryCacheQueries,
			queryCacheSize.map(Indexes::parseSize).orElseGet(SearchCaches::defaultQueryCacheSize),
			Indexes.parseSize(termCacheSize),
			typoEntries,
			prefixEntries,
			Indexes.parseSize(facetCacheSize)
		);

		logger.atInfo()
			.addKeyValue("queryCacheQueries", queryCacheQueries)
			.addKeyValue(
				"queryCacheBytes",
				queryCacheSize.map(Indexes::parseSize).orElseGet(SearchCaches::defaultQueryCacheSize)
			)
			.addKeyValue("termCacheBytes", Indexes.parseSize(termCacheSize))
			.addKeyValue("typoEntries", typoEntries)
			.addKeyValue("prefixEntries", prefixEntries)
			.addKeyValue("facetCacheBytes", Indexes.parseSize(facetCacheSize))
			.log("Sized the search caches of the node");
	}

	/**
	 * Hold caches of the given sizes.
	 *
	 * @param queryCacheQueries
	 *   how many distinct queries the query cache keeps matches for
	 * @param queryCacheBytes
	 *   what the matches in the query cache may take together, in bytes
	 * @param termCacheBytes
	 *   what the term states may take together, in bytes
	 * @param typoEntries
	 *   how many typo tolerant automata are kept
	 * @param prefixEntries
	 *   how many prefix automata are kept
	 * @param facetCacheBytes
	 *   what the kept facet answers may take together, in bytes
	 * @throws IllegalArgumentException
	 *   if a count or a size is negative
	 */
	public SearchCaches(
		int queryCacheQueries,
		long queryCacheBytes,
		long termCacheBytes,
		int typoEntries,
		int prefixEntries,
		long facetCacheBytes
	) {
		if(queryCacheQueries < 0 || queryCacheBytes < 0) {
			throw new IllegalArgumentException("The query cache can not be negative in size");
		}

		/*
		 * Every segment is cached, where Lucene's own default declines any
		 * segment holding less than half the documents of the average segment
		 * of its index. That default holds for a term lookup, but a typo
		 * tolerant or a prefix clause walks the term dictionary of every
		 * segment it is not cached on, on every search, and an index under
		 * the merge policy of this engine always carries a tail of small
		 * segments. Caching them costs a bitset per small segment, which goes
		 * with the segment when a merge replaces it.
		 */
		this.queryCache = new LRUQueryCache(
			queryCacheQueries,
			queryCacheBytes,
			leaf -> true,
			SKIP_CACHE_FACTOR
		);

		this.termStates = TermStatesCache.sized(termCacheBytes);
		this.automata = AutomatonCache.sized(typoEntries, prefixEntries);
		this.facetScopes = FacetScopeCache.sized(facetCacheBytes);
	}

	/**
	 * Get caches of the default sizes, for an index opened outside a node.
	 */
	public static SearchCaches defaults() {
		return new SearchCaches(
			DEFAULT_QUERY_CACHE_QUERIES,
			defaultQueryCacheSize(),
			TermStatesCache.DEFAULT_MAX_SIZE,
			AutomatonCache.DEFAULT_TYPO_ENTRIES,
			AutomatonCache.DEFAULT_PREFIX_ENTRIES,
			FacetScopeCache.DEFAULT_MAX_SIZE
		);
	}

	/**
	 * Get what the query cache may hold when no setting says: a twentieth of
	 * the heap the process may grow to.
	 */
	private static long defaultQueryCacheSize() {
		return Runtime.getRuntime().maxMemory() / DEFAULT_QUERY_CACHE_HEAP_SHARE;
	}

	/**
	 * Get the cache of which documents a narrowing clause matched, per
	 * segment. Every searcher of the node is opened with it. Which clauses
	 * it admits is decided per index, by the caching policy of that index's
	 * searchers.
	 */
	public LRUQueryCache queryCache() {
		return queryCache;
	}

	/**
	 * Get the cache of where a term sits in a reader.
	 */
	public TermStatesCache termStates() {
		return termStates;
	}

	/**
	 * Get the cache of compiled automata.
	 */
	public AutomatonCache automata() {
		return automata;
	}

	/**
	 * Get the cache of what a facet answered over a scope.
	 */
	public FacetScopeCache facetScopes() {
		return facetScopes;
	}
}
