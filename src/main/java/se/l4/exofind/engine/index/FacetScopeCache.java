package se.l4.exofind.engine.index;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.apache.lucene.index.IndexReader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Cache over what a facet answered over a scope, and how many matches a
 * scope holds, shared by every index of the node.
 *
 * <p>The same facet counted over the same clauses against the same reader
 * answers the same counts every time, and real traffic asks the same few
 * scopes over and over - the category pages and the common filters of a
 * shop. So what a facet answered is kept under the reader and the
 * {@link FacetStates.Scope} it was counted over, and the next search asking
 * for it is answered without a walk. The total of a scope is kept the same
 * way, so a search every facet of which is answered from here collects
 * nothing at all.
 *
 * <p>A reader is only ever replaced, never changed, so an entry stays true
 * for as long as its reader is open and is dropped when the reader closes,
 * through the listener registered on it. A search cut short by its
 * {@link SearchDeadline} counted part of the index, and nothing of it is
 * kept: an entry answers as the whole of the reader, and what was collected
 * over a spent budget is not that.
 *
 * <p>There is one cache for the node rather than one per reader, bounded by
 * an estimate of the bytes its entries take. The shape of a scope is the
 * caller's to choose, so what is kept has to have a ceiling; a shared one
 * lets the indexes that are searched hold the space.
 *
 * <p>The {@code exofind.facets.scope-cache} system property (default
 * {@code true}) turns the cache off. It is not a configuration setting and a
 * node has no reason to set it: it exists for benchmarks, which repeat one
 * request against one reader and would otherwise measure a map lookup
 * instead of counting.
 *
 * <p>Safe for concurrent use. Hits, misses and evictions are counted for the
 * node as a whole, whichever cache answered - see {@link FacetCacheStats}.
 */
public final class FacetScopeCache {
	/**
	 * How many bytes of entries a cache holds when nothing says otherwise.
	 */
	public static final long DEFAULT_MAX_SIZE = 64L << 20;

	/**
	 * Whether what a facet answered over a scope is kept, read once when the
	 * class is loaded - see the class comment.
	 */
	private static final boolean ENABLED = Boolean.parseBoolean(
		System.getProperty("exofind.facets.scope-cache", "true")
	);

	/**
	 * What an entry costs beyond its values: the key with its scope, the
	 * result record, and the cache's own bookkeeping. An estimate, but a
	 * fixed one.
	 */
	private static final int ENTRY_OVERHEAD = 160;

	/**
	 * What one counted value takes: the record, its decoded value, its label
	 * and its path.
	 */
	private static final int VALUE_BYTES = 96;

	/**
	 * What one range bucket takes: the record and its two bounds.
	 */
	private static final int BUCKET_BYTES = 64;

	private static final LongAdder hits = new LongAdder();
	private static final LongAdder misses = new LongAdder();
	private static final LongAdder evictions = new LongAdder();

	/**
	 * One ask for counts: a facet over a scope.
	 */
	private record CountsKey(FacetStates.Scope scope, Facet facet) {
	}

	/**
	 * What is kept under one reader: the counts of a facet over a scope, or
	 * the total of a scope.
	 */
	private record Key(IndexReader.CacheKey reader, Object ask) {
	}

	/**
	 * The cache, or {@code null} when caching is off and every ask is a miss.
	 */
	private final Cache<Key, Object> cache;

	/**
	 * The readers whose closing already removes their entries, so a listener
	 * is registered once per reader rather than once per ask.
	 */
	private final Set<IndexReader.CacheKey> watched;

	private FacetScopeCache(Cache<Key, Object> cache) {
		this.cache = cache;
		this.watched = ConcurrentHashMap.newKeySet();
	}

	/**
	 * Get a cache bounded to roughly the given number of bytes.
	 *
	 * @param maxSize
	 *   what the entries may weigh together, in bytes, at least zero
	 * @throws IllegalArgumentException
	 *   if the size is negative
	 */
	public static FacetScopeCache sized(long maxSize) {
		if(maxSize < 0) {
			throw new IllegalArgumentException("A facet scope cache can not be negative in size");
		}

		if(!ENABLED) {
			return new FacetScopeCache(null);
		}

		return new FacetScopeCache(
			Caffeine.newBuilder()
				.maximumWeight(maxSize)
				.<Key, Object>weigher((key, value) -> weigh(value))
				.evictionListener((key, value, cause) -> {
					if(cause == RemovalCause.SIZE) {
						evictions.increment();
					}
				})
				.build()
		);
	}

	/**
	 * Get what the given facet answered over the given scope, or {@code null}
	 * where nothing was kept - see {@link #keepCounts}.
	 *
	 * @param reader
	 *   the reader the facet is counted against
	 * @param scope
	 *   the scope the facet is counted over
	 */
	public SearchResult.Facet countsOf(IndexReader reader, FacetStates.Scope scope, Facet facet) {
		var kept = get(reader, new CountsKey(scope, FacetStates.shapeOf(facet)));
		return kept == null ? null : (SearchResult.Facet) kept;
	}

	/**
	 * Keep what a facet answered over a scope, for as long as the reader is
	 * open and the entry stays within the bound. Not kept for a reader that
	 * cannot say when it closes, and not kept when the search has run past its
	 * {@link SearchDeadline}, as the counts then describe part of the index.
	 *
	 * @param reader
	 *   the reader the facet was counted against
	 * @param scope
	 *   the scope the facet was counted over
	 */
	public void keepCounts(
		IndexReader reader,
		FacetStates.Scope scope,
		Facet facet,
		SearchResult.Facet counts
	) {
		keep(reader, new CountsKey(scope, FacetStates.shapeOf(facet)), counts);
	}

	/**
	 * Get how many matches the given scope holds, or {@code null} where
	 * nothing was kept - see {@link #keepTotal}.
	 *
	 * @param reader
	 *   the reader the scope was counted against
	 */
	public Long totalOf(IndexReader reader, FacetStates.Scope scope) {
		var kept = get(reader, scope);
		return kept == null ? null : (Long) kept;
	}

	/**
	 * Keep how many matches a scope holds, for as long as the reader is open
	 * and the entry stays within the bound. Not kept for a reader that cannot
	 * say when it closes, and not kept when the search has run past its
	 * {@link SearchDeadline}, as the total is then of part of the index.
	 *
	 * @param reader
	 *   the reader the scope was counted against
	 */
	public void keepTotal(IndexReader reader, FacetStates.Scope scope, long total) {
		keep(reader, scope, total);
	}

	private Object get(IndexReader reader, Object ask) {
		if(cache == null) {
			misses.increment();
			return null;
		}

		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			misses.increment();
			return null;
		}

		var kept = cache.getIfPresent(new Key(helper.getKey(), ask));
		if(kept == null) {
			misses.increment();
		} else {
			hits.increment();
		}

		return kept;
	}

	private void keep(IndexReader reader, Object ask, Object value) {
		if(cache == null) {
			return;
		}

		var helper = reader.getReaderCacheHelper();
		if(helper == null || SearchDeadline.exceeded()) {
			return;
		}

		watch(helper);
		cache.put(new Key(helper.getKey(), ask), value);
	}

	/**
	 * Have the closing of a reader remove the entries keyed on it. Without
	 * this the entries of a replaced reader sit dead in the cache until the
	 * eviction policy gets to them, which it only does under pressure.
	 *
	 * The removal walks every key the cache holds. Readers close at the pace
	 * of commits and pulls, not of searches, so the walk stays rare next to
	 * the asks it keeps honest.
	 */
	private void watch(IndexReader.CacheHelper helper) {
		var reader = helper.getKey();
		if(watched.add(reader)) {
			helper.addClosedListener(closed -> {
				watched.remove(closed);
				cache.asMap().keySet().removeIf(key -> key.reader() == closed);
			});
		}
	}

	/**
	 * Estimate what an entry takes on the heap: a total is its record alone,
	 * and a facet answer is its record plus every value it holds, the
	 * children of a tree included.
	 */
	private static int weigh(Object value) {
		if(value instanceof SearchResult.Facet facet) {
			return ENTRY_OVERHEAD
				+ weighValues(facet.values())
				+ BUCKET_BYTES * facet.buckets().size();
		}

		return ENTRY_OVERHEAD;
	}

	private static int weighValues(Iterable<SearchResult.Facet.Value> values) {
		var bytes = 0;
		for(var value : values) {
			bytes += VALUE_BYTES + weighValues(value.children());
		}

		return bytes;
	}

	/**
	 * Get how many entries the cache is holding, for tests.
	 */
	long entries() {
		if(cache == null) {
			return 0;
		}

		cache.cleanUp();
		return cache.estimatedSize();
	}

	/**
	 * Get how many asks any cache of this node answered from what an earlier
	 * search kept.
	 */
	static long hits() {
		return hits.sum();
	}

	/**
	 * Get how many asks any cache of this node had nothing kept for.
	 */
	static long misses() {
		return misses.sum();
	}

	/**
	 * Get how many entries any cache of this node dropped to stay within its
	 * bound.
	 */
	static long evictions() {
		return evictions.sum();
	}
}
