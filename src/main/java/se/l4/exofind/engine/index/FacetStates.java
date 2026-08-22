package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.apache.lucene.facet.StringDocValuesReaderState;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedSetDocValues;

/**
 * What counting a string facet has to know about a reader before it can count,
 * kept for as long as the reader is open.
 *
 * Counting the values of a field across segments needs the ordinals of each
 * segment lined up against one another, which is read out of the term
 * dictionaries and costs as much whether the search matched everything or
 * nothing. It says nothing about the search, only about the reader, so it is
 * built once per reader and field and answered from here after that - a search
 * over a handful of documents would otherwise spend most of its time preparing
 * to count them.
 *
 * A reader is only ever replaced, never changed, so an entry stays true for as
 * long as the reader it was built from is open and is dropped when it closes.
 * That is the same lifetime the reader's own caches have and is why the key is
 * the one Lucene hands out for exactly this.
 */
final class FacetStates {
	private static final Map<IndexReader.CacheKey, Map<String, StringDocValuesReaderState>> states =
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

	private FacetStates() {
	}

	/**
	 * Get whether any segment of the reader holds doc values for the given
	 * field, which is what counting reads.
	 *
	 * A field no document ever held a value in has none, which is no counts
	 * rather than a problem - that the field can be faceted at all is checked
	 * before a counter is asked for.
	 *
	 * @param reader
	 * @param field
	 * @return
	 */
	static boolean hasValues(IndexReader reader, String field) {
		for(var leaf : reader.leaves()) {
			var info = leaf.reader().getFieldInfos().fieldInfo(field);
			if(info != null && info.getDocValuesType() != DocValuesType.NONE) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Get the state for counting the given field of the given reader, building
	 * it the first time it is asked for.
	 *
	 * A reader that cannot say when it closes is not kept, as an entry for it
	 * could never be dropped; it is built and handed back the way it was before
	 * there was a cache here.
	 *
	 * @param reader
	 * @param field
	 * @return
	 * @throws IOException
	 */
	static StringDocValuesReaderState of(IndexReader reader, String field)
		throws IOException
	{
		var helper = reader.getReaderCacheHelper();
		if(helper == null) {
			return new StringDocValuesReaderState(reader, field);
		}

		var key = helper.getKey();
		var fields = states.get(key);
		if(fields == null) {
			fields = states.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());

			/*
			 * Registered against the key rather than against the map, so a
			 * reader that closed while this was being built drops what was put
			 * under it instead of leaving it behind.
			 */
			helper.addClosedListener(states::remove);
		}

		var state = fields.get(field);
		if(state == null) {
			state = new StringDocValuesReaderState(reader, field);
			fields.put(field, state);
		}

		return state;
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
