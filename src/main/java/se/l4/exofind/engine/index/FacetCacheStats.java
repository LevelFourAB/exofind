package se.l4.exofind.engine.index;

/**
 * How the facet caches of this node have answered since it started - what a
 * node reports as meters, see {@code docs/reference/metrics.md}.
 *
 * <p>Two caches answer here, one above the other. What a facet answered over
 * one scope is kept per reader, and a search asking for the same facet over
 * the same scope again is a hit. Below that, what each segment counted for
 * the scope nothing narrows is kept per segment reader, so that a search
 * after a refresh only counts the segments the refresh added; a segment
 * answered from there is a hit. Both are described in {@code FacetStates}.
 *
 * <p>Every count only ever grows. A rate is the change over a period. The
 * bytes held are what is kept right now, and go down when readers close.
 *
 * @param hits
 *   facets answered from what an earlier search counted over the same scope
 * @param misses
 *   facets that had to be counted
 * @param evictions
 *   scope entries dropped to make room for ones asked for more recently
 * @param segmentHits
 *   segments whose counts over everything the reader holds were reused
 * @param segmentMisses
 *   segments that had to be counted for everything the reader holds
 * @param heldBytes
 *   an estimate of the heap the facet state of every open reader takes: the
 *   ordinal maps, the columns and postings per segment, and the counts over
 *   everything each segment holds
 */
public record FacetCacheStats(
	long hits,
	long misses,
	long evictions,
	long segmentHits,
	long segmentMisses,
	long heldBytes
) {
	/**
	 * Get how the caches have answered so far.
	 *
	 * @return
	 */
	public static FacetCacheStats current() {
		return FacetStates.stats();
	}
}
