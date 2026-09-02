package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.OrdinalMap;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.util.packed.PackedInts;

import se.l4.exofind.engine.query.Facet;
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
 * The counts of a facet whose scope is everything say just as little about any
 * one search: a search nothing narrows counts the same matches every time, and
 * so does a facet counting sideways of the only filter of the search, so those
 * counts are kept here too and a walk of every document is paid once per
 * reader rather than once per search.
 *
 * A search answering with the values of an object field counts values rather
 * than documents, and an unnarrowed one counts every value of that path -
 * which is as fixed for a reader as its documents are. Those counts are kept
 * here as well, under the path they count, because the same facet over another
 * path counts other things. A path holds several times the documents, so this
 * is where the saving is largest.
 *
 * What a segment holds is fixed for even longer than a reader: a segment is
 * never changed, only merged away, so what is read out of one alone - the
 * values of a facet field laid out flat, see {@link FacetColumns}, and the
 * decoded levels of a tree - is kept per segment core and survives the reader
 * being reopened around it.
 *
 * A reader is only ever replaced, never changed, so an entry stays true for as
 * long as the reader it was built from is open and is dropped when it closes.
 * That is the same lifetime the reader's own caches have and is why the key is
 * the one Lucene hands out for exactly this.
 */
final class FacetStates {
	/**
	 * How many facets one reader keeps whole-index counts for. The shape of a
	 * facet is the caller's to choose, so the entries under one reader are
	 * capped rather than trusted to be few; a facet arriving after the cap is
	 * counted as if there were no cache.
	 */
	private static final int WHOLE_LIMIT = 256;

	/**
	 * The ordinals of one field's segments lined up against one another, per
	 * reader and field - see {@link #stringOrdsOf}.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, StringOrds>> ords =
		new ConcurrentHashMap<>();

	/**
	 * The counts of one facet over everything the reader holds, keyed by the
	 * facet asked for, the locale it was asked under, and the object field path
	 * whose values were counted where they were values rather than documents.
	 */
	private static final Map<IndexReader.CacheKey, Map<WholeKey, SearchResult.Facet>> wholeCounts =
		new ConcurrentHashMap<>();

	/**
	 * How many documents the reader holds, counted the way a search with
	 * nothing narrowing it counts them.
	 */
	private static final Map<IndexReader.CacheKey, Long> wholeTotals =
		new ConcurrentHashMap<>();

	/**
	 * How many values of one object field path the reader holds, counted the
	 * way a search answering with them and narrowed by nothing counts them.
	 *
	 * Uncapped where {@link #wholeCounts} is capped: a path is an object field
	 * of the definition rather than a shape the caller chose, so a reader has
	 * as many entries here as the index has object fields.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, Long>> wholeValueTotals =
		new ConcurrentHashMap<>();

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

	private FacetStates() {
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
	 * A reader that cannot say when it closes is not kept, as an entry for it
	 * could never be dropped; its ordinals are lined up and handed back
	 * without being remembered.
	 *
	 * @param reader
	 * @param field
	 * @return
	 * @throws IOException
	 */
	static StringOrds stringOrdsOf(IndexReader reader, String field)
		throws IOException
	{
		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
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

		var built = fields.get(field);
		if(built == null) {
			built = StringOrds.build(reader, field);
			fields.put(field, built);
		}

		return built;
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
	 * Get what the given facet counted over everything the reader holds, or
	 * {@code null} where nothing was kept - see
	 * {@link #keepWholeCounts(IndexReader, String, String, Facet, SearchResult.Facet)}.
	 *
	 * @param reader
	 * @param path
	 *   the object field path whose values were counted, or {@code null} where
	 *   the counts are of documents
	 * @param locale
	 *   the locale of the search, or {@code null} where it named none
	 * @param facet
	 * @return
	 */
	static SearchResult.Facet wholeCountsOf(
		IndexReader reader,
		String path,
		String locale,
		Facet facet
	) {
		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			return null;
		}

		var facets = wholeCounts.get(helper.getKey());
		return facets == null ? null : facets.get(new WholeKey(path, locale, facet));
	}

	/**
	 * Keep what a facet counted over everything the reader holds, for as long
	 * as the reader is open. Not kept for a reader that cannot say when it
	 * closes, or one already holding counts for {@code WHOLE_LIMIT} facets.
	 *
	 * @param reader
	 * @param path
	 *   the object field path whose values were counted, or {@code null} where
	 *   the counts are of documents
	 * @param locale
	 *   the locale of the search, or {@code null} where it named none
	 * @param facet
	 * @param counts
	 */
	static void keepWholeCounts(
		IndexReader reader,
		String path,
		String locale,
		Facet facet,
		SearchResult.Facet counts
	) {
		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			return;
		}

		var key = helper.getKey();
		var facets = wholeCounts.get(key);
		if(facets == null) {
			facets = wholeCounts.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());

			/*
			 * Registered against the key rather than against the map, so a
			 * reader that closed while this was being built drops what was put
			 * under it instead of leaving it behind.
			 */
			helper.addClosedListener(wholeCounts::remove);
		}

		if(facets.size() >= WHOLE_LIMIT) {
			return;
		}

		facets.put(new WholeKey(path, locale, facet), counts);
	}

	/**
	 * One ask for whole-index counts: the facet, the locale it was asked under,
	 * which is what decides the values a localized field counts, and the path
	 * whose values were counted, which is what decides whether the counts are
	 * of values at all and of which of them.
	 *
	 * @param path
	 *   the object field path whose values were counted, or {@code null} where
	 *   the counts are of documents
	 */
	private record WholeKey(String path, String locale, Facet facet) {
	}

	/**
	 * Get how many documents the reader holds, as a search with nothing
	 * narrowing it counts them, or {@code null} where nothing was kept - see
	 * {@link #keepWholeTotal(IndexReader, long)}.
	 *
	 * @param reader
	 * @return
	 */
	static Long wholeTotalOf(IndexReader reader) {
		var helper = reader.getReaderCacheHelper();
		return helper == null ? null : wholeTotals.get(helper.getKey());
	}

	/**
	 * Keep how many documents the reader holds, for as long as it is open. Not
	 * kept for a reader that cannot say when it closes.
	 *
	 * @param reader
	 * @param total
	 */
	static void keepWholeTotal(IndexReader reader, long total) {
		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			return;
		}

		if(wholeTotals.putIfAbsent(helper.getKey(), total) == null) {
			helper.addClosedListener(wholeTotals::remove);
		}
	}

	/**
	 * Get how many values of the given object field path the reader holds, as a
	 * search answering with them and narrowed by nothing counts them, or
	 * {@code null} where nothing was kept - see
	 * {@link #keepWholeValueTotal(IndexReader, String, long)}.
	 *
	 * @param reader
	 * @param path
	 *   the object field path the values belong to
	 * @return
	 */
	static Long wholeValueTotalOf(IndexReader reader, String path) {
		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			return null;
		}

		var paths = wholeValueTotals.get(helper.getKey());
		return paths == null ? null : paths.get(path);
	}

	/**
	 * Keep how many values of one object field path the reader holds, for as
	 * long as it is open. Not kept for a reader that cannot say when it closes.
	 *
	 * @param reader
	 * @param path
	 *   the object field path the values belong to
	 * @param total
	 */
	static void keepWholeValueTotal(IndexReader reader, String path, long total) {
		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			return;
		}

		var key = helper.getKey();
		var paths = wholeValueTotals.get(key);
		if(paths == null) {
			paths = wholeValueTotals.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());

			/*
			 * Registered against the key rather than against the map, so a
			 * reader that closed while this was being built drops what was put
			 * under it instead of leaving it behind.
			 */
			helper.addClosedListener(wholeValueTotals::remove);
		}

		paths.put(path, total);
	}

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
