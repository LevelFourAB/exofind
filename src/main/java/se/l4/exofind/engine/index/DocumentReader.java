package se.l4.exofind.engine.index;

import java.util.Set;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.SetIterable;

import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.Field;
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
 * does not rewrite the documents already indexed. When everything a search
 * asks for is stored on its own, the copy is not read at all - see
 * {@link #namesOf()} for what that trades away.
 *
 * A field inside an object is asked for by its dotted path, whichever mode the
 * object is kept in, and comes back where it was given: inside the object, with
 * the fields that were not asked for left out of it. Asking for the object
 * itself asks for everything inside it.
 *
 * Asking for a field the definition leaves no way to return is refused rather
 * than answered with it missing from every hit, which reads as documents that
 * never held it.
 *
 * An instance reads one document at a time and is not safe to share between
 * threads, as it carries the encounter that field types are handed.
 */
public class DocumentReader {
	private final IndexSchema schema;
	private final IndexEncounterImpl encounter;
	private final SetIterable<String> fields;

	/**
	 * The fields of the document that were asked for whole, by the name they
	 * were asked for - which is the name of a wildcard field's value rather
	 * than the pattern it matched.
	 */
	private final MutableMap<String, Field> whole;

	/**
	 * The fields asked for inside an object field, keyed by the name of that
	 * object and held by their name inside it.
	 */
	private final MutableMap<String, MutableSet<String>> inside;

	/**
	 * @param schema
	 * @param fields
	 *   the fields wanted, as they are called in the definition of the index -
	 *   a field inside an object by its dotted path - or empty for all of them
	 * @throws IndexFieldNotFoundException
	 *   if the index has no field by one of the names
	 * @throws IndexFieldUsageException
	 *   if the definition leaves the index no way to return one of them
	 * @throws IndexSourceRequiredException
	 *   if only the copy of the document could return one of them and the
	 *   index keeps none
	 */
	public DocumentReader(IndexSchema schema, SetIterable<String> fields) {
		this.schema = schema;
		this.fields = fields;

		this.whole = Maps.mutable.empty();
		this.inside = Maps.mutable.empty();

		for(var name : fields) {
			want(name);
		}

		this.encounter = new IndexEncounterImpl(schema.getResources());
		this.encounter.updateLocale(Locales.getDefault());
	}

	/**
	 * Take one name that was asked for, as either a field of the document or a
	 * field inside one of its objects.
	 *
	 * A flattened path names a field of the index as well as a field inside an
	 * object, and is read as the latter here: values are given inside the
	 * object whichever mode it is kept in, so that is where they can be handed
	 * back from.
	 *
	 * @throws IndexFieldNotFoundException
	 *   if the index has no field by the name
	 * @throws IndexFieldUsageException
	 *   if nothing the index keeps could hold the field
	 * @throws IndexSourceRequiredException
	 *   if only the copy of the document could hold the field and the index
	 *   keeps none
	 */
	private void want(String name) {
		var flattened = schema.getFlattenedObjectOf(name);
		if(flattened.isPresent()) {
			wantInside(flattened.get(), name);
			return;
		}

		var nested = schema.getNestedField(name);
		if(nested.isPresent()) {
			wantInside(nested.get().path(), name);
			return;
		}

		var field = schema.getField(name)
			.orElseThrow(() -> new IndexFieldNotFoundException(name));

		/*
		 * Only what the definition says now is judged. A document indexed while
		 * the index kept its copies still holds every value it was given, and a
		 * search naming no fields brings those back - what is refused here is
		 * naming a field the definition has left no way to answer for, which
		 * would otherwise come back missing from every hit and read as documents
		 * that never held it.
		 */
		if(!schema.isSourceStored() && !field.isStored() && !field.getDef().getPrimaryKey()) {
			if(field.isObject()) {
				throw new IndexSourceRequiredException(name);
			}

			throw new IndexFieldUsageException(name, "stored");
		}

		whole.put(name, field);

		/*
		 * Asking for an object as well as for something inside it is asking for
		 * the object, so what was gathered for it stops meaning anything.
		 */
		inside.remove(name);
	}

	private void wantInside(String object, String path) {
		if(!schema.isSourceStored()) {
			throw new IndexSourceRequiredException(path);
		}

		if(whole.containsKey(object)) {
			return;
		}

		inside.getIfAbsentPut(object, Sets.mutable::empty)
			.add(path.substring(object.length() + 1));
	}

	/**
	 * Work out what to ask Lucene for when only some of the fields are wanted.
	 *
	 * The primary key is always included, as it is what a result is identified
	 * by and asking for a few fields is not a reason to lose track of which
	 * document they came from. The copy of the document is included only when
	 * something wanted needs it - it holds the whole document, and dragging it
	 * through decompression per hit to answer for a handful of stored fields
	 * is most of what a page of results would otherwise cost.
	 *
	 * The set is the caller's own, so a caller that needs stored fields of its
	 * own - the text highlighting is cut from, say - can add them and read
	 * everything at once. {@link #read(org.apache.lucene.document.Document)}
	 * hands back the fields that were asked for however much was loaded.
	 *
	 * @return
	 *   the names to load, or {@code null} to load everything that is stored
	 */
	public Set<String> namesOf() {
		if(fields.isEmpty()) {
			return null;
		}

		var names = Sets.mutable.<String>empty();

		if(sourceNeeded()) {
			names.add(FieldNames.SOURCE);
		}

		/*
		 * Only the fields of the document itself are named: a field inside an
		 * object is never stored on its own, so the copy of the document is the
		 * only thing that can answer for one and it is already being loaded.
		 */
		whole.forEachKeyValue((name, field) -> addStoredNames(names, name, field));

		var primaryKey = schema.getPrimaryKey();
		if(primaryKey.isPresent()) {
			addStoredNames(names, primaryKey.get().getName(), primaryKey.get());
		}

		return names;
	}

	/**
	 * Whether the copy of the document is needed to answer what was asked for.
	 *
	 * A field asked for inside an object, or an object asked for whole, only
	 * exists in the copy, as an object holds no value of its own to store. So
	 * does a field that does not ask to be stored, which {@link #want} let
	 * through because the index keeps its copies.
	 *
	 * What answering from the stored fields alone trades away is documents
	 * older than their field: one indexed before the field asked to be stored
	 * holds it only in its copy, and the copy is no longer read, so the value
	 * stays behind until the document is written again. A document indexed
	 * before the field asked to be filtered misses searches on it the same
	 * way, which is why changing a definition is expected to come with the
	 * documents being indexed again.
	 */
	private boolean sourceNeeded() {
		if(inside.notEmpty()) {
			return true;
		}

		return whole.anySatisfy(
			field -> field.isObject()
				|| !(field.isStored() || field.getDef().getPrimaryKey())
		);
	}

	/**
	 * Add the names a field's values are stored under. A locale specific field
	 * stores a variant per locale it holds values in, and asking for the field
	 * means asking for all of them - which variants exist is exactly what the
	 * declared locales of the field say.
	 */
	private void addStoredNames(MutableSet<String> names, String name, Field field) {
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
			if(fields.isEmpty()) {
				return DocumentSource.decode(source);
			}

			/*
			 * The copy holds every field of the document however few a search
			 * asks back, so reading all of it to hand back a handful would be
			 * what a page of results ordinarily costs. What was not asked for
			 * is stepped over instead.
			 */
			return cutObjects(DocumentSource.decode(source, this::wanted));
		}

		var values = Lists.mutable.<Document.Value>empty();

		for(var stored : doc.getFields()) {
			var parsed = FieldNames.parse(stored.name());
			if(parsed == null || !FieldNames.STORED.equals(parsed.suffix())) {
				continue;
			}

			if(!wanted(parsed.field())) {
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
	 * Cut the objects of a document down to the fields that were asked for
	 * inside them. Which fields of the document itself are there was decided
	 * as it was read.
	 *
	 * @param doc
	 * @return
	 */
	private Document cutObjects(Document doc) {
		if(inside.isEmpty()) {
			return doc;
		}

		var values = Lists.mutable.<Document.Value>empty();
		for(var value : doc.fields()) {
			values.add(cut(value));
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	/**
	 * Cut one value down to the fields that were asked for inside it, which is
	 * a value of an object field that was named by the paths through it rather
	 * than whole. Every other value is handed back as it was given.
	 */
	private Document.Value cut(Document.Value value) {
		var names = inside.get(value.name());
		if(names == null || !(value.value() instanceof Document object)) {
			return value;
		}

		var values = Lists.mutable.<Document.Value>empty();
		for(var field : object.fields()) {
			if(names.contains(field.name())) {
				values.add(field);
			}
		}

		return new Document.Value(
			value.name(),
			new Document(values.toArray(new Document.Value[0])),
			value.locale()
		);
	}

	/**
	 * Whether a field belongs in what is handed back.
	 *
	 * The copy of a document holds every field however few were wanted, and the
	 * stored fields can hold more than was wanted too, as they are read in one
	 * go with whatever else the caller needed them for - so both are read
	 * through this. Asking for a few fields never loses the primary key, which
	 * is what a result is identified by.
	 */
	private boolean wanted(String field) {
		if(fields.isEmpty() || whole.containsKey(field) || inside.containsKey(field)) {
			return true;
		}

		return schema.getPrimaryKey()
			.map(primaryKey -> primaryKey.getName().equals(field))
			.orElse(false);
	}
}
