package se.l4.exofind.engine.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.util.BytesRef;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * Cache over the stored fields of documents, shared by every index of the
 * node.
 *
 * Stored fields are kept compressed in blocks, and reading a document
 * decompresses part of its block every time however recently it was last read.
 * What a document's stored fields hold cannot change for as long as its
 * segment exists, so the read is cached rather than repeated.
 *
 * There is one cache for the node rather than one per index, bounded by
 * weight. A node holds an unbounded number of indexes and nothing can say
 * ahead of time which of them will be read; a shared budget lets the indexes
 * that are read hold the space, and the admission policy keeps one pass over
 * something cold from pushing out what is hot.
 *
 * Entries are keyed by the segment - {@link IndexReader.CacheHelper its core
 * key} and the document's id inside it - rather than by index or searcher. A
 * commit replaces the searcher but keeps the segments it did not touch, and
 * with them their entries; and a segment that closes, on a merge or the index
 * closing, takes its entries with it through the listener registered on the
 * core. What is cached is every stored field of the document, serialized to
 * one array of bytes: the compression makes reading everything cost the same
 * as reading some of it, one entry answers whichever fields a search asks
 * for, and bytes make the weight of the cache what it actually holds instead
 * of an estimate over object graphs. Reading through the cache filters the
 * same names the read would otherwise have handed Lucene, so a cached read
 * and a direct one answer alike.
 *
 * Safe for concurrent use; a document read by two searches at once is loaded
 * once.
 */
public final class DocumentCache {
	/**
	 * What an entry costs beyond its serialized bytes: the key, the array
	 * header and the cache's own bookkeeping per entry. An estimate, but a
	 * fixed one - the part of the weight that could drift with what is cached
	 * is measured, not estimated.
	 */
	private static final int ENTRY_OVERHEAD = 96;

	private static final byte STRING = 0;
	private static final byte BINARY = 1;
	private static final byte INT = 2;
	private static final byte LONG = 3;
	private static final byte FLOAT = 4;
	private static final byte DOUBLE = 5;

	private record Key(IndexReader.CacheKey core, int doc) {
	}

	/**
	 * The cache, or {@code null} when caching is off and every read goes
	 * straight to Lucene.
	 */
	private final Cache<Key, byte[]> cache;

	/**
	 * The cores whose closing already removes their entries, so a listener is
	 * registered once per core rather than once per read.
	 */
	private final Set<IndexReader.CacheKey> watched;

	private DocumentCache(Cache<Key, byte[]> cache) {
		this.cache = cache;
		this.watched = ConcurrentHashMap.newKeySet();
	}

	/**
	 * Get a cache that caches nothing, for a node that did not ask for one.
	 * Reads through it go straight to Lucene, so a caller holds one whichever
	 * way the node is set.
	 */
	public static DocumentCache disabled() {
		return new DocumentCache(null);
	}

	/**
	 * Get a cache bounded to roughly the given number of bytes.
	 *
	 * @param maxSize
	 *   what the entries may weigh together, in bytes
	 */
	public static DocumentCache sized(long maxSize) {
		return new DocumentCache(
			Caffeine.newBuilder()
				.maximumWeight(maxSize)
				.<Key, byte[]>weigher((key, value) -> ENTRY_OVERHEAD + value.length)
				.recordStats()
				.build()
		);
	}

	/**
	 * Read the stored fields of one document, through the cache when there is
	 * one.
	 *
	 * @param searcher
	 *   the searcher the id belongs to
	 * @param storedFields
	 *   the searcher's stored fields, read on a miss
	 * @param docId
	 *   Lucene id of the document, across the whole searcher
	 * @param names
	 *   the stored fields wanted, or {@code null} for all of them
	 * @return
	 *   the document, holding the fields that were wanted
	 * @throws IOException
	 */
	public org.apache.lucene.document.Document read(
		IndexSearcher searcher,
		StoredFields storedFields,
		int docId,
		Set<String> names
	) throws IOException {
		if(cache == null) {
			return names == null
				? storedFields.document(docId)
				: storedFields.document(docId, names);
		}

		var leaves = searcher.getIndexReader().leaves();
		var leaf = leaves.get(ReaderUtil.subIndex(docId, leaves));
		var helper = leaf.reader().getCoreCacheHelper();
		if(helper == null) {
			/*
			 * A reader wrapped in a way that left no stable identity to key on
			 * or hang the removal off. Nothing this node opens reads that way,
			 * but reading is still right when something does - just not through
			 * the cache.
			 */
			return names == null
				? storedFields.document(docId)
				: storedFields.document(docId, names);
		}

		watch(helper);

		var key = new Key(helper.getKey(), docId - leaf.docBase);

		byte[] bytes;
		try {
			bytes = cache.get(key, k -> encode(storedFields, docId));
		} catch(UncheckedIOException e) {
			throw e.getCause();
		}

		return decode(bytes, names);
	}

	/**
	 * Have the closing of a core remove the entries keyed on it. Without this
	 * the entries of a merged-away segment sit dead in the cache until the
	 * eviction policy gets to them, which it only does under pressure.
	 *
	 * The removal walks every key the cache holds. Cores close at the pace of
	 * merges and commits, not of searches, so the walk stays rare next to the
	 * reads it keeps honest.
	 */
	private void watch(IndexReader.CacheHelper helper) {
		var core = helper.getKey();
		if(watched.add(core)) {
			helper.addClosedListener(closed -> {
				watched.remove(closed);
				cache.asMap().keySet().removeIf(key -> key.core() == closed);
			});
		}
	}

	/**
	 * Read every stored field of a document and lay the values out as one run
	 * of bytes: for each field its name, a type and the value. Only the types
	 * a stored field can hold have a tag here, which is what lets
	 * {@link #decode} give the values back exactly as Lucene would have.
	 */
	private static byte[] encode(StoredFields storedFields, int docId) {
		try {
			var doc = storedFields.document(docId);
			var out = new ByteBuffersDataOutput();

			for(var field : doc.getFields()) {
				out.writeString(field.name());

				var number = field.numericValue();
				if(number != null) {
					switch(number) {
						case Integer v -> {
							out.writeByte(INT);
							out.writeZInt(v);
						}
						case Long v -> {
							out.writeByte(LONG);
							out.writeZLong(v);
						}
						case Float v -> {
							out.writeByte(FLOAT);
							out.writeInt(Float.floatToRawIntBits(v));
						}
						case Double v -> {
							out.writeByte(DOUBLE);
							out.writeLong(Double.doubleToRawLongBits(v));
						}
						default -> throw new IllegalStateException(
							"A stored field held a " + number.getClass().getSimpleName()
								+ ", which stored fields can not hold"
						);
					}
					continue;
				}

				var binary = field.binaryValue();
				if(binary != null) {
					out.writeByte(BINARY);
					out.writeVInt(binary.length);
					out.writeBytes(binary.bytes, binary.offset, binary.length);
					continue;
				}

				var string = field.stringValue();
				if(string != null) {
					out.writeByte(STRING);
					out.writeString(string);
					continue;
				}

				throw new IllegalStateException(
					"The stored field " + field.name() + " held no value"
				);
			}

			return out.toArrayCopy();
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Turn cached bytes back into the document Lucene would have read, cut
	 * down to the fields that were wanted. A field that is not wanted is
	 * stepped over rather than read, the same answer a direct read gets from
	 * handing Lucene the names.
	 */
	private static org.apache.lucene.document.Document decode(
		byte[] bytes,
		Set<String> names
	) throws IOException {
		var in = new ByteArrayDataInput(bytes);
		var doc = new org.apache.lucene.document.Document();

		while(!in.eof()) {
			var name = in.readString();
			var type = in.readByte();

			if(names != null && !names.contains(name)) {
				switch(type) {
					case STRING, BINARY -> in.skipBytes(in.readVInt());
					case INT -> in.readZInt();
					case LONG -> in.readZLong();
					case FLOAT -> in.readInt();
					case DOUBLE -> in.readLong();
					default -> throw new IllegalStateException(
						"Unknown type in a cached document: " + type
					);
				}
				continue;
			}

			switch(type) {
				case STRING -> doc.add(new StoredField(name, in.readString()));
				case BINARY -> {
					var value = new byte[in.readVInt()];
					in.readBytes(value, 0, value.length);
					doc.add(new StoredField(name, new BytesRef(value)));
				}
				case INT -> doc.add(new StoredField(name, in.readZInt()));
				case LONG -> doc.add(new StoredField(name, in.readZLong()));
				case FLOAT -> doc.add(
					new StoredField(name, Float.intBitsToFloat(in.readInt()))
				);
				case DOUBLE -> doc.add(
					new StoredField(name, Double.longBitsToDouble(in.readLong()))
				);
				default -> throw new IllegalStateException(
					"Unknown type in a cached document: " + type
				);
			}
		}

		return doc;
	}

	/**
	 * Get how the cache has answered so far - hits, misses, evictions.
	 */
	public CacheStats stats() {
		return cache == null ? CacheStats.empty() : cache.stats();
	}

	/**
	 * Get how many documents the cache is holding, for tests.
	 */
	long entries() {
		if(cache == null) {
			return 0;
		}

		cache.cleanUp();
		return cache.estimatedSize();
	}
}
