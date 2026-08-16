package se.l4.exofind.engine.index;

import java.util.Set;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.SetIterable;

import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.IndexSchema;

/**
 * Reads documents back out of the index in the shape they were given in.
 *
 * There are two ways a document can come back, and which one is used is decided
 * per document rather than per index. An index that keeps its documents whole
 * has a copy of each one as it arrived, and that copy is what is returned - it
 * needs no definition to read and holds every value that was given, of the type
 * it was given as. Without one, the stored fields are all there is: each is
 * matched back to the field of the schema it belongs to, and that field's type
 * turns it back into a value.
 *
 * Both are needed whichever way the index is set, because changing the setting
 * does not rewrite the documents already indexed.
 *
 * An instance reads one document at a time and is not safe to share between
 * threads, as it carries the encounter that field types are handed.
 */
public class DocumentReader {
	private final IndexSchema schema;
	private final IndexEncounterImpl encounter;
	private final SetIterable<String> fields;

	/**
	 * @param schema
	 * @param fields
	 *   the fields wanted, as they are called in the definition of the index,
	 *   or empty for all of them
	 */
	public DocumentReader(IndexSchema schema, SetIterable<String> fields) {
		this.schema = schema;
		this.fields = fields;

		this.encounter = new IndexEncounterImpl(schema.getResources());
		this.encounter.updateLocale(Locales.getDefault());
	}

	/**
	 * Work out what to ask Lucene for when only some of the fields are wanted.
	 *
	 * The primary key is always included, as it is what a result is identified
	 * by and asking for a few fields is not a reason to lose track of which
	 * document they came from. So is the copy of the document, whatever the
	 * index is set to keep now - a document that has one is read from it, and
	 * asking for it costs nothing for the documents that do not.
	 *
	 * @return
	 *   the names to load, or {@code null} to load everything that is stored
	 */
	public Set<String> namesOf() {
		if(fields.isEmpty()) {
			return null;
		}

		var names = Sets.mutable.<String>empty();
		names.add(FieldNames.SOURCE);

		for(var name : fields) {
			addStoredNames(names, name);
		}

		var primaryKey = schema.getPrimaryKey();
		if(primaryKey.isPresent()) {
			addStoredNames(names, primaryKey.get().getName());
		}

		return names;
	}

	/**
	 * Add the names a field's values are stored under. A locale specific field
	 * stores a variant per locale it holds values in, and asking for the field
	 * means asking for all of them - which variants exist is exactly what the
	 * declared locales of the field say.
	 */
	private void addStoredNames(MutableSet<String> names, String name) {
		var field = schema.getField(name)
			.orElseThrow(() -> new IndexFieldNotFoundException(name));

		if(field.isLocaleSpecific()) {
			for(var locale : field.getLocales()) {
				names.add(FieldNames.name(name, locale, FieldNames.STORED));
			}
		} else {
			names.add(FieldNames.name(name, null, FieldNames.STORED));
		}
	}

	/**
	 * Turn a document as Lucene holds it into one shaped like the document
	 * that was indexed.
	 *
	 * @param doc
	 * @return
	 */
	public Document read(org.apache.lucene.document.Document doc) {
		var source = doc.getBinaryValue(FieldNames.SOURCE);
		if(source != null) {
			return select(DocumentSource.decode(source));
		}

		var values = Lists.mutable.<Document.Value>empty();

		for(var stored : doc.getFields()) {
			var parsed = FieldNames.parse(stored.name());
			if(parsed == null || !FieldNames.STORED.equals(parsed.suffix())) {
				continue;
			}

			var field = schema.getField(parsed.field());
			if(field.isEmpty()) {
				/*
				 * The field has been taken out of the definition since the
				 * document was indexed. It is still on disk until the document
				 * is written again, but it is no longer part of the index.
				 */
				continue;
			}

			encounter.updateValue(parsed.field(), field.get().getDef());

			values.add(
				new Document.Value(
					parsed.field(),
					field.get().getType().readStored(encounter, stored),
					parsed.locale()
				)
			);
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	/**
	 * Cut a document down to the fields that were asked for.
	 *
	 * Only needed for a document read from its own copy, which holds every
	 * field whatever was wanted. Asking Lucene for a few stored fields already
	 * leaves the rest behind.
	 *
	 * @param doc
	 * @return
	 */
	private Document select(Document doc) {
		if(fields.isEmpty()) {
			return doc;
		}

		var primaryKey = schema.getPrimaryKey()
			.map(field -> field.getName())
			.orElse(null);

		var values = Lists.mutable.<Document.Value>empty();
		for(var value : doc.fields()) {
			if(fields.contains(value.name()) || value.name().equals(primaryKey)) {
				values.add(value);
			}
		}

		return new Document(values.toArray(new Document.Value[0]));
	}
}
