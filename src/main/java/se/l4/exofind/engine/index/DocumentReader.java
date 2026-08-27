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
		this.localeSpecific = schema.getFields().anySatisfy(Field::isLocaleSpecific);

		this.whole = Maps.mutable.empty();
		this.inside = Maps.mutable.empty();

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
	 * specific, or of a field the definition no longer has.
	 */
	private Field localeSpecificField(String name) {
		var field = schema.getField(name);
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
	 * being answered; the fields inside an object are never locale specific, so
	 * what a caller looks at here is the same either way.
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
		var source = doc.getBinaryValue(FieldNames.SOURCE);
		if(source == null) {
			return new WithSource(read(doc), null);
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

		/*
		 * The locale specific fields the document holds and the variants it
		 * holds them in, gathered before anything is judged because which value
		 * of a field answers depends on which of them are there. Left
		 * unallocated for a document that holds no such field, which is every
		 * document of an index whose locale specific fields are not among the
		 * ones being read.
		 */
		MutableMap<String, Field> localized = null;
		MutableMap<String, MutableSet<String>> held = null;

		for(var value : doc.fields()) {
			var field = localeSpecificField(value.name());
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

		if(localized == null) {
			return doc;
		}

		var keep = Maps.mutable.<String, String>empty();
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
			 * The document was never given this variant, so what a search read
			 * of the field is what the index filled it with - the first locale
			 * of the chain the document does hold, found here the way it was
			 * found when the document was indexed. An index that fills nothing
			 * has no chain, and the field is left out.
			 */
			var filled = schema.getLocaleFallbackChain(entry.getTwo())
				.detect(variants::contains);

			if(filled != null) {
				keep.put(entry.getOne(), filled);
			}
		}

		var values = Lists.mutable.<Document.Value>empty();
		var cut = false;

		for(var value : doc.fields()) {
			var field = localized.get(value.name());
			if(field == null) {
				values.add(value);
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
