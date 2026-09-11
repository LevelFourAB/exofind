package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.map.MapIterable;

import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.schema.IndexSchema;

/**
 * Reads the values of signal fields out of doc values, for the documents a
 * read hands back.
 *
 * <p>A signal field is refreshed in place through
 * {@link Index#updateDocument}, which replaces its sort doc values and
 * nothing else. The value therefore lives nowhere a stored field or the copy
 * of the document would answer from, and every path that turns a Lucene
 * document into a {@link Document} has to fill it in from here: a page of
 * results, a document read by key, a scan of the index, and the copy a patch
 * merges into. A path that does not leaves the field missing from what it
 * hands back, with no error anywhere.
 *
 * <p>The value is filled in as a stored field of the Lucene document, under
 * the name the field would have been stored under, so that
 * {@link DocumentReader} reads it the way it reads any stored field. Filling
 * happens after the {@link DocumentCache} has answered, never inside it: the
 * cache keys on the core of a segment, which a doc values update leaves as it
 * is, so a value cached there would outlive its refresh.
 */
final class SignalValues {
	private SignalValues() {
	}

	/**
	 * Get whether a name is that of a signal field of the index.
	 *
	 * @param schema
	 * @param name
	 *   name of a field of the document - never a path into an object, as
	 *   a signal field sits at the root
	 * @return
	 */
	static boolean isSignal(IndexSchema schema, String name) {
		return schema.getField(name).map(Field::isSignal).orElse(false);
	}

	/**
	 * Get what a copy of a document keeps, which is every field but the
	 * signal fields - or {@code null} when the index has none, so that the
	 * copy of an ordinary index is written without a lookup per field.
	 *
	 * @param schema
	 * @return
	 */
	static Predicate<String> keptInSource(IndexSchema schema) {
		if(!schema.hasSignalFields()) {
			return null;
		}

		return name -> !isSignal(schema, name);
	}

	/**
	 * Fill the signal fields of one document into the Lucene document read
	 * for it, as stored fields.
	 *
	 * @param schema
	 * @param reader
	 *   the reader the id belongs to
	 * @param docId
	 *   Lucene id of the document, across the whole reader
	 * @param doc
	 *   the stored fields read for the document, added to
	 * @param names
	 *   the stored fields wanted, or {@code null} for all of them - the same
	 *   set the stored fields were read with, so a field that was not asked
	 *   for is not read here either
	 * @throws IOException
	 */
	static void fill(
		IndexSchema schema,
		IndexReader reader,
		int docId,
		org.apache.lucene.document.Document doc,
		Set<String> names
	) throws IOException {
		if(!schema.hasSignalFields()) {
			return;
		}

		var leaves = reader.leaves();
		var leaf = leaves.get(ReaderUtil.subIndex(docId, leaves));
		var local = docId - leaf.docBase;

		for(var field : schema.getSignalFields()) {
			var stored = FieldNames.name(field.getName(), null, FieldNames.STORED);
			if(names != null && !names.contains(stored)) {
				continue;
			}

			var values = leaf.reader().getNumericDocValues(
				FieldNames.name(field.getName(), null, FieldNames.SORT)
			);
			if(values == null || !values.advanceExact(local)) {
				// The document holds no value, which reads as no field
				continue;
			}

			doc.add(storedField(stored, field.getType().readSignalValue(values.longValue())));
		}
	}

	/**
	 * Get a document with its signal fields filled in from the doc values of
	 * the reader it was read from, and then from values written since the
	 * reader was opened.
	 *
	 * @param schema
	 * @param reader
	 *   the reader the id belongs to
	 * @param docId
	 *   Lucene id of the document, across the whole reader
	 * @param document
	 *   the document as decoded from its copy, which holds no signal fields
	 * @param pending
	 *   the values of the signal fields written since the reader was opened,
	 *   by field, holding {@code null} for a field that was emptied - or
	 *   {@code null} when nothing was written. A field named here is answered
	 *   from here rather than from the reader
	 * @return
	 * @throws IOException
	 */
	static Document withValues(
		IndexSchema schema,
		IndexReader reader,
		int docId,
		Document document,
		MapIterable<String, Object> pending
	) throws IOException {
		if(!schema.hasSignalFields()) {
			return document;
		}

		var values = Lists.mutable.of(document.fields());

		LeafReaderContext leaf = null;
		if(docId >= 0) {
			var leaves = reader.leaves();
			leaf = leaves.get(ReaderUtil.subIndex(docId, leaves));
		}

		for(var field : schema.getSignalFields()) {
			var name = field.getName();

			if(pending != null && pending.containsKey(name)) {
				var value = pending.get(name);
				if(value != null) {
					values.add(new Document.Value(name, value));
				}
				continue;
			}

			if(leaf == null) {
				continue;
			}

			var docValues = leaf.reader().getNumericDocValues(
				FieldNames.name(name, null, FieldNames.SORT)
			);
			if(docValues == null || !docValues.advanceExact(docId - leaf.docBase)) {
				continue;
			}

			values.add(new Document.Value(
				name,
				field.getType().readSignalValue(docValues.longValue())
			));
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	/**
	 * Get a value as the stored field a read hands to {@link DocumentReader},
	 * which reads it back through the type the way it reads any stored
	 * number.
	 */
	private static StoredField storedField(String name, Object value) {
		return switch(value) {
			case Integer v -> new StoredField(name, v);
			case Long v -> new StoredField(name, v);
			case Float v -> new StoredField(name, v);
			case Double v -> new StoredField(name, v);
			default -> throw new IllegalArgumentException(
				"A signal field holds a number, not " + value.getClass().getSimpleName()
			);
		};
	}
}
