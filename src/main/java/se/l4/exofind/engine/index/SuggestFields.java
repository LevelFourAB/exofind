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
 * The fields of an index whose stored values are suggested while a search is
 * typed, as the search settings name them, compiled against one generation.
 *
 * <p>A suggest request looks the typed text up among the values of every
 * field listed here, see {@link Index#suggest}. The values come from the
 * facet doc values of the generation that answers, so nothing is stored for
 * this beyond the name of the field.
 *
 * <p>A field can be suggested when it is a string field with {@code facet}
 * and no {@code hierarchy}: the facet holds the dictionary of values. The
 * settings outlive generations, so a field the generation lacks or cannot
 * suggest is skipped and carried in {@link #skippedFields()} instead of
 * failing the request.
 *
 * <p>Immutable and safe to share between threads.
 */
public final class SuggestFields {
	private static final ErrorType UNKNOWN_FIELD =
		ErrorType.withCode("index:settings:fields:unknown_field")
			.withArguments("field")
			.withMessage("Settings are given for `{{field}}`, which the index does not have");

	private static final ErrorType SUGGEST_UNSUPPORTED =
		ErrorType.withCode("index:settings:fields:suggest_unsupported")
			.withArguments("field")
			.withMessage(
				"The values of `{{field}}` can not be suggested; suggesting needs a string"
					+ " field with `facet` and without `hierarchy`"
			);

	private static final SuggestFields NONE = new SuggestFields(
		Lists.immutable.empty(),
		Lists.immutable.empty()
	);

	private final ImmutableList<String> fields;
	private final ListIterable<String> skippedFields;

	private SuggestFields(ImmutableList<String> fields, ListIterable<String> skippedFields) {
		this.fields = fields;
		this.skippedFields = skippedFields;
	}

	/**
	 * Get the fields of an index whose settings name none, which suggests
	 * nothing.
	 *
	 * @return
	 */
	public static SuggestFields none() {
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
	public static SuggestFields compile(Map<String, FieldSettings> settings, IndexSchema schema) {
		var fields = Lists.mutable.<String>empty();
		var skipped = Lists.mutable.<String>empty();

		for(var entry : settings.entrySet()) {
			if(!entry.getValue().hasSuggest()) {
				continue;
			}

			var name = entry.getKey();
			var field = resolve(schema, name);
			if(field == null || !isSuggestable(field)) {
				skipped.add(name);
				continue;
			}

			fields.add(name);
		}

		if(fields.isEmpty() && skipped.isEmpty()) {
			return NONE;
		}

		return new SuggestFields(
			fields.toSortedList().toImmutable(),
			skipped.toSortedList()
		);
	}

	/**
	 * Validate the field settings of an index against one generation. What
	 * passes here can still be skipped by a later generation - see
	 * {@link #compile} - so this is the check for storing settings, not for
	 * suggesting with them.
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
			if(!entry.getValue().hasSuggest()) {
				continue;
			}

			var name = entry.getKey();
			var at = location.forField(name);

			var field = resolve(schema, name);
			if(field == null) {
				errors.add(UNKNOWN_FIELD.toMessage(at, "field", name));
				continue;
			}

			if(!isSuggestable(field)) {
				errors.add(SUGGEST_UNSUPPORTED.toMessage(at.forField("suggest"), "field", name));
			}
		}

		return errors;
	}

	/**
	 * Get whether no field is suggested.
	 *
	 * @return
	 */
	public boolean isEmpty() {
		return fields.isEmpty();
	}

	/**
	 * Get the fields whose values are suggested, sorted by name.
	 *
	 * @return
	 */
	public ListIterable<String> fields() {
		return fields;
	}

	/**
	 * Get the fields the settings name that this generation cannot suggest,
	 * sorted. Empty when every named field is suggested.
	 *
	 * @return
	 */
	public ListIterable<String> skippedFields() {
		return skippedFields;
	}

	private static Field resolve(IndexSchema schema, String name) {
		var nested = schema.getNestedField(name);
		if(nested.isPresent()) {
			return nested.get().field();
		}

		return schema.getField(name).orElse(null);
	}

	/**
	 * Whether a field holds a dictionary of values a prefix can be looked up
	 * in.
	 */
	private static boolean isSuggestable(Field field) {
		var def = field.getDef();
		return def.getType().hasString()
			&& !def.getType().getString().hasHierarchy()
			&& def.hasFacet();
	}
}
