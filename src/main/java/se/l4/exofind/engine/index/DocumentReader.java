package se.l4.exofind.engine.index;

import java.util.Set;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MapIterable;
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
 * turns it back into a value. The stored fields of a field below a nested list
 * sit in the documents of the list's values rather than in the document
 * itself; {@link #needsChildren()} says when those have to be read, and the
 * caller hands them to {@link #read(org.apache.lucene.document.Document,
 * MapIterable)} to come back inside the list.
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
 * A locale specific field comes back in one variant, {@link #inLocale the one
 * the search reads it in} and under the tag of that variant, as what the
 * document holds in the other languages is not what a search asking in one of
 * them is answering. {@link #everyVariant} hands a document back whole instead,
 * which is how it is read for its own sake.
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
	 * The locale that locale specific fields are read in, or {@code null} to
	 * read each of them in its own default locale. Says nothing when
	 * {@link #everyVariant} is set.
	 */
	private final String locale;

	/**
	 * Whether every variant of a locale specific field comes back, rather than
	 * the one {@link #locale} names.
	 */
	private final boolean everyVariant;

	/**
	 * Whether the index declares a locale specific field at all, so documents
	 * of an index that has none are never walked looking for one.
	 */
	private final boolean localeSpecific;

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
	 * The fields asked for inside objects that answer from their own stored
	 * Lucene fields, keyed by their full dotted path. Only filled when the
	 * index keeps no copies: a stored field inside a chain of single objects
	 * is stored under its path like a field of the index, and the shape it
	 * was given in is rebuilt around it when it is read.
	 */
	private final MutableMap<String, Field> insideStored;

	/**
	 * The fields asked for below nested lists that answer from the stored
	 * Lucene fields of the lists' values, keyed by the path of the list and
	 * holding each field by its full dotted path. Only filled when the index
	 * keeps no copies: the values live in documents of their own, which the
	 * caller reads and hands to {@link #read(org.apache.lucene.document.Document,
	 * MapIterable)} - what {@link #wantsChildren} asks for.
	 */
	private final MutableMap<String, MutableMap<String, Field>> nestedStored;

	/**
	 * Read documents the way a search answers them, with every locale specific
	 * field in the one variant the search reads it in.
	 *
	 * @param schema
	 * @param fields
	 *   the fields wanted, as they are called in the definition of the index -
	 *   a field inside an object by its dotted path - or empty for all of them
	 * @param locale
	 *   the locale the search reads locale specific fields in (BCP-47), or
	 *   {@code null} to read every field in its own default locale
	 * @throws IndexFieldNotFoundException
	 *   if the index has no field by one of the names
	 * @throws IndexFieldUsageException
	 *   if the definition leaves the index no way to return one of them
	 * @throws IndexSourceRequiredException
	 *   if only the copy of the document could return one of them and the
	 *   index keeps none
	 */
	public static DocumentReader inLocale(
		IndexSchema schema,
		SetIterable<String> fields,
		String locale
	) {
		return new DocumentReader(schema, fields, locale, false);
	}

	/**
	 * Read documents whole, keeping every variant of every locale specific
	 * field.
	 *
	 * This is how a document is read for its own sake rather than as a result:
	 * there is no locale it is being answered in, and what it says in each
	 * language is part of what it is.
	 *
	 * @param schema
	 * @param fields
	 *   the fields wanted, judged as {@link #inLocale} judges them
	 * @throws IndexFieldNotFoundException
	 *   if the index has no field by one of the names
	 * @throws IndexFieldUsageException
	 *   if the definition leaves the index no way to return one of them
	 * @throws IndexSourceRequiredException
	 *   if only the copy of the document could return one of them and the
	 *   index keeps none
	 */
	public static DocumentReader everyVariant(
		IndexSchema schema,
		SetIterable<String> fields
	) {
		return new DocumentReader(schema, fields, null, true);
	}

	private DocumentReader(
		IndexSchema schema,
		SetIterable<String> fields,
		String locale,
		boolean everyVariant
	) {
		this.schema = schema;
		this.fields = fields;
		this.locale = locale;
		this.everyVariant = everyVariant;
		this.localeSpecific = schema.hasLocaleSpecificFields();

		this.whole = Maps.mutable.empty();
		this.inside = Maps.mutable.empty();
		this.insideStored = Maps.mutable.empty();
		this.nestedStored = Maps.mutable.empty();

		for(var name : fields) {
			want(name);
		}

		this.encounter = new IndexEncounterImpl(schema.getResources(), schema.isHighlightingInPostings());
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
			/*
			 * Without copies, a field inside objects answers only when it is
			 * stored on its own - which validation lets it be below single
			 * objects alone, so the shape around it can be rebuilt from the
			 * path. An object itself holds no value to store, and below a
			 * flattened list nothing says which value a stored one came from,
			 * so for both the copy is the only thing that could answer;
			 * elsewhere what is missing is the field's own setting.
			 */
			if(!schema.isSourceStored()) {
				var field = schema.getField(name).orElseThrow();
				if(field.isObject() || underFlattenedList(schema, field, name)) {
					throw new IndexSourceRequiredException(name);
				}

				if(!field.isStored()) {
					throw new IndexFieldUsageException(name, "stored");
				}

				insideStored.put(name, field);
			}

			wantInside(flattened.get(), name);
			return;
		}

		var nested = schema.getNestedField(name);
		if(nested.isPresent()) {
			/*
			 * Without copies, a field below a nested list answers from the
			 * stored fields of the list's values, judged the way the flattened
			 * case above judges: an object holds no value to store, and below
			 * a flattened list - above the nested list or inside its values -
			 * nothing says which value a stored one came from, so for both the
			 * copy is the only thing that could answer; elsewhere what is
			 * missing is the field's own setting.
			 */
			if(!schema.isSourceStored()) {
				var field = nested.get().field();
				if(field.isObject() || underFlattenedList(schema, field, name)) {
					throw new IndexSourceRequiredException(name);
				}

				if(!field.isStored()) {
					throw new IndexFieldUsageException(name, "stored");
				}

				nestedStored
					.getIfAbsentPut(nested.get().path(), Maps.mutable::empty)
					.put(name, field);
			}

			/*
			 * The nested list itself may sit inside other objects, and the
			 * document gives its values at the root of that chain - which is
			 * where the copy holds them.
			 */
			var block = nested.get().path();
			wantInside(schema.getFlattenedObjectOf(block).orElse(block), name);
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

	/**
	 * Whether any flattened list sits on the chain above a field, so its
	 * values mix in one document and only the copy can say which object value
	 * each belongs to. A nested list on the chain is not one - its values
	 * keep documents of their own - so the one a nested field's chain crosses
	 * is stepped over and the walk carries on above it.
	 *
	 * Static so that judging a field selection outside a reader - the fields
	 * of a value being reported - refuses by the same rule reading does.
	 */
	static boolean underFlattenedList(IndexSchema schema, Field field, String name) {
		var current = name;
		var currentField = field;

		while(true) {
			var parent = schema.getEnclosingObjectOf(currentField, current).orElse(null);
			if(parent == null) {
				return false;
			}

			currentField = schema.getField(parent)
				.or(() -> schema.getNestedField(parent).map(IndexSchema.NestedField::field))
				.orElseThrow();
			if(currentField.isMultiple() && !currentField.isNestedObject()) {
				return true;
			}

			current = parent;
		}
	}

	private void wantInside(String object, String path) {
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
		 * A field inside objects is usually answered by the copy of the
		 * document, which is already being loaded; the exception is a stored
		 * field below single objects in an index that keeps no copies, which
		 * is stored under its path like any field of the document.
		 */
		whole.forEachKeyValue((name, field) -> addStoredNames(names, name, field));
		insideStored.forEachKeyValue((name, field) -> addStoredNames(names, name, field));

		var primaryKey = schema.getPrimaryKey();
		if(primaryKey.isPresent()) {
			addStoredNames(names, primaryKey.get().getName(), primaryKey.get());
		}

		return names;
	}

	/**
	 * Whether the copy of the document is needed to answer what was asked for.
	 *
	 * An object asked for whole only exists in the copy, as an object holds no
	 * value of its own to store, and a field asked for inside one prefers the
	 * copy - it holds the shape the field was given in. So does a field that
	 * does not ask to be stored, which {@link #want} let through because the
	 * index keeps its copies.
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
	 * stores a variant per locale it holds values in, so only the one being
	 * read is named - the others hold the same field in languages this is not
	 * answering in, and reading them costs the same as reading anything else.
	 * Reading a document whole names every variant, which is what the declared
	 * locales of the field say exist.
	 */
	private void addStoredNames(MutableSet<String> names, String name, Field field) {
		if(!field.isLocaleSpecific()) {
			names.add(FieldNames.name(name, null, FieldNames.STORED));
			return;
		}

		if(everyVariant) {
			for(var locale : field.getLocales()) {
				names.add(FieldNames.name(name, locale, FieldNames.STORED));
			}

			return;
		}

		names.add(FieldNames.name(name, variantOf(field), FieldNames.STORED));
	}

	/**
	 * The variant of a locale specific field that is being read, judged the way
	 * a search judges which variant it matches and sorts by: the locale asked
	 * for when the field declares a variant the tag names - matched as closely
	 * as the declared locales tell apart - and the field's default otherwise,
	 * so a field that never held the locale still answers.
	 *
	 * @see QueryCompiler
	 */
	private String variantOf(Field field) {
		return locale == null
			? field.getDefaultLocale()
			: field.resolveLocale(locale).orElseGet(field::getDefaultLocale);
	}

	/**
	 * The variant of a locale specific field a value belongs to, which is the
	 * declared locale the tag it was given under resolves to. {@code null} when
	 * the field no longer declares a variant the tag names, as a value indexed
	 * before a locale was taken out of the definition belongs to no variant the
	 * field now has.
	 */
	private String variantOf(Field field, String locale) {
		return locale == null
			? field.getDefaultLocale()
			: field.resolveLocale(locale).orElse(null);
	}

	/**
	 * The field a value belongs to when that field is locale specific, and
	 * {@code null} for every other value - one of a field that is not locale
	 * specific, or of a field the definition no longer has. Asked with the
	 * full dotted path, so a field inside objects answers wherever it sits.
	 */
	private Field localeSpecificField(String name) {
		var field = schema.getField(name)
			.or(() -> schema.getNestedField(name).map(IndexSchema.NestedField::field));

		return field.isPresent() && field.get().isLocaleSpecific()
			? field.get()
			: null;
	}

	/**
	 * Turn a document as Lucene holds it into one shaped like the document
	 * that was indexed.
	 *
	 * @param doc
	 * @return
	 */
	public Document read(org.apache.lucene.document.Document doc) {
		return read(doc, null);
	}

	/**
	 * Whether reading needs the documents of any nested list's values handed
	 * along - which it does exactly when the index keeps no copies and
	 * something stored below a nested list is being read. A caller that gets
	 * {@code true} finds the paths through {@link #wantsChildren} and reads
	 * the children itself, as only it knows where the documents sit.
	 */
	public boolean needsChildren() {
		if(schema.isSourceStored()) {
			return false;
		}

		return fields.isEmpty()
			? schema.hasNestedStoredFields()
			: nestedStored.notEmpty();
	}

	/**
	 * Whether the values of one nested list have to be handed to
	 * {@link #read(org.apache.lucene.document.Document, MapIterable)} for it
	 * to answer what was asked. Asked with the concrete path the values are
	 * marked with, which for a list whose name holds a wildcard is a name a
	 * document gave rather than the pattern.
	 */
	public boolean wantsChildren(String path) {
		if(schema.isSourceStored()) {
			return false;
		}

		if(fields.isEmpty()) {
			return schema.getNestedFields(path).anySatisfy(Field::isStored);
		}

		return nestedStored.containsKey(path);
	}

	/**
	 * Turn a document as Lucene holds it into one shaped like the document
	 * that was indexed, handed the documents of its nested lists' values.
	 *
	 * The children only matter for a document read without its copy: the
	 * stored fields of a value live in the value's own document, and this is
	 * how they come back inside the list the way the copy would have held
	 * them. A document that has a copy answers from it and the children go
	 * unread.
	 *
	 * @param doc
	 * @param children
	 *   the documents of the nested lists' values, keyed by the concrete path
	 *   of each list and in the order the block holds them - the order the
	 *   document gave the values in. {@code null} or missing a path when the
	 *   caller had nothing to read, which leaves that list out the way a
	 *   missing copy leaves the whole document out
	 * @return
	 */
	public Document read(
		org.apache.lucene.document.Document doc,
		MapIterable<String, ? extends ListIterable<org.apache.lucene.document.Document>> children
	) {
		var source = doc.getBinaryValue(FieldNames.SOURCE);
		if(source != null) {
			if(fields.isEmpty()) {
				return cutVariants(DocumentSource.decode(source));
			}

			/*
			 * The copy holds every field of the document however few a search
			 * asks back, so reading all of it to hand back a handful would be
			 * what a page of results ordinarily costs. What was not asked for
			 * is stepped over instead.
			 */
			return cutObjects(cutVariants(DocumentSource.decode(source, this::wanted)));
		}

		var values = Lists.mutable.<Document.Value>empty();
		MutableList<Document.Value> folded = null;

		for(var stored : doc.getFields()) {
			var parsed = FieldNames.parse(stored.name());
			if(parsed == null || !FieldNames.STORED.equals(parsed.suffix())) {
				continue;
			}

			if(!wanted(parsed.field()) && !insideStored.containsKey(parsed.field())) {
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

			/*
			 * The variants beside the one being read are stored fields of the
			 * same document, and are loaded with it whenever everything stored
			 * is - a search asking for no fields in particular, or one whose
			 * highlighting reads a field of its own.
			 *
			 * A filled variant is a stored field like any other here, and is
			 * answered as the variant it fills - which is what reading from the
			 * copy answers too, so the two agree.
			 */
			if(!everyVariant
				&& field.get().isLocaleSpecific()
				&& !variantOf(field.get()).equals(parsed.locale())) {
				continue;
			}

			encounter.updateValue(parsed.field(), field.get().getDef());

			var value = new Document.Value(
				parsed.field(),
				field.get().getType().readStored(encounter, stored),
				parsed.locale()
			);

			/*
			 * A field that folded out of objects was given inside them, so it
			 * is handed back inside them - collected apart and folded back in
			 * below, once every path it shares an object with has been read.
			 */
			if(schema.getFlattenedObjectOf(parsed.field()).isPresent()) {
				if(folded == null) {
					folded = Lists.mutable.empty();
				}

				folded.add(value);
			} else {
				values.add(value);
			}
		}

		/*
		 * The values of each nested list, one Document apiece and named by the
		 * path of the list, so the assembly below folds them into the objects
		 * the list sits in beside the fields stored under their own paths.
		 */
		if(children != null) {
			for(var pair : children.keyValuesView()) {
				var path = pair.getOne();
				if(!wantsChildren(path)) {
					continue;
				}

				var only = fields.isEmpty() ? null : nestedStored.get(path);
				for(var child : pair.getTwo()) {
					if(folded == null) {
						folded = Lists.mutable.empty();
					}

					folded.add(new Document.Value(
						path,
						readValue(path, child, only, !everyVariant)
					));
				}
			}
		}

		if(folded != null) {
			assembleObjects(folded, values);
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	/**
	 * Fold values read under dotted paths back into the objects they were
	 * given inside. A field below single objects is stored under its path, so
	 * each level of the shape is one object value and rebuilding is putting
	 * every value where its path says; the values of a nested list arrive
	 * already assembled and named by the path of the list, and go in the same
	 * way - several under one name, which is what a list is.
	 */
	private void assembleObjects(
		MutableList<Document.Value> folded,
		MutableList<Document.Value> values
	) {
		var roots = new java.util.LinkedHashMap<String, ObjectShape>();

		for(var value : folded) {
			var chain = chainOf(value.name());

			/*
			 * A nested list at the root has no object above it to rebuild -
			 * its values are values of the document itself.
			 */
			if(chain.size() == 1) {
				values.add(
					new Document.Value(chain.get(0), value.value(), value.locale())
				);
				continue;
			}

			var node = roots.computeIfAbsent(chain.get(0), key -> new ObjectShape());
			for(var i = 1; i < chain.size() - 1; i++) {
				node = node.child(chain.get(i));
			}

			node.values.add(
				new Document.Value(chain.getLast(), value.value(), value.locale())
			);
		}

		roots.forEach((name, shape) -> values.add(shape.toValue(name)));
	}

	/**
	 * The relative names along a flattened path - the name of the top object,
	 * each object below it, and the field's own name inside the last. Walked
	 * along the chain the schema declares rather than split on dots, so the
	 * split can never disagree with which objects the schema puts on the path.
	 */
	private ListIterable<String> chainOf(String path) {
		var chain = Lists.mutable.<String>empty();

		var name = path;
		var field = schema.getField(name).orElseThrow();
		while(true) {
			var parent = schema.getEnclosingObjectOf(field, name).orElse(null);
			if(parent == null) {
				chain.add(0, name);
				break;
			}

			chain.add(0, name.substring(parent.length() + 1));
			name = parent;
			field = schema.getField(parent).orElseThrow();
		}

		return chain;
	}

	/**
	 * One object value being rebuilt around the stored fields inside it.
	 */
	private static final class ObjectShape {
		final MutableList<Document.Value> values = Lists.mutable.empty();
		final java.util.LinkedHashMap<String, ObjectShape> children =
			new java.util.LinkedHashMap<>();

		ObjectShape child(String name) {
			return children.computeIfAbsent(name, key -> new ObjectShape());
		}

		Document.Value toValue(String name) {
			var fields = Lists.mutable.<Document.Value>empty();
			fields.addAll(values);
			children.forEach((childName, child) -> fields.add(child.toValue(childName)));

			return new Document.Value(
				name,
				new Document(fields.toArray(new Document.Value[0]))
			);
		}
	}

	/**
	 * Turn the document of one nested list's value into the value as it was
	 * given, as far as its stored fields reach - the fields that were not
	 * stored are only in the copy of the document, and a caller here is
	 * answering without one.
	 *
	 * This is how a value is read when it is being answered about on its own -
	 * a hit standing for it, or a report of which values matched - so every
	 * locale variant comes back, the same as when such a value is looked at
	 * through the copy.
	 *
	 * @param path
	 *   concrete path of the nested list the value belongs to
	 * @param doc
	 *   the value's own document as Lucene holds it, with all of its stored
	 *   fields loaded
	 * @return
	 */
	public Document readNestedValue(String path, org.apache.lucene.document.Document doc) {
		return readValue(path, doc, null, false);
	}

	/**
	 * Read one nested list value out of its own document, rebuilding the
	 * objects inside it around the relative paths the way a document is
	 * rebuilt around its own stored fields.
	 *
	 * @param path
	 *   concrete path of the nested list
	 * @param doc
	 *   the value's document
	 * @param only
	 *   the fields to keep, by their full dotted path, or {@code null} for
	 *   every stored field of the value
	 * @param oneVariant
	 *   whether a locale specific field answers in the one variant being
	 *   read, rather than in every variant the value holds
	 */
	private Document readValue(
		String path,
		org.apache.lucene.document.Document doc,
		MapIterable<String, Field> only,
		boolean oneVariant
	) {
		var values = Lists.mutable.<Document.Value>empty();
		java.util.LinkedHashMap<String, ObjectShape> shapes = null;

		for(var stored : doc.getFields()) {
			var parsed = FieldNames.parse(stored.name());
			if(parsed == null || !FieldNames.STORED.equals(parsed.suffix())) {
				continue;
			}

			if(only != null && !only.containsKey(parsed.field())) {
				continue;
			}

			var nested = schema.getNestedField(parsed.field()).orElse(null);
			if(nested == null || !nested.path().equals(path)) {
				/*
				 * The field has been taken out of the definition since the
				 * value was indexed, or now resolves to another list. It is
				 * still on disk until the document is written again, but it is
				 * no longer part of this value.
				 */
				continue;
			}

			var field = nested.field();
			if(oneVariant
				&& field.isLocaleSpecific()
				&& !variantOf(field).equals(parsed.locale())) {
				continue;
			}

			encounter.updateValue(parsed.field(), field.getDef());

			var chain = chainBelow(path, parsed.field(), field);
			var value = new Document.Value(
				chain.getLast(),
				field.getType().readStored(encounter, stored),
				parsed.locale()
			);

			if(chain.size() == 1) {
				values.add(value);
				continue;
			}

			if(shapes == null) {
				shapes = new java.util.LinkedHashMap<>();
			}

			var node = shapes.computeIfAbsent(chain.get(0), key -> new ObjectShape());
			for(var i = 1; i < chain.size() - 1; i++) {
				node = node.child(chain.get(i));
			}

			node.values.add(value);
		}

		if(shapes != null) {
			shapes.forEach((name, shape) -> values.add(shape.toValue(name)));
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	/**
	 * The relative names along a path below a nested list - each object
	 * between the list and the field, and the field's own name inside the
	 * last. Walked along the chain the schema declares rather than split on
	 * dots, so the split can never disagree with which objects the schema
	 * puts on the path.
	 */
	private ListIterable<String> chainBelow(String block, String name, Field field) {
		var chain = Lists.mutable.<String>empty();

		var current = name;
		var currentField = field;
		while(true) {
			var parent = schema.getEnclosingObjectOf(currentField, current).orElseThrow();
			chain.add(0, current.substring(parent.length() + 1));
			if(parent.equals(block)) {
				break;
			}

			current = parent;
			currentField = schema.getNestedField(parent)
				.map(IndexSchema.NestedField::field)
				.orElseThrow();
		}

		return chain;
	}

	/**
	 * What {@link #readWithSource} hands back.
	 *
	 * @param document
	 *   the fields that were asked for, shaped the way {@link #read} shapes
	 *   them
	 * @param source
	 *   the copy of the document, holding the extra fields that were asked to
	 *   be readable beside what {@code document} holds - or {@code null} when
	 *   the document has no copy to read them from
	 */
	public record WithSource(Document document, Document source) {
	}

	/**
	 * Turn a document as Lucene holds it into one shaped like the document
	 * that was indexed, keeping the copy it was read from readable.
	 *
	 * What was asked for comes back exactly as {@link #read} answers it; the
	 * copy is for fields a caller needs to look at without handing them back -
	 * the values of an object field whose matches are being reported, whether
	 * or not the search asks the field itself back. Loading the copy is the
	 * caller's to arrange, by adding {@link FieldNames#SOURCE} to what
	 * {@link #namesOf()} says to load.
	 *
	 * The copy is handed back as the document was given, in every language it
	 * holds. Only what was asked for is read in one variant, as only that is
	 * being answered - so an object value looked at through the copy carries
	 * every variant of any locale specific field inside it.
	 *
	 * @param doc
	 * @param alsoDecode
	 *   names of fields to keep readable in the copy beyond the ones that
	 *   were asked for
	 * @return
	 */
	public WithSource readWithSource(
		org.apache.lucene.document.Document doc,
		SetIterable<String> alsoDecode
	) {
		return readWithSource(doc, alsoDecode, null);
	}

	/**
	 * Turn a document as Lucene holds it into one shaped like the document
	 * that was indexed, keeping the copy readable and handed the documents of
	 * its nested lists' values - what {@link #readWithSource(org.apache.lucene.document.Document,
	 * SetIterable)} answers for a document that has a copy, answered for one
	 * that has none the way {@link #read(org.apache.lucene.document.Document,
	 * MapIterable)} answers it.
	 *
	 * @param doc
	 * @param alsoDecode
	 *   names of fields to keep readable in the copy beyond the ones that
	 *   were asked for
	 * @param children
	 *   the documents of the nested lists' values, read the way
	 *   {@link #read(org.apache.lucene.document.Document, MapIterable)}
	 *   describes
	 * @return
	 */
	public WithSource readWithSource(
		org.apache.lucene.document.Document doc,
		SetIterable<String> alsoDecode,
		MapIterable<String, ? extends ListIterable<org.apache.lucene.document.Document>> children
	) {
		var source = doc.getBinaryValue(FieldNames.SOURCE);
		if(source == null) {
			return new WithSource(read(doc, children), null);
		}

		if(fields.isEmpty()) {
			var decoded = DocumentSource.decode(source);
			return new WithSource(cutVariants(decoded), decoded);
		}

		var decoded = DocumentSource.decode(
			source,
			name -> wanted(name) || alsoDecode.contains(name)
		);

		return new WithSource(cutObjects(cutVariants(askedFor(decoded))), decoded);
	}

	/**
	 * Keep the fields of a document that were asked for, dropping the ones
	 * decoded only to be looked at.
	 */
	private Document askedFor(Document doc) {
		var values = Lists.mutable.<Document.Value>empty();
		for(var value : doc.fields()) {
			if(wanted(value.name())) {
				values.add(value);
			}
		}

		if(values.size() == doc.fields().length) {
			return doc;
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	/**
	 * Keep one value of every locale specific field the document holds: the one
	 * the field is being read in, and where the document was never given that
	 * variant, the one an index that fills its locales filled it with - which is
	 * the value the search matched, and comes back under the variant it fills
	 * rather than the language it is in.
	 *
	 * That a filled variant answers as the variant is what the Lucene fields
	 * say too, where it is a copy written into that variant and nothing tells it
	 * from a translation - so a search asking for a field that is stored on its
	 * own is answered the same whether the copy of the document is read or not.
	 * {@link #everyVariant Reading a document whole} is what says which
	 * languages it was given.
	 *
	 * A field the document holds nothing to answer with is left out, the way a
	 * field it never held is.
	 */
	private Document cutVariants(Document doc) {
		if(everyVariant || !localeSpecific) {
			return doc;
		}

		return cutVariants(doc, "");
	}

	/**
	 * Trim the variants of one document or object value, resolving the fields
	 * at this level by the given path prefix and stepping into object values
	 * for the levels below. Judged per value rather than across the document,
	 * because each object value is its own unit of translation - it filled
	 * its own missing locales from its own given ones when it was indexed.
	 */
	private Document cutVariants(Document doc, String prefix) {
		/*
		 * The locale specific fields this level holds and the variants it
		 * holds them in, gathered before anything is judged because which value
		 * of a field answers depends on which of them are there. Left
		 * unallocated for a level that holds no such field, which is every
		 * document of an index whose locale specific fields are not among the
		 * ones being read.
		 */
		MutableMap<String, Field> localized = null;
		MutableMap<String, MutableSet<String>> held = null;

		for(var value : doc.fields()) {
			var field = localeSpecificField(prefix + value.name());
			if(field == null) {
				continue;
			}

			if(localized == null) {
				localized = Maps.mutable.empty();
				held = Maps.mutable.empty();
			}

			localized.put(value.name(), field);

			var variant = variantOf(field, value.locale());
			if(variant != null) {
				held.getIfAbsentPut(value.name(), Sets.mutable::empty).add(variant);
			}
		}

		var keep = Maps.mutable.<String, String>empty();
		if(localized != null) {
			for(var entry : localized.keyValuesView()) {
				var variants = held.get(entry.getOne());
				if(variants == null) {
					continue;
				}

				var variant = variantOf(entry.getTwo());
				if(variants.contains(variant)) {
					keep.put(entry.getOne(), variant);
					continue;
				}

				/*
				 * The document was never given this variant, so what a search
				 * read of the field is what the index filled it with - the
				 * first locale of the chain the document does hold, found here
				 * the way it was found when the document was indexed. An index
				 * that fills nothing has no chain, and the field is left out.
				 */
				var filled = schema.getLocaleFallbackChain(entry.getTwo())
					.detect(variants::contains);

				if(filled != null) {
					keep.put(entry.getOne(), filled);
				}
			}
		}

		var values = Lists.mutable.<Document.Value>empty();
		var cut = false;

		for(var value : doc.fields()) {
			var field = localized == null ? null : localized.get(value.name());
			if(field == null) {
				if(value.value() instanceof Document object) {
					var inner = cutVariants(object, prefix + value.name() + '.');
					if(inner == object) {
						values.add(value);
					} else {
						cut = true;
						values.add(
							new Document.Value(value.name(), inner, value.locale())
						);
					}
				} else {
					values.add(value);
				}

				continue;
			}

			var variant = keep.get(value.name());
			if(variant == null || !variant.equals(variantOf(field, value.locale()))) {
				cut = true;
				continue;
			}

			/*
			 * Handed back under the variant that was read rather than the tag
			 * the value carries: a value given as `nb-NO` comes back as the
			 * `no` the field declares, and one taken from another locale comes
			 * back as the variant it was taken for. Either way a caller reads
			 * the variant it asked about without matching tags itself.
			 */
			var read = variantOf(field);
			if(read.equals(value.locale())) {
				values.add(value);
			} else {
				cut = true;
				values.add(new Document.Value(value.name(), value.value(), read));
			}
		}

		if(!cut) {
			return doc;
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

		return new Document.Value(
			value.name(),
			cutInner(object, names),
			value.locale()
		);
	}

	/**
	 * Keep the fields of one object value that some remaining path asks for. A
	 * path spent whole keeps the field as it was given; one reaching further
	 * steps into the object there and carries on with what is left of it.
	 * Which of the two a path is reads off the value: a name holding a dot
	 * matches a field that carries it as a declared name, or steps into an
	 * object of the first segment's name - the definition refuses names that
	 * could be both.
	 */
	static Document cutInner(Document object, SetIterable<String> names) {
		var values = Lists.mutable.<Document.Value>empty();

		for(var field : object.fields()) {
			if(names.contains(field.name())) {
				values.add(field);
				continue;
			}

			if(!(field.value() instanceof Document innerObject)) {
				continue;
			}

			var prefix = field.name() + '.';
			MutableSet<String> deeper = null;
			for(var name : names) {
				if(name.startsWith(prefix)) {
					if(deeper == null) {
						deeper = Sets.mutable.empty();
					}

					deeper.add(name.substring(prefix.length()));
				}
			}

			if(deeper != null) {
				values.add(
					new Document.Value(
						field.name(),
						cutInner(innerObject, deeper),
						field.locale()
					)
				);
			}
		}

		return new Document(values.toArray(new Document.Value[0]));
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
