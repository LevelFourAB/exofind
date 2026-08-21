package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
	 * decoded once per segment rather than once per search - reading them walks
	 * the term dictionary of the segment, which costs the same however few
	 * documents matched. Small because such a field holds a tree of levels
	 * rather than one value per document. Keyed by the core of the segment,
	 * which survives the reader being reopened, and dropped when the core goes
	 * away.
	 */
	private static final Map<IndexReader.CacheKey, Map<String, String[]>> values =
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
	 * Get every value one segment holds for a field, by ordinal, decoding them
	 * the first time the segment is asked for.
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
	 * @return
	 * @throws IOException
	 */
	static String[] valuesOf(
		LeafReaderContext context,
		String field,
		SortedSetDocValues docValues
	) throws IOException {
		var helper = context.reader().getCoreCacheHelper();
		if(helper == null) {
			return decode(docValues);
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
			decoded = decode(docValues);
			fields.put(field, decoded);
		}

		return decoded;
	}

	private static String[] decode(SortedSetDocValues docValues) throws IOException {
		var decoded = new String[(int) docValues.getValueCount()];
		for(var ord = 0; ord < decoded.length; ord++) {
			decoded[ord] = docValues.lookupOrd(ord).utf8ToString();
		}

		return decoded;
	}
}
