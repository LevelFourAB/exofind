package se.l4.exofind.engine.index;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.set.SetIterable;

/**
 * A change to some of the fields of a document that is already indexed.
 *
 * <p>The unit of a patch is a field: a field the patch names is replaced by
 * what the patch gives it, whether that is one value, several, or none at all -
 * naming a field and giving it nothing is what empties it. A field the patch
 * does not name keeps whatever the document holds.
 *
 * <p>A field is replaced whole, so a locale specific field named by a patch
 * holds the locales the patch gives it and no others, and an object field named
 * by a patch holds the values the patch gives it rather than merging into the
 * ones it had.
 *
 * @param fields
 *   the names the patch speaks about, which are the ones taken out of the
 *   document before the values are added back
 * @param values
 *   what those fields become. Every value here has a name in {@code fields}
 */
public record DocumentPatch(SetIterable<String> fields, ListIterable<Document.Value> values) {
	/**
	 * Apply this patch to the document it changes.
	 *
	 * <p>The values a patch leaves alone come first and in the order the
	 * document held them, then the values the patch gives. Fields therefore
	 * come back in a different order than they were first given in, which
	 * nothing reading a document depends on - values within one field keep the
	 * order they were given in.
	 *
	 * @param base
	 *   the document as it is indexed now
	 * @return
	 *   a new document; {@code base} is left as it was
	 */
	public Document applyTo(Document base) {
		var merged = Lists.mutable.<Document.Value>ofInitialCapacity(
			base.fields().length + values.size()
		);

		for(var value : base.fields()) {
			if(!fields.contains(value.name())) {
				merged.add(value);
			}
		}

		merged.addAllIterable(values);

		return new Document(merged.toArray(new Document.Value[0]));
	}

	/**
	 * Get the value the patch gives a field, for a field it gives a single one.
	 *
	 * @return
	 *   the value, or {@code null} when the patch does not name the field or
	 *   names it without giving it anything
	 */
	public Object get(String name) {
		for(var value : values) {
			if(value.name().equals(name)) {
				return value.value();
			}
		}

		return null;
	}
}
