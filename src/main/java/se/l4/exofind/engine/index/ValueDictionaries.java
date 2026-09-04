package se.l4.exofind.engine.index;

import java.util.Map;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.schema.IndexSchema;
import se.l4.exofind.engine.index.settings.FieldSettings;

/**
 * The fields of an index whose stored values a search reads out of the typed
 * text, as the search settings name them, compiled against one generation.
 *
 * <p>A search in user mode looks the words a person typed up among the values
 * of every field listed here. A word that is a value of a field is read as a
 * filter on that field, see {@link Interpretation}. The values come from the
 * facet doc values of the generation that answers the search, so nothing is
 * stored for this beyond the name of the field.
 *
 * <p>A field can be read when it is a string field with both {@code filter}
 * and {@code facet}, and no {@code hierarchy}: the facet holds the dictionary
 * of values, and a reading compiles to a filter. The settings outlive
 * generations, so a field the generation lacks or cannot read is skipped and
 * carried in {@link #skippedFields()} instead of failing the search.
 *
 * <p>Immutable and safe to share between threads.
 */
public final class ValueDictionaries {
	private static final ErrorType UNKNOWN_FIELD =
		ErrorType.withCode("index:settings:fields:unknown_field")
			.withArguments("field")
			.withMessage("Settings are given for `{{field}}`, which the index does not have");

	private static final ErrorType INTERPRET_UNSUPPORTED =
		ErrorType.withCode("index:settings:fields:interpret_unsupported")
			.withArguments("field")
			.withMessage(
				"The values of `{{field}}` can not be read from the search text; reading needs"
					+ " a string field with `filter` and `facet` and without `hierarchy`"
			);

	private static final ValueDictionaries NONE = new ValueDictionaries(
		Lists.immutable.empty(),
		Lists.immutable.empty()
	);

	/**
	 * One field whose values are read.
	 *
	 * @param name
	 *   the field, named as the definition names it
	 * @param nestedPath
	 *   the object field the values sit inside, when a filter on the field
	 *   has to run against one value of a list at a time, or {@code null}
	 */
	public record Entry(String name, String nestedPath) {
	}

	private final ImmutableList<Entry> fields;
	private final ListIterable<String> skippedFields;

	private ValueDictionaries(ImmutableList<Entry> fields, ListIterable<String> skippedFields) {
		this.fields = fields;
		this.skippedFields = skippedFields;
	}

	/**
	 * Get the dictionaries of an index whose settings name no field, which
	 * reads every typed word as text.
	 *
	 * @return
	 */
	public static ValueDictionaries none() {
		return NONE;
	}

	/**
	 * Compile the field settings of an index against one generation.
	 *
	 * @param settings
	 *   the settings by field name, as stored
	 * @param schema
	 *   the generation the values are read from
	 * @return
	 */
	public static ValueDictionaries compile(Map<String, FieldSettings> settings, IndexSchema schema) {
		var fields = Lists.mutable.<Entry>empty();
		var skipped = Lists.mutable.<String>empty();

		for(var entry : settings.entrySet()) {
			if(!entry.getValue().hasInterpret()) {
				continue;
			}

			var name = entry.getKey();
			var found = resolve(schema, name);
			if(found == null || !isReadable(found.field())) {
				skipped.add(name);
				continue;
			}

			fields.add(found.entry());
		}

		if(fields.isEmpty() && skipped.isEmpty()) {
			return NONE;
		}

		return new ValueDictionaries(
			fields.toSortedListBy(Entry::name).toImmutable(),
			skipped.toSortedList()
		);
	}

	/**
	 * Validate the field settings of an index against one generation. What
	 * passes here can still be skipped by a later generation - see
	 * {@link #compile} - so this is the check for storing settings, not for
	 * searching with them.
	 *
	 * @param settings
	 *   the settings by field name, as they would be stored
	 * @param schema
	 *   the generation the index name answers from
	 * @param location
	 *   where the settings sit in what the caller is validating, for the
	 *   errors to point into it
	 * @return
	 *   what stops the settings, empty when this generation answers for all of
	 *   them
	 */
	public static ListIterable<ErrorMessage> validate(
		Map<String, FieldSettings> settings,
		IndexSchema schema,
		ObjectLocation location
	) {
		var errors = Lists.mutable.<ErrorMessage>empty();

		for(var entry : settings.entrySet()) {
			var name = entry.getKey();
			var at = location.forField(name);

			var found = resolve(schema, name);
			if(found == null) {
				errors.add(UNKNOWN_FIELD.toMessage(at, "field", name));
				continue;
			}

			if(entry.getValue().hasInterpret() && !isReadable(found.field())) {
				errors.add(INTERPRET_UNSUPPORTED.toMessage(at.forField("interpret"), "field", name));
			}
		}

		return errors;
	}

	/**
	 * Get whether no field is read.
	 *
	 * @return
	 */
	public boolean isEmpty() {
		return fields.isEmpty();
	}

	/**
	 * Get the fields whose values are read, sorted by name.
	 *
	 * @return
	 */
	public ListIterable<Entry> fields() {
		return fields;
	}

	/**
	 * Get the fields the settings name that this generation cannot read,
	 * sorted. Empty when every named field is read.
	 *
	 * @return
	 */
	public ListIterable<String> skippedFields() {
		return skippedFields;
	}

	/**
	 * Find a named field wherever it sits, with the field it was found in.
	 */
	private record Resolved(Field field, Entry entry) {
	}

	private static Resolved resolve(IndexSchema schema, String name) {
		var nested = schema.getNestedField(name);
		if(nested.isPresent()) {
			var path = nested.get().path();
			var inside = schema.getField(path).map(Field::isNestedObject).orElse(false);
			return new Resolved(nested.get().field(), new Entry(name, inside ? path : null));
		}

		var field = schema.getField(name).orElse(null);
		return field == null ? null : new Resolved(field, new Entry(name, null));
	}

	/**
	 * Whether a field holds a dictionary a reading can be looked up in, and a
	 * filter the reading can compile to.
	 */
	private static boolean isReadable(Field field) {
		var def = field.getDef();
		return def.getType().hasString()
			&& !def.getType().getString().hasHierarchy()
			&& def.hasFilter()
			&& def.hasFacet();
	}
}
