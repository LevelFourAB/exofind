package se.l4.exofind.engine.index;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.SetIterable;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * A change to some of the fields of a document that is already indexed.
 *
 * <p>The unit of a patch is a place in the document, named by a
 * {@link DocumentPath}: the place a change names is replaced by what the
 * change gives it, whether that is one value, several, or none at all - naming
 * a place and giving it nothing is what empties it. A place no change names
 * keeps whatever the document holds.
 *
 * <p>A place is replaced whole, so what a change leaves alone is decided by how
 * deeply it reaches. A change to {@code variants} replaces every value of the
 * field; a change to {@code variants[sku=V-2]} replaces one of them and leaves
 * the others; a change to {@code variants[sku=V-2].price} replaces one field
 * inside that value and leaves the rest of it. The same holds across locales:
 * {@code title} replaces every variant, {@code title[sv]} replaces one.
 *
 * <p>Changes are applied in the order they are held, so two changes to the same
 * place leave what the later one gives it.
 *
 * @param changes
 *   the places the patch speaks about and what they become
 */
public record DocumentPatch(ListIterable<Change> changes) {
	private static final ErrorType NO_MATCH = ErrorType
		.withCode("request:update:no_match")
		.withArguments("path")
		.withMessage("`{{path}}` names no value the document holds");

	/**
	 * Which values of a field a change is about.
	 */
	public sealed interface Selector {
		/**
		 * Every value the field holds.
		 */
		Selector ALL = new All();

		/**
		 * A value added to the ones the field holds, leaving those alone.
		 */
		Selector ADDED = new Added();

		record All() implements Selector {
		}

		record Added() implements Selector {
		}

		/**
		 * The values the field holds in one locale.
		 *
		 * @param locale
		 *   BCP 47 tag of a locale the field declares, rather than the one the
		 *   change was written with - a field holding {@code no} is changed in
		 *   {@code no} by a change naming {@code nb-NO}
		 */
		record InLocale(String locale) implements Selector {
		}

		/**
		 * The object values holding a value for one of their own fields.
		 *
		 * @param field
		 *   name of the field inside the value, not the path to it
		 * @param value
		 *   what that field reads as. Values are compared as text, so a value
		 *   held as a number matches the digits that were written
		 */
		record Matching(String field, String value) implements Selector {
		}
	}

	/**
	 * One change of a patch.
	 *
	 * @param field
	 *   name of the field the change is about
	 * @param selector
	 *   which of its values
	 * @param inner
	 *   the field inside those values that the change is about, {@code null}
	 *   when the change is about the values themselves. Only ever set for a
	 *   field whose values are objects
	 * @param values
	 *   what the named place becomes, empty to empty it. Each carries the name
	 *   the place goes by where it sits, which for a change reaching inside an
	 *   object value is {@code inner} rather than {@code field}
	 */
	public record Change(
		String field,
		Selector selector,
		String inner,
		ListIterable<Document.Value> values
	) {
	}

	/**
	 * Build a patch that replaces whole fields, which is every field the values
	 * name plus the ones named without any.
	 *
	 * @param fields
	 *   the names to replace
	 * @param values
	 *   what they become. A value whose name is not in {@code fields} is
	 *   dropped
	 */
	public static DocumentPatch replacing(
		SetIterable<String> fields,
		ListIterable<Document.Value> values
	) {
		var changes = Lists.mutable.<Change>ofInitialCapacity(fields.size());

		for(var name : fields) {
			changes.add(new Change(
				name,
				Selector.ALL,
				null,
				values.select(value -> value.name().equals(name))
			));
		}

		return new DocumentPatch(changes.toImmutable());
	}

	/**
	 * Apply this patch to the document it changes.
	 *
	 * <p>Values the patch leaves alone keep the order the document held them
	 * in. A change that replaces a value in place leaves it where it was, so
	 * the order of the values of an object field survives a change to one of
	 * them; a change that replaces a whole field puts what it gives after
	 * everything else. Nothing reading a document depends on the order of its
	 * fields, while the values within one field keep the order they were given
	 * in.
	 *
	 * @param base
	 *   the document as it is indexed now, left as it was
	 * @return
	 *   a new document
	 * @throws ValidationException
	 *   if a change names a value by a selector the document holds nothing for.
	 *   A change that reaches into a value has nothing to reach into, and one
	 *   that replaces a value would have to invent where it sits
	 */
	public Document applyTo(Document base) {
		var values = Lists.mutable.of(base.fields());

		for(var change : changes) {
			apply(values, change);
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	private static void apply(MutableList<Document.Value> values, Change change) {
		switch(change.selector()) {
			case Selector.All ignored -> {
				if(change.inner() == null) {
					values.removeIf(value -> value.name().equals(change.field()));
					values.addAllIterable(change.values());
				} else {
					applyToOnlyValue(values, change);
				}
			}
			case Selector.Added ignored -> values.addAllIterable(change.values());
			case Selector.InLocale inLocale -> {
				values.removeIf(value -> value.name().equals(change.field())
					&& inLocale.locale().equals(value.locale()));
				values.addAllIterable(change.values());
			}
			case Selector.Matching matching -> applyToMatching(values, change, matching);
		}
	}

	/**
	 * Change one field inside the single object value a field holds, making
	 * that value where the document has none.
	 */
	private static void applyToOnlyValue(MutableList<Document.Value> values, Change change) {
		for(var i = 0; i < values.size(); i++) {
			var value = values.get(i);

			if(value.name().equals(change.field()) && value.value() instanceof Document object) {
				values.set(i, new Document.Value(
					change.field(),
					replaceInside(object, change.inner(), change.values()),
					value.locale()
				));

				return;
			}
		}

		values.add(new Document.Value(
			change.field(),
			replaceInside(new Document(), change.inner(), change.values())
		));
	}

	/**
	 * Change every object value a selector picks out.
	 */
	private static void applyToMatching(
		MutableList<Document.Value> values,
		Change change,
		Selector.Matching matching
	) {
		var matched = false;

		/*
		 * Walked from the end so that replacing a value with several, or with
		 * none, leaves the values still to be looked at where they are.
		 */
		for(var i = values.size() - 1; i >= 0; i--) {
			var value = values.get(i);

			if(!value.name().equals(change.field())
				|| !(value.value() instanceof Document object)
				|| !holds(object, matching)) {
				continue;
			}

			matched = true;

			if(change.inner() != null) {
				values.set(i, new Document.Value(
					change.field(),
					replaceInside(object, change.inner(), change.values()),
					value.locale()
				));
			} else {
				values.remove(i);
				values.addAll(i, change.values().toList());
			}
		}

		if(!matched) {
			throw new ValidationException(
				NO_MATCH.toMessage(
					ObjectLocation.root().forField(pathOf(change)),
					"path", pathOf(change)
				)
			);
		}
	}

	/**
	 * Get whether an object value holds what a selector asks of it.
	 */
	private static boolean holds(Document object, Selector.Matching matching) {
		for(var value : object.fields()) {
			if(value.name().equals(matching.field())
				&& matching.value().equals(String.valueOf(value.value()))) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Replace one field of an object value, leaving the rest of it.
	 */
	private static Document replaceInside(
		Document object,
		String inner,
		ListIterable<Document.Value> values
	) {
		var merged = Lists.mutable.<Document.Value>ofInitialCapacity(
			object.fields().length + values.size()
		);

		for(var value : object.fields()) {
			if(!value.name().equals(inner)) {
				merged.add(value);
			}
		}

		merged.addAllIterable(values);

		return new Document(merged.toArray(new Document.Value[0]));
	}

	/**
	 * Write the path a change names, for saying which one failed.
	 */
	private static String pathOf(Change change) {
		var selector = change.selector() instanceof Selector.Matching matching
			? matching.field() + '=' + matching.value()
			: null;

		return new DocumentPath(change.field(), selector, change.inner()).toString();
	}

	/**
	 * Get the value the patch gives a whole field, for a field it gives a
	 * single one.
	 *
	 * @return
	 *   the value, or {@code null} when no change replaces the field as a
	 *   whole, or one does without giving it anything
	 */
	public Object get(String name) {
		for(var change : changes) {
			if(change.inner() != null
				|| !(change.selector() instanceof Selector.All)
				|| !change.field().equals(name)) {
				continue;
			}

			if(change.values().notEmpty()) {
				return change.values().getFirst().value();
			}
		}

		return null;
	}
}
