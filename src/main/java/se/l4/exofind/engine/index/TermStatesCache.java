package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.search.IndexSearcher;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * Cache over where a term sits in a reader, shared by every index of the
 * node.
 *
 * <p>Turning a term query into a weight needs the term's place in the term
 * dictionary of every segment, and its document frequency for scoring. Lucene
 * collects both into a {@link TermStates} by seeking the term in each
 * segment, and does so again for every query that names the term. A text
 * search builds one term query per word per field, a field is usually
 * searched in several ways at once, and the requests that follow ask for the
 * same words again, so the same seek is paid over and over.
 *
 * <p>What the seek finds depends only on the reader and the term, so entries
 * are keyed by the reader and the term. A reader is only ever replaced, never
 * changed, so an entry stays true for as long as its reader is open and is
 * dropped when the reader closes, through the listener registered on it.
 *
 * <p>There is one cache for the node rather than one per index, bounded by
 * an estimate of the bytes its entries take. A node holds an unbounded number
 * of indexes and nothing can say ahead of time which of them are searched; a
 * shared budget lets the indexes that are searched hold the space.
 *
 * <p>Entries are always built with statistics, even for a weight that does
 * not score. A {@link TermStates} built without them refuses to answer
 * {@code docFreq()}, and one cached entry answers every later query naming
 * the term, scoring or not.
 *
 * <p>Safe for concurrent use. A built {@link TermStates} is not written to
 * afterwards and Lucene already shares one between the weights of a single
 * query, so several searches may read one entry at the same time. Two
 * searches that want the same unseen term seek it twice and one result is
 * thrown away, which costs no more than the seek they would each have done
 * without a cache.
 */
public final class TermStatesCache {
	/**
	 * How many bytes of entries a cache holds when nothing says otherwise.
	 */
	public static final long DEFAULT_MAX_SIZE = 32L << 20;

	/**
	 * What an entry costs before its per segment states: the key with its
	 * term, the {@link TermStates} object, and the cache's own bookkeeping.
	 * An estimate, but a fixed one.
	 */
	private static final int ENTRY_OVERHEAD = 192;

	/**
	 * What one segment adds to an entry: a slot in the array of states, and
	 * for a segment that holds the term the block state Lucene keeps for it.
	 * The segments that hold the term are counted at that; the others at the
	 * slot alone.
	 */
	private static final int SLOT_BYTES = 8;
	private static final int STATE_BYTES = 80;

	private record Key(IndexReader.CacheKey reader, Term term) {
	}

	/**
	 * A cached {@link TermStates} with what it was weighed at when it was
	 * built, as the states carry no view of their own segments to weigh them
	 * by later.
	 */
	private record Entry(TermStates states, int weight) {
	}

	private final Cache<Key, Entry> cache;

	/**
	 * The readers whose closing already removes their entries, so a listener
	 * is registered once per reader rather than once per term.
	 */
	private final Set<IndexReader.CacheKey> watched;

	private TermStatesCache(Cache<Key, Entry> cache) {
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
	public static TermStatesCache sized(long maxSize) {
		if(maxSize < 0) {
			throw new IllegalArgumentException("A term states cache can not be negative in size");
		}

		return new TermStatesCache(
			Caffeine.newBuilder()
				.maximumWeight(maxSize)
				.<Key, Entry>weigher((key, entry) -> entry.weight())
				.recordStats()
				.build()
		);
	}

	/**
	 * Get where a term sits in the reader of a searcher, seeking it out on the
	 * first ask. The result is built for the searcher's top reader context, so
	 * it serves any searcher opened over the same reader.
	 *
	 * @param searcher
	 *   the searcher whose reader the term is looked up in
	 * @return
	 *   the states, with statistics; never {@code null}
	 * @throws IOException
	 *   if seeking the term in a segment fails
	 */
	public TermStates get(IndexSearcher searcher, Term term) throws IOException {
		var helper = searcher.getIndexReader().getReaderCacheHelper();
		if(helper == null) {
			/*
			 * A reader wrapped in a way that left no stable identity to key on
			 * or hang the removal off. Nothing this node opens reads that way,
			 * but the lookup is still right when something does - just not
			 * through the cache.
			 */
			return TermStates.build(searcher, term, true);
		}

		watch(helper);

		var key = new Key(helper.getKey(), term);
		var cached = cache.getIfPresent(key);
		if(cached != null) {
			return cached.states();
		}

		/*
		 * Built outside the cache rather than through a compute, so that a
		 * seek in progress holds up no other lookup of the same term.
		 */
		var built = TermStates.build(searcher, term, true);
		cache.put(key, new Entry(built, weigh(searcher, built)));
		return built;
	}

	/**
	 * Have the closing of a reader remove the entries keyed on it. Without
	 * this the entries of a replaced reader sit dead in the cache until the
	 * eviction policy gets to them, which it only does under pressure.
	 *
	 * The removal walks every key the cache holds. Readers close at the pace
	 * of commits and pulls, not of searches, so the walk stays rare next to
	 * the lookups it keeps honest.
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
	 * Estimate what an entry takes on the heap, from how many segments the
	 * reader has and how many of them hold the term. The states were built
	 * with statistics, so asking a segment reads what was already seeked and
	 * does no I/O.
	 */
	private static int weigh(IndexSearcher searcher, TermStates states) throws IOException {
		var bytes = ENTRY_OVERHEAD;

		for(var leaf : searcher.getIndexReader().leaves()) {
			bytes += SLOT_BYTES;
			if(states.get(leaf) != null) {
				bytes += STATE_BYTES;
			}
		}

		return bytes;
	}

	/**
	 * Get how the cache has answered so far - hits, misses, evictions.
	 */
	public CacheStats stats() {
		return cache.stats();
	}

	/**
	 * Get how many terms the cache is holding, for tests.
	 */
	long entries() {
		cache.cleanUp();
		return cache.estimatedSize();
	}
}
