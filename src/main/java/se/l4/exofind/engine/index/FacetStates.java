package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.UnaryOperator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.OrdinalMap;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.util.packed.PackedInts;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.map.primitive.LongLongMap;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchResult;

/**
 * What counting a facet has to know about a reader before it can count, and
 * what counting everything a reader holds has already answered, kept for as
 * long as the reader is open.
 *
 * Counting the values of a field across segments needs the ordinals of each
 * segment lined up against one another, which is read out of the term
 * dictionaries and costs as much whether the search matched everything or
 * nothing. It says nothing about the search, only about the reader, so it is
 * built once per reader and field and answered from here after that - a search
 * over a handful of documents would otherwise spend most of its time preparing
 * to count them.
 *
 * What a facet counted says just as little about any one search: the same
 * facet counted over the same clauses against the same reader answers the
 * same counts every time, and real traffic asks the same few scopes over and
 * over - the category pages and the common filters of a shop. So what a facet
 * answered is kept per reader under the scope it was counted over, see
 * {@link Scope}, and the next search asking for it is answered without a
 * walk. The shape of a scope is the caller's to choose, so the entries under
 * one reader are bounded, the least recently asked for going first. The
 * total of a scope is kept the same way, so a search every facet of which is
 * answered from here collects nothing at all.
 *
 * A search cut short by its {@link SearchDeadline} counted part of the index,
 * and nothing of it is kept: an entry answers as the whole of the reader, and
 * what was collected over a spent budget is not that.
 *
 * What a segment holds is fixed for even longer than a reader: a segment is
 * never changed, only merged away, so what is read out of one alone - the
 * values of a facet field laid out flat, see {@link FacetColumns}, and the
 * decoded levels of a tree - is kept per segment core and survives the reader
 * being reopened around it.
 *
 * What a segment counts for the scope nothing narrows - everything the reader
 * holds, see {@link FacetMatches#whole()} - is fixed for as long as the
 * segment's live documents are, which is longer than a reader too: a reopen
 * that only added documents keeps the segment readers it already had. Those
 * counts are kept per segment reader, in the segment's own ordinal space, so
 * a search after a refresh counts the new segments and folds the rest through
 * the reader's ordinal map. The cost of a refresh is then the size of what
 * changed rather than the size of the index. A segment that took a deletion
 * comes back as a new segment reader over the same core, which is what drops
 * its counts: the key is the reader's rather than the core's for exactly
 * this.
 *
 * A reader is only ever replaced, never changed, so an entry stays true for as
 * long as the reader it was built from is open and is dropped when it closes.
 * That is the same lifetime the reader's own caches have and is why the key is
 * the one Lucene hands out for exactly this.
 *
 * Everything here is built by whoever asks first. Without warming that is the
 * first search after a reader is reopened. {@link FacetWarmer} asks ahead of
 * it: on a thread of its own it walks the scope nothing narrows for every
 * faceted field of the new reader, which fills the ordinal maps, the columns
 * and the whole-reader counts before a search needs them. A search that
 * arrives while an ordinal map is being built waits for that build instead of
 * making a second one - see {@link #stringOrdsOf}. That is the one place a
 * search waits for anything here.
 *
 * A facet that answers only the values starting with a prefix compares the
 * prefix with each value folded, and what a value folds to is read off a
 * sorted dictionary of the segment's values kept per segment core the same
 * way - see {@link #foldedTermsOf}. It is built the first time a prefix is
 * asked of a field under a reader that holds the segment, or by the warm
 * for a field the search settings suggest the values of or read out of a
 * search box - see {@link Index#warmFacets}.
 *
 * {@link #heldBytes()} estimates what all of it takes on the heap, for the
 * gauge a node reports. A deployment holding hundreds of indexes reads that
 * gauge to see what warming every open reader costs it.
 *
 * The {@code exofind.facets.scope-cache} system property (default {@code true})
 * turns off what is kept per scope, both the counts and the totals. It is not a
 * configuration setting and a node has no reason to set it: it exists for
 * benchmarks, which repeat one request against one reader and would otherwise
 * measure a map lookup instead of counting. The per-segment caches stay on
 * either way, as a node has those warm too.
 */
final class FacetStates {
	/**
	 * Whether what a facet answered over a scope is kept, read once when the
	 * class is loaded - see the class comment.
	 */
	private static final boolean SCOPE_CACHE = Boolean.parseBoolean(
		System.getProperty("exofind.facets.scope-cache", "true")
	);

	/**
	 * How many scopes one reader keeps answers for, counts and totals each.
	 * The shape of a scope is the caller's to choose, so the entries under
	 * one reader are bounded rather than trusted to be few; past the bound
	 * the scope asked for least recently goes.
	 */
	private static final int SCOPE_LIMIT = 1024;

	/**
	 * The ordinals of one field's segments lined up against one another, per
	 * reader and field - see {@link #stringOrdsOf}. An entry is the build
	 * itself, complete or still running, so that a second thread asking for
	 * the same field waits for it instead of building a second copy.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, CompletableFuture<StringOrds>>> ords =
		new ConcurrentHashMap<>();

	/**
	 * What one facet answered over one scope, per reader - see
	 * {@link #scopeCountsOf}.
	 */
	private static final Map<IndexReader.CacheKey, Recent<ScopeKey, SearchResult.Facet>> scopeCounts =
		new ConcurrentHashMap<>();

	/**
	 * How many matches one scope holds, per reader - see
	 * {@link #scopeTotalOf}.
	 */
	private static final Map<IndexReader.CacheKey, Recent<Scope, Long>> scopeTotals =
		new ConcurrentHashMap<>();

	/**
	 * What one segment counted for everything the reader holds, per segment
	 * reader and by what was counted - see {@link #segmentCountsOf}.
	 */
	private static final Map<IndexReader.CacheKey, Map<SegmentKey, Object>> segmentCounts =
		new ConcurrentHashMap<>();

	private static final LongAdder scopeHits = new LongAdder();
	private static final LongAdder scopeMisses = new LongAdder();
	private static final LongAdder scopeEvictions = new LongAdder();
	private static final LongAdder segmentHits = new LongAdder();
	private static final LongAdder segmentMisses = new LongAdder();

	/**
	 * How many ordinal maps have been built, for a test of two threads asking
	 * for the same one.
	 */
	private static final LongAdder ordinalBuilds = new LongAdder();

	/**
	 * The distinct values of one segment of a field counted a level at a time,
	 * decoded and parsed once per segment rather than once per search - reading
	 * them walks the term dictionary of the segment, which costs the same
	 * however few documents matched. Small because such a field holds a tree of
	 * levels rather than one value per document. Keyed by the core of the
	 * segment, which survives the reader being reopened, and dropped when the
	 * core goes away.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, Hierarchy>> values =
		new ConcurrentHashMap<>();

	/**
	 * The ordinals of one segment of a field laid out flat, per segment core
	 * and field - see {@link #ordsOf}.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, FacetColumns.Ords>> ordColumns =
		new ConcurrentHashMap<>();

	/**
	 * The numbers of one segment of a field laid out flat, per segment core
	 * and field - see {@link #longsOf}.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, FacetColumns.Longs>> longColumns =
		new ConcurrentHashMap<>();

	/**
	 * The ordinals of one segment of a field inverted, per segment core and
	 * field - see {@link #ordPostingsOf}.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, FacetColumns.OrdPostings>> ordPostings =
		new ConcurrentHashMap<>();

	/**
	 * The numbers of one segment of a field sorted, per segment core and
	 * field - see {@link #longPostingsOf}.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, FacetColumns.LongPostings>> longPostings =
		new ConcurrentHashMap<>();

	/**
	 * The ordinals of one segment of a field inside an object inverted in
	 * terms of the documents above its values, per segment core and field -
	 * see {@link #rolledUpOrdPostingsOf}.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, FacetColumns.OrdPostings>> rolledUpOrdPostings =
		new ConcurrentHashMap<>();

	/**
	 * The numbers of one segment of a field inside an object sorted, each
	 * beside the document above the value holding it, per segment core and
	 * field - see {@link #rolledUpLongPostingsOf}.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, FacetColumns.LongPostings>> rolledUpLongPostings =
		new ConcurrentHashMap<>();

	/**
	 * How many documents of the index one segment holds, per segment core -
	 * see {@link #documentCountOf}.
	 */
	private static final Map<IndexReader.CacheKey, Integer> documentCounts =
		new ConcurrentHashMap<>();

	private FacetStates() {
	}

	/**
	 * Get how many documents of the index the given segment holds, leaving
	 * out the values of object fields, counting them the first time the
	 * segment is asked for. What a scope of documents is measured against to
	 * tell how much of a field inside an object it covers.
	 *
	 * @param context
	 *   the segment being read
	 * @param parents
	 *   finds the documents of the index in the segment
	 * @return
	 * @throws IOException
	 */
	static int documentCountOf(LeafReaderContext context, BitSetProducer parents)
		throws IOException
	{
		var helper = context.reader().getCoreCacheHelper();
		if(helper == null) {
			return countDocuments(context, parents);
		}

		var key = helper.getKey();
		var count = documentCounts.get(key);
		if(count == null) {
			count = countDocuments(context, parents);
			if(documentCounts.putIfAbsent(key, count) == null) {
				helper.addClosedListener(documentCounts::remove);
			}
		}

		return count;
	}

	private static int countDocuments(LeafReaderContext context, BitSetProducer parents)
		throws IOException
	{
		var documents = parents.getBitSet(context);
		return documents == null ? 0 : documents.cardinality();
	}

	/**
	 * Get the ordinals of the given field inside an object in the given
	 * segment inverted in terms of the documents above its values, building
	 * them the first time the segment is asked for. The field has to hold
	 * values in the segment. Kept the way {@link #ordPostingsOf} keeps the
	 * postings of the values themselves: which documents of the index a
	 * segment holds is as fixed as the segment, so what is built from them
	 * is kept per core like everything else read out of one.
	 *
	 * @param context
	 *   the segment being read
	 * @param field
	 *   the Lucene field the values were written under
	 * @param parents
	 *   finds the documents of the index in the segment
	 * @return
	 *   the postings, or {@code null} where the segment holds no document of
	 *   the index and so nothing to roll up into
	 * @throws IOException
	 */
	static FacetColumns.OrdPostings rolledUpOrdPostingsOf(
		LeafReaderContext context,
		String field,
		BitSetProducer parents
	) throws IOException {
		var helper = context.reader().getCoreCacheHelper();
		if(helper == null) {
			return buildRolledUpOrdPostings(context, field, parents);
		}

		var key = helper.getKey();
		var fields = rolledUpOrdPostings.get(key);
		if(fields == null) {
			fields = rolledUpOrdPostings.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
			helper.addClosedListener(rolledUpOrdPostings::remove);
		}

		var postings = fields.get(field);
		if(postings == null) {
			postings = buildRolledUpOrdPostings(context, field, parents);
			if(postings != null) {
				fields.put(field, postings);
			}
		}

		return postings;
	}

	private static FacetColumns.OrdPostings buildRolledUpOrdPostings(
		LeafReaderContext context,
		String field,
		BitSetProducer parents
	) throws IOException {
		var documents = parents.getBitSet(context);
		if(documents == null) {
			return null;
		}

		var reader = context.reader();
		return FacetColumns.rolledUpOrdPostings(
			ordsOf(context, field),
			(int) reader.getSortedSetDocValues(field).getValueCount(),
			reader.maxDoc(),
			documents
		);
	}

	/**
	 * Get the numbers of the given field inside an object in the given
	 * segment sorted, each beside the document above the value holding it,
	 * building them the first time the segment is asked for. The field has
	 * to hold values in the segment. Kept the way
	 * {@link #rolledUpOrdPostingsOf} keeps ordinals.
	 *
	 * @param context
	 *   the segment being read
	 * @param field
	 *   the Lucene field the values were written under
	 * @param parents
	 *   finds the documents of the index in the segment
	 * @return
	 *   the postings, or {@code null} where the segment holds no document of
	 *   the index and so nothing to roll up into
	 * @throws IOException
	 */
	static FacetColumns.LongPostings rolledUpLongPostingsOf(
		LeafReaderContext context,
		String field,
		BitSetProducer parents
	) throws IOException {
		var helper = context.reader().getCoreCacheHelper();
		if(helper == null) {
			return buildRolledUpLongPostings(context, field, parents);
		}

		var key = helper.getKey();
		var fields = rolledUpLongPostings.get(key);
		if(fields == null) {
			fields = rolledUpLongPostings.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
			helper.addClosedListener(rolledUpLongPostings::remove);
		}

		var postings = fields.get(field);
		if(postings == null) {
			postings = buildRolledUpLongPostings(context, field, parents);
			if(postings != null) {
				fields.put(field, postings);
			}
		}

		return postings;
	}

	private static FacetColumns.LongPostings buildRolledUpLongPostings(
		LeafReaderContext context,
		String field,
		BitSetProducer parents
	) throws IOException {
		var documents = parents.getBitSet(context);
		if(documents == null) {
			return null;
		}

		return FacetColumns.rolledUpLongPostings(
			longsOf(context, field),
			context.reader().maxDoc(),
			documents
		);
	}

	/**
	 * Get the ordinals of the given field in the given segment inverted,
	 * building them the first time the segment is asked for. The field has to
	 * hold values in the segment. Kept the way {@link #ordsOf} keeps the
	 * column, and built from it.
	 *
	 * @param context
	 *   the segment being read
	 * @param field
	 *   the Lucene field the values were written under
	 * @return
	 * @throws IOException
	 */
	static FacetColumns.OrdPostings ordPostingsOf(LeafReaderContext context, String field)
		throws IOException
	{
		var helper = context.reader().getCoreCacheHelper();
		if(helper == null) {
			return buildOrdPostings(context, field);
		}

		var key = helper.getKey();
		var fields = ordPostings.get(key);
		if(fields == null) {
			fields = ordPostings.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
			helper.addClosedListener(ordPostings::remove);
		}

		var postings = fields.get(field);
		if(postings == null) {
			postings = buildOrdPostings(context, field);
			fields.put(field, postings);
		}

		return postings;
	}

	private static FacetColumns.OrdPostings buildOrdPostings(
		LeafReaderContext context,
		String field
	) throws IOException {
		var reader = context.reader();
		return FacetColumns.ordPostings(
			ordsOf(context, field),
			(int) reader.getSortedSetDocValues(field).getValueCount(),
			reader.maxDoc()
		);
	}

	/**
	 * Get the numbers of the given field in the given segment sorted,
	 * building them the first time the segment is asked for. The field has to
	 * hold values in the segment. Kept the way {@link #longsOf} keeps the
	 * column, and built from it.
	 *
	 * @param context
	 *   the segment being read
	 * @param field
	 *   the Lucene field the values were written under
	 * @return
	 * @throws IOException
	 */
	static FacetColumns.LongPostings longPostingsOf(LeafReaderContext context, String field)
		throws IOException
	{
		var helper = context.reader().getCoreCacheHelper();
		if(helper == null) {
			return FacetColumns.longPostings(longsOf(context, field), context.reader().maxDoc());
		}

		var key = helper.getKey();
		var fields = longPostings.get(key);
		if(fields == null) {
			fields = longPostings.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
			helper.addClosedListener(longPostings::remove);
		}

		var postings = fields.get(field);
		if(postings == null) {
			postings = FacetColumns.longPostings(longsOf(context, field), context.reader().maxDoc());
			fields.put(field, postings);
		}

		return postings;
	}

	/**
	 * Get the ordinals of the given field in the given segment laid out flat,
	 * building them the first time the segment is asked for. The field has to
	 * hold values in the segment.
	 *
	 * Building reads every document of the segment once, which is paid by the
	 * first search that counts the field after the segment appears and by
	 * nobody after that. A segment that cannot say when its core goes away is
	 * not kept, as an entry for it could never be dropped; its ordinals are
	 * laid out and handed back without being remembered.
	 *
	 * @param context
	 *   the segment being read
	 * @param field
	 *   the Lucene field the values were written under
	 * @return
	 * @throws IOException
	 */
	static FacetColumns.Ords ordsOf(LeafReaderContext context, String field)
		throws IOException
	{
		var helper = context.reader().getCoreCacheHelper();
		if(helper == null) {
			return FacetColumns.ords(context.reader(), field);
		}

		var key = helper.getKey();
		var fields = ordColumns.get(key);
		if(fields == null) {
			fields = ordColumns.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());

			/*
			 * Registered against the key rather than against the map, so a
			 * segment that went away while this was being built drops what was
			 * put under it instead of leaving it behind.
			 */
			helper.addClosedListener(ordColumns::remove);
		}

		var column = fields.get(field);
		if(column == null) {
			column = FacetColumns.ords(context.reader(), field);
			fields.put(field, column);
		}

		return column;
	}

	/**
	 * Get the numbers of the given field in the given segment laid out flat,
	 * building them the first time the segment is asked for. The field has to
	 * hold values in the segment. Kept the way {@link #ordsOf} keeps ordinals.
	 *
	 * @param context
	 *   the segment being read
	 * @param field
	 *   the Lucene field the values were written under
	 * @return
	 * @throws IOException
	 */
	static FacetColumns.Longs longsOf(LeafReaderContext context, String field)
		throws IOException
	{
		var helper = context.reader().getCoreCacheHelper();
		if(helper == null) {
			return FacetColumns.longs(context.reader(), field);
		}

		var key = helper.getKey();
		var fields = longColumns.get(key);
		if(fields == null) {
			fields = longColumns.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
			helper.addClosedListener(longColumns::remove);
		}

		var column = fields.get(field);
		if(column == null) {
			column = FacetColumns.longs(context.reader(), field);
			fields.put(field, column);
		}

		return column;
	}

	/**
	 * Get the segment ordinals of the given field lined up against one
	 * another, building them the first time the reader is asked for.
	 *
	 * Built once per reader and field however many threads ask at once: the
	 * first to ask builds, and the others wait for that build. Building walks
	 * the term dictionary of every segment, so a second build would take as
	 * long as the wait and leave two copies behind. A {@link FacetWarmer} can
	 * therefore build ahead of a search without the two racing.
	 *
	 * A reader that cannot say when it closes is not kept, as an entry for it
	 * could never be dropped; its ordinals are lined up and handed back
	 * without being remembered.
	 *
	 * @param reader
	 * @param field
	 * @return
	 * @throws IOException
	 *   if building failed, on the thread that built and on every thread
	 *   that waited for it; the next to ask builds again
	 */
	static StringOrds stringOrdsOf(IndexReader reader, String field)
		throws IOException
	{
		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			ordinalBuilds.increment();
			return StringOrds.build(reader, field);
		}

		var key = helper.getKey();
		var fields = ords.get(key);
		if(fields == null) {
			fields = ords.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());

			/*
			 * Registered against the key rather than against the map, so a
			 * reader that closed while this was being built drops what was put
			 * under it instead of leaving it behind.
			 */
			helper.addClosedListener(ords::remove);
		}

		var building = new CompletableFuture<StringOrds>();
		var found = fields.putIfAbsent(field, building);
		if(found == null) {
			try {
				ordinalBuilds.increment();
				var built = StringOrds.build(reader, field);
				building.complete(built);
				return built;
			} catch(Throwable t) {
				/*
				 * Dropped before the waiters are told, so that whoever asks
				 * next starts a build of their own rather than reading the
				 * failure back.
				 */
				fields.remove(field, building);
				building.completeExceptionally(t);
				throw t;
			}
		}

		try {
			return found.get();
		} catch(ExecutionException e) {
			var cause = e.getCause();
			if(cause instanceof IOException io) {
				throw io;
			}

			if(cause instanceof RuntimeException re) {
				throw re;
			}

			if(cause instanceof Error error) {
				throw error;
			}

			throw new IOException(cause);
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the ordinals of " + field, e);
		}
	}

	/**
	 * Get how many ordinal maps have been built since the node started, for a
	 * test of what a warm leaves for a search to do.
	 */
	static long ordinalBuilds() {
		return ordinalBuilds.sum();
	}

	/**
	 * Get whether the ordinals of the given field are already lined up for
	 * the given reader, without building them - for a test of what a warm
	 * left behind.
	 *
	 * @param reader
	 * @param field
	 * @return
	 */
	static boolean holdsStringOrds(IndexReader reader, String field) {
		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			return false;
		}

		var fields = ords.get(helper.getKey());
		if(fields == null) {
			return false;
		}

		var building = fields.get(field);
		return building != null && building.isDone() && !building.isCompletedExceptionally();
	}

	/**
	 * The distinct values of one field across the segments of a reader, and
	 * how a segment's ordinal maps onto them.
	 *
	 * @param map
	 *   the segment ordinals lined up against one another, or {@code null}
	 *   where the reader has at most one segment and its ordinals already
	 *   stand for the whole field
	 * @param cardinality
	 *   how many distinct values the field holds across the reader
	 */
	record StringOrds(OrdinalMap map, long cardinality) {
		static StringOrds build(IndexReader reader, String field) throws IOException {
			var leaves = reader.leaves();
			if(leaves.isEmpty()) {
				return new StringOrds(null, 0);
			}

			if(leaves.size() == 1) {
				var values = leaves.get(0).reader().getSortedSetDocValues(field);
				return new StringOrds(null, values == null ? 0 : values.getValueCount());
			}

			var values = new SortedSetDocValues[leaves.size()];
			for(var i = 0; i < values.length; i++) {
				values[i] = DocValues.getSortedSet(leaves.get(i).reader(), field);
			}

			var helper = reader.getReaderCacheHelper();
			var map = OrdinalMap.build(
				helper == null ? null : helper.getKey(),
				values,
				PackedInts.DEFAULT
			);

			return new StringOrds(map, map.getValueCount());
		}
	}

	/**
	 * Get what the given facet answered over the given scope, or {@code null}
	 * where nothing was kept - see {@link #keepScopeCounts}.
	 *
	 * @param reader
	 * @param scope
	 *   the scope the facet is counted over
	 * @param facet
	 * @return
	 */
	static SearchResult.Facet scopeCountsOf(IndexReader reader, Scope scope, Facet facet) {
		if(!SCOPE_CACHE) {
			scopeMisses.increment();
			return null;
		}

		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			scopeMisses.increment();
			return null;
		}

		var kept = scopeCounts.get(helper.getKey());
		var counts = kept == null ? null : kept.get(new ScopeKey(scope, shapeOf(facet)));
		if(counts == null) {
			scopeMisses.increment();
		} else {
			scopeHits.increment();
		}

		return counts;
	}

	/**
	 * Keep what a facet answered over a scope, for as long as the reader is
	 * open and the scope stays among the {@code SCOPE_LIMIT} most recently
	 * asked for. Not kept for a reader that cannot say when it closes, and
	 * not kept when the search has run past its {@link SearchDeadline}, as
	 * the counts then describe part of the index.
	 *
	 * @param reader
	 * @param scope
	 *   the scope the facet was counted over
	 * @param facet
	 * @param counts
	 */
	static void keepScopeCounts(
		IndexReader reader,
		Scope scope,
		Facet facet,
		SearchResult.Facet counts
	) {
		if(!SCOPE_CACHE) {
			return;
		}

		var helper = reader.getReaderCacheHelper();
		if(helper == null || SearchDeadline.exceeded()) {
			return;
		}

		var key = helper.getKey();
		var kept = scopeCounts.get(key);
		if(kept == null) {
			kept = scopeCounts.computeIfAbsent(key, ignored -> new Recent<>());

			/*
			 * Registered against the key rather than against the map, so a
			 * reader that closed while this was being built drops what was put
			 * under it instead of leaving it behind.
			 */
			helper.addClosedListener(scopeCounts::remove);
		}

		kept.put(new ScopeKey(scope, shapeOf(facet)), counts);
	}

	/**
	 * The part of a facet that decides what it counts: everything but the
	 * name the answer is keyed by and the filters it leaves out, which have
	 * done their work by the time the scope is known. Two facets alike in
	 * this answer alike over one scope, whatever they are called.
	 */
	private static Facet shapeOf(Facet facet) {
		return new Facet(
			facet.field(),
			facet.field(),
			facet.limit(),
			facet.order(),
			facet.ranges(),
			facet.path(),
			facet.depth(),
			Lists.immutable.empty(),
			facet.prefix(),
			facet.prefixEdits()
		);
	}

	/**
	 * Get how many matches the given scope holds, or {@code null} where
	 * nothing was kept - see {@link #keepScopeTotal}.
	 *
	 * @param reader
	 * @param scope
	 * @return
	 */
	static Long scopeTotalOf(IndexReader reader, Scope scope) {
		if(!SCOPE_CACHE) {
			return null;
		}

		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			return null;
		}

		var kept = scopeTotals.get(helper.getKey());
		return kept == null ? null : kept.get(scope);
	}

	/**
	 * Keep how many matches a scope holds, for as long as the reader is open
	 * and the scope stays among the {@code SCOPE_LIMIT} most recently asked
	 * for. Not kept for a reader that cannot say when it closes, and not kept
	 * when the search has run past its {@link SearchDeadline}, as the total is
	 * then of part of the index.
	 *
	 * @param reader
	 * @param scope
	 * @param total
	 */
	static void keepScopeTotal(IndexReader reader, Scope scope, long total) {
		if(!SCOPE_CACHE) {
			return;
		}

		var helper = reader.getReaderCacheHelper();
		if(helper == null || SearchDeadline.exceeded()) {
			return;
		}

		var key = helper.getKey();
		var kept = scopeTotals.get(key);
		if(kept == null) {
			kept = scopeTotals.computeIfAbsent(key, ignored -> new Recent<>());
			helper.addClosedListener(scopeTotals::remove);
		}

		kept.put(scope, total);
	}

	/**
	 * What a facet is counted over, as far as the reader is concerned:
	 * everything that decides which matches a scope holds beyond the reader
	 * itself.
	 *
	 * The clauses are the query records the scope was compiled from, which
	 * compare by value - a scope asked for again is the same key. The one
	 * clause that does not is {@code knn}, whose vector is an array: two
	 * searches by vector never share an entry, which costs a walk and never
	 * an answer from the wrong one. What the clauses compile to also depends
	 * on the search settings and the definition, both of which can change
	 * under an open reader, so their versions are part of the key: an entry
	 * counted under a synonym set no longer in force is a miss rather than a
	 * stale answer.
	 *
	 * @param path
	 *   the object field whose values the matches are, or {@code null} where
	 *   they are documents of the index
	 * @param locale
	 *   the locale of the search, or {@code null} where it named none - what
	 *   decides the variant a localized field is matched and counted in
	 * @param settingsVersion
	 *   the version of the search settings the search ran under, or
	 *   {@code null} where it ran under none
	 * @param definitionVersion
	 *   the version of the definition the search ran under
	 * @param clauses
	 *   the clauses narrowing the scope, empty for everything the reader holds
	 */
	record Scope(
		String path,
		String locale,
		String settingsVersion,
		String definitionVersion,
		ImmutableList<Query> clauses
	) {
	}

	/**
	 * One ask for counts: a facet over a scope.
	 */
	private record ScopeKey(Scope scope, Facet facet) {
	}

	/**
	 * At most {@code SCOPE_LIMIT} entries, the one asked for least recently
	 * going first. Locked around every read and write: a read is what moves
	 * an entry to the front, and the map cannot be shared without it.
	 */
	private static final class Recent<K, V> {
		private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				if(size() > SCOPE_LIMIT) {
					scopeEvictions.increment();
					return true;
				}

				return false;
			}
		};

		synchronized V get(K key) {
			return entries.get(key);
		}

		synchronized void put(K key, V value) {
			entries.put(key, value);
		}
	}

	/**
	 * Get what the given segment counted for everything the reader holds, or
	 * {@code null} where nothing was kept - see {@link #keepSegmentCounts}.
	 *
	 * @param context
	 *   the segment being read
	 * @param field
	 *   the Lucene field the values were written under
	 * @param mode
	 *   what the matches were and what the counts are of
	 * @param path
	 *   the object field whose every value was counted, or {@code null} where
	 *   every document was
	 * @param type
	 *   the shape the counts were kept in, decided by whoever counts the field
	 * @return
	 */
	static <T> T segmentCountsOf(
		LeafReaderContext context,
		String field,
		FacetMatches.Mode mode,
		String path,
		Class<T> type
	) {
		var helper = context.reader().getReaderCacheHelper();
		if(helper == null) {
			segmentMisses.increment();
			return null;
		}

		var kept = segmentCounts.get(helper.getKey());
		var counts = kept == null ? null : kept.get(new SegmentKey(field, mode, path));
		if(counts == null) {
			segmentMisses.increment();
			return null;
		}

		segmentHits.increment();
		return type.cast(counts);
	}

	/**
	 * Keep what a segment counted for everything the reader holds, in the
	 * segment's own ordinal space, for as long as the segment reader is open -
	 * across reopens of the reader around it, until the segment takes a
	 * deletion or is merged away. Not kept for a segment that cannot say when
	 * its reader closes, and not kept when the search has run past its
	 * {@link SearchDeadline}, as the matches it counted may then be part of
	 * the segment.
	 *
	 * Uncapped: what is counted is a field of the definition in one of a few
	 * modes rather than a shape the caller chose, so a segment holds at most
	 * a few entries per faceted field. An entry costs about as much as the
	 * distinct values of the field in the segment.
	 *
	 * @param context
	 *   the segment being read
	 * @param field
	 *   the Lucene field the values were written under
	 * @param mode
	 *   what the matches were and what the counts are of
	 * @param path
	 *   the object field whose every value was counted, or {@code null} where
	 *   every document was
	 * @param counts
	 *   the counts, never written to again by the caller
	 */
	static void keepSegmentCounts(
		LeafReaderContext context,
		String field,
		FacetMatches.Mode mode,
		String path,
		Object counts
	) {
		var helper = context.reader().getReaderCacheHelper();
		if(helper == null || SearchDeadline.exceeded()) {
			return;
		}

		var key = helper.getKey();
		var kept = segmentCounts.get(key);
		if(kept == null) {
			kept = segmentCounts.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());

			/*
			 * Registered against the key rather than against the map, so a
			 * segment reader that closed while this was being counted drops
			 * what was put under it instead of leaving it behind.
			 */
			helper.addClosedListener(segmentCounts::remove);
		}

		kept.put(new SegmentKey(field, mode, path), counts);
	}

	/**
	 * What one segment counted for everything the reader holds is keyed by:
	 * the field, what the matches were - every document or every value of
	 * one path - and what a count of them means.
	 */
	private record SegmentKey(String field, FacetMatches.Mode mode, String path) {
	}

	/**
	 * Get how the caches here have answered so far, for the meters a node
	 * reports and for tests of what is kept.
	 *
	 * @return
	 */
	static FacetCacheStats stats() {
		return new FacetCacheStats(
			scopeHits.sum(),
			scopeMisses.sum(),
			scopeEvictions.sum(),
			segmentHits.sum(),
			segmentMisses.sum(),
			heldBytes()
		);
	}

	/**
	 * Estimate what everything kept here takes on the heap: the ordinal maps
	 * per reader, the columns, postings and decoded trees per segment core,
	 * and the whole-reader counts per segment reader. What a facet answered
	 * per scope is left out - a bounded number of small results per reader.
	 *
	 * An estimate rather than a measurement: the arrays are sized by their
	 * length and a map by its entries, which is within a small factor of what
	 * the JVM allocates and costs a walk of the entries rather than of what
	 * they hold. Read when a node is scraped.
	 *
	 * @return
	 */
	static long heldBytes() {
		var bytes = 0L;

		for(var fields : ords.values()) {
			for(var building : fields.values()) {
				if(building.isDone() && !building.isCompletedExceptionally()) {
					var map = building.join().map();
					bytes += map == null ? 0 : map.ramBytesUsed();
				}
			}
		}

		for(var kept : segmentCounts.values()) {
			for(var counts : kept.values()) {
				bytes += switch(counts) {
					case int[] ints -> ARRAY_HEADER + 4L * ints.length;
					case long[] longs -> ARRAY_HEADER + 8L * longs.length;
					case LongLongMap map -> ARRAY_HEADER + MAP_ENTRY * (long) map.size();
					default -> 0;
				};
			}
		}

		for(var fields : ordColumns.values()) {
			for(var column : fields.values()) {
				bytes += switch(column) {
					case FacetColumns.Ords.Single single ->
						ARRAY_HEADER + 4L * single.ord().length;
					case FacetColumns.Ords.Multi multi ->
						2 * ARRAY_HEADER + 4L * multi.starts().length + 4L * multi.ords().length;
				};
			}
		}

		for(var fields : longColumns.values()) {
			for(var column : fields.values()) {
				bytes += switch(column) {
					case FacetColumns.Longs.Single single ->
						ARRAY_HEADER + 8L * single.value().length
							+ (single.present() == null ? 0 : single.present().ramBytesUsed());
					case FacetColumns.Longs.Multi multi ->
						2 * ARRAY_HEADER + 4L * multi.starts().length + 8L * multi.values().length;
				};
			}
		}

		for(var postings : List.of(ordPostings, rolledUpOrdPostings)) {
			for(var fields : postings.values()) {
				for(var inverted : fields.values()) {
					bytes += 3 * ARRAY_HEADER
						+ 4L * inverted.starts().length
						+ 4L * inverted.docs().length;
					for(var dense : inverted.dense()) {
						bytes += dense == null ? 0 : dense.ramBytesUsed();
					}
				}
			}
		}

		for(var postings : List.of(longPostings, rolledUpLongPostings)) {
			for(var fields : postings.values()) {
				for(var sorted : fields.values()) {
					bytes += 2 * ARRAY_HEADER
						+ 8L * sorted.values().length
						+ 4L * sorted.docs().length;
				}
			}
		}

		for(var fields : foldedTerms.values()) {
			for(var folded : fields.values()) {
				bytes += folded.bytesHeld();
			}
		}

		for(var fields : values.values()) {
			for(var tree : fields.values()) {
				bytes += 3 * ARRAY_HEADER + 4L * tree.levels().length;
				for(var i = 0; i < tree.paths().length; i++) {
					bytes += STRING_HEADER + tree.paths()[i].length();
					if(tree.normalized()[i] != tree.paths()[i]) {
						bytes += STRING_HEADER + tree.normalized()[i].length();
					}
				}
			}
		}

		return bytes;
	}

	/**
	 * The values of one segment of a field folded by an analyzer and sorted,
	 * per segment core, field and analyzer - see {@link #foldedTermsOf}.
	 */
	private static final Map<IndexReader.CacheKey, Map<FoldedKey, FoldedTerms>> foldedTerms =
		new ConcurrentHashMap<>();

	/**
	 * What one folded dictionary is keyed by under a segment: the field, and
	 * the analyzer that folded it. Analyzers are shared per chain and locale,
	 * so the same folding is the same instance.
	 */
	private record FoldedKey(String field, Analyzer normalizer) {
	}

	/**
	 * Get the values of the given field in the given segment folded by the
	 * given analyzer and sorted, building them the first time the segment is
	 * asked for under that analyzer. Kept per segment core, the way
	 * {@link #ordsOf} keeps ordinals: a segment is never changed, and what an
	 * analyzer folds a value to is as fixed as the analyzer.
	 *
	 * A segment that cannot say when its core goes away is not kept, as an
	 * entry for it could never be dropped; its values are folded and handed
	 * back without being remembered.
	 *
	 * @param context
	 *   the segment being read
	 * @param field
	 *   the Lucene field the values were written under
	 * @param normalizer
	 *   what folds a value
	 * @param docValues
	 *   the doc values of that field in that segment, read by ordinal
	 * @return
	 * @throws IOException
	 */
	static FoldedTerms foldedTermsOf(
		LeafReaderContext context,
		String field,
		Analyzer normalizer,
		SortedSetDocValues docValues
	) throws IOException {
		var helper = context.reader().getCoreCacheHelper();
		if(helper == null) {
			return FoldedTerms.build(field, normalizer, docValues);
		}

		var key = helper.getKey();
		var fields = foldedTerms.get(key);
		if(fields == null) {
			fields = foldedTerms.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());

			/*
			 * Registered against the key rather than against the map, so a
			 * segment that went away while this was being built drops what was
			 * put under it instead of leaving it behind.
			 */
			helper.addClosedListener(foldedTerms::remove);
		}

		var foldedKey = new FoldedKey(field, normalizer);
		var folded = fields.get(foldedKey);
		if(folded == null) {
			folded = FoldedTerms.build(field, normalizer, docValues);
			fields.put(foldedKey, folded);
		}

		return folded;
	}

	/**
	 * What an array costs beyond its elements.
	 */
	private static final long ARRAY_HEADER = 16;

	/**
	 * What a string costs beyond its characters, at one byte per character
	 * the way the JVM stores Latin text.
	 */
	private static final long STRING_HEADER = 40;

	/**
	 * What one entry of a primitive map costs, counting the two slots of the
	 * open-addressed table and its slack.
	 */
	private static final long MAP_ENTRY = 32;

	/**
	 * Get every value one segment holds for a field counted a level at a time,
	 * by ordinal, decoding and parsing them the first time the segment is asked
	 * for.
	 *
	 * Kept per segment and field alone: the separator and the normalization are
	 * what the levels of the field were written through, so they are as fixed
	 * for the segment as the values themselves.
	 *
	 * A segment that cannot say when its core goes away is not kept, as an
	 * entry for it could never be dropped; its values are decoded and handed
	 * back without being remembered.
	 *
	 * @param context
	 *   the segment being read
	 * @param field
	 *   the Lucene field the values were written under
	 * @param docValues
	 *   the doc values of that field in that segment, not advanced by this
	 * @param separator
	 *   what separates one level of a path from the next
	 * @param normalize
	 *   how a path is read before two of them are called the same
	 * @return
	 * @throws IOException
	 */
	static Hierarchy hierarchyOf(
		LeafReaderContext context,
		String field,
		SortedSetDocValues docValues,
		String separator,
		UnaryOperator<String> normalize
	) throws IOException {
		var helper = context.reader().getCoreCacheHelper();
		if(helper == null) {
			return decode(docValues, separator, normalize);
		}

		var key = helper.getKey();
		var fields = values.get(key);
		if(fields == null) {
			fields = values.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());

			/*
			 * Registered against the key rather than against the map, so a
			 * segment that went away while this was being built drops what was
			 * put under it instead of leaving it behind.
			 */
			helper.addClosedListener(values::remove);
		}

		var decoded = fields.get(field);
		if(decoded == null) {
			decoded = decode(docValues, separator, normalize);
			fields.put(field, decoded);
		}

		return decoded;
	}

	private static Hierarchy decode(
		SortedSetDocValues docValues,
		String separator,
		UnaryOperator<String> normalize
	) throws IOException {
		var paths = new String[(int) docValues.getValueCount()];
		var normalized = new String[paths.length];
		var levels = new int[paths.length];

		for(var ord = 0; ord < paths.length; ord++) {
			var path = docValues.lookupOrd(ord).utf8ToString();
			var read = normalize.apply(path);

			paths[ord] = path;
			// The same string where normalizing changed nothing, held once
			normalized[ord] = read.equals(path) ? path : read;
			levels[ord] = Hierarchy.levelsOf(path, separator);
		}

		return new Hierarchy(paths, normalized, levels);
	}

	/**
	 * The values of one segment of a field whose values are paths, with what
	 * deciding a scope reads of each: the path as it was written, the path as
	 * narrowing reads it, and how deep it reaches.
	 *
	 * @param paths
	 *   each value as it was written, by ordinal - what is counted and answered
	 * @param normalized
	 *   each value as narrowing reads it, by ordinal - what a prefix is judged
	 *   against
	 * @param levels
	 *   how many levels deep each value reaches, by ordinal, counting from one
	 */
	record Hierarchy(
		String[] paths,
		String[] normalized,
		int[] levels
	) {
		/**
		 * Get how many levels deep a path reaches, counting from one.
		 */
		static int levelsOf(String path, String separator) {
			var count = 1;
			for(
				var at = path.indexOf(separator);
				at >= 0;
				at = path.indexOf(separator, at + separator.length())
			) {
				count++;
			}

			return count;
		}
	}
}
