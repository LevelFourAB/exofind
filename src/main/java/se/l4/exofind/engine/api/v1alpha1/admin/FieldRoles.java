package se.l4.exofind.engine.api.v1alpha1.admin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import se.l4.exofind.engine.api.v1alpha1.admin.model.AnalyzerDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition.Role;
import se.l4.exofind.engine.api.v1alpha1.admin.model.GeoPointFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ObjectFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.StringFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.TimestampFieldDefinition;
import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Expands the {@link Role} of a field into the usages it stands for.
 *
 * <p>A role is a name for a combination that gives a good result, and this is
 * the one place that says what each name means. Expansion happens before a
 * definition is stored, so the stored definition holds the usages rather than
 * the name: an index keeps answering the way it did when it was defined, and a
 * role can be improved without a node upgrade changing an index that already
 * exists. Nothing read back from storage ever carries a role.
 *
 * <p>What is given beside a role is kept as it was given, one property at a
 * time, so {@code "role": "title"} with {@code "matching": { "weight": 8 }}
 * takes the weight from the caller and the rest of {@code matching} from the
 * role.
 *
 * <p>A role turns on only what its surroundings accept. Below a flattened list
 * that leaves {@code stored} and {@code sort} alone, because an object field
 * refuses those there.
 *
 * <p>Adding a role is a name in {@link Role}, a template here, and a row in the
 * field types reference. A name, once released, is written by callers and can
 * never be renamed or given a different meaning.
 */
public class FieldRoles {
	private static final ErrorType NOT_VALID_FOR_TYPE =
		ErrorType.withCode("index:field:role:not_valid_for_type")
			.withArguments("name", "role", "type")
			.withMessage(
				"Field `{{name}}` has role `{{role}}`, which no `{{type}}` field can answer for"
			);

	private static final ErrorType NOT_VALID_IN_OBJECT =
		ErrorType.withCode("index:field:role:not_valid_in_object")
			.withArguments("name", "role")
			.withMessage(
				"Field `{{name}}` has role `{{role}}`, which can not be used inside an object field"
			);

	private static final FieldDefinition.Filter FILTER = new FieldDefinition.Filter();

	private static final FieldDefinition.Facet FACET = new FieldDefinition.Facet();

	private static final FieldDefinition.Sort SORT = new FieldDefinition.Sort(null, null);

	private static final StringFieldDefinition.TextUsage.Highlight HIGHLIGHT =
		new StringFieldDefinition.TextUsage.Highlight();

	private static final StringFieldDefinition.TextUsage.TypoTolerance TYPO_TOLERANCE =
		new StringFieldDefinition.TextUsage.TypoTolerance(null, null, null, null);

	private static final StringFieldDefinition.TextUsage.Exact EXACT =
		new StringFieldDefinition.TextUsage.Exact(null);

	/**
	 * A usage turned on with nothing said about it, so the engine builds the
	 * chain from the slot and the locale of the value.
	 */
	private static final StringFieldDefinition.TextUsage DEFAULT_USAGE =
		new StringFieldDefinition.TextUsage(null, null, null, null, null, null, null);

	private static final AnalyzerDefinition PRESERVE_TERMS =
		new AnalyzerDefinition(AnalyzerDefinition.Preset.PRESERVE_TERMS, null, null);

	/**
	 * Where a field sits, which decides what a role may turn on. The two list
	 * flags are sticky the way the validation in {@code ObjectFieldType}
	 * treats them: once a path passes through a flattened or nested list,
	 * every field below it shares that position however deep the objects
	 * nest.
	 *
	 * @param inObject
	 *   whether the field sits inside any object
	 * @param underFlattenedList
	 *   whether a flattened list sits anywhere on the path above the field
	 * @param underNested
	 *   whether a nested list sits anywhere on the path above the field
	 */
	private record Context(
		boolean inObject,
		boolean underFlattenedList,
		boolean underNested
	) {
		static final Context ROOT = new Context(false, false, false);

		/**
		 * The context of the fields inside an object field sitting in this
		 * one.
		 */
		Context inside(ObjectFieldDefinition field) {
			var multiple = Boolean.TRUE.equals(field.multiple());

			return new Context(
				true,
				underFlattenedList
					|| (multiple && field.mode() == ObjectFieldDefinition.Mode.FLATTENED),
				underNested
					|| (multiple && field.mode() == ObjectFieldDefinition.Mode.NESTED)
			);
		}
	}

	private FieldRoles() {
	}

	/**
	 * Replace every role in a definition with the usages it stands for.
	 *
	 * @param definition
	 *   definition as it was received
	 * @return
	 *   the same definition with no field carrying a role
	 * @throws EngineException
	 *   with {@code index:field:role:not_valid_for_type} if a field names a
	 *   role its type can not answer for, or
	 *   {@code index:field:role:not_valid_in_object} if a field inside an
	 *   object names a role only a field of the index itself can carry
	 */
	public static IndexDefinition expand(IndexDefinition definition) {
		if(definition.fields() == null) {
			return definition;
		}

		return new IndexDefinition(
			definition.source(),
			definition.metadata(),
			expandFields(definition.fields(), Context.ROOT),
			definition.ranking(),
			definition.resources(),
			definition.localeFallback()
		);
	}

	private static Map<String, FieldDefinition> expandFields(
		Map<String, FieldDefinition> fields,
		Context context
	) {
		var expanded = new LinkedHashMap<String, FieldDefinition>();
		for(var entry : fields.entrySet()) {
			expanded.put(entry.getKey(), expand(entry.getKey(), entry.getValue(), context));
		}
		return expanded;
	}

	private static FieldDefinition expand(
		String name,
		FieldDefinition field,
		Context context
	) {
		return switch(field) {
			case StringFieldDefinition string -> expandString(name, string, context);
			case TimestampFieldDefinition timestamp -> expandTimestamp(name, timestamp);
			case GeoPointFieldDefinition geo -> expandGeoPoint(name, geo);
			case ObjectFieldDefinition object -> expandObject(object, context);
			default -> field;
		};
	}

	private static ObjectFieldDefinition expandObject(
		ObjectFieldDefinition field,
		Context outside
	) {
		if(field.fields() == null) {
			return field;
		}

		/*
		 * A single object is one unit whether or not it says so, so only a
		 * list that says it is flattened loses sorting - and whatever list
		 * the object itself sits below follows its fields down.
		 */
		var context = outside.inside(field);

		return new ObjectFieldDefinition(
			field.primaryKey(),
			field.required(),
			field.multiple(),
			field.stored(),
			field.locales(),
			field.filter(),
			field.sort(),
			field.facet(),
			field.mode(),
			field.key(),
			expandFields(field.fields(), context)
		);
	}

	private static StringFieldDefinition expandString(
		String name,
		StringFieldDefinition field,
		Context context
	) {
		var role = field.role();
		if(role == null) {
			return field;
		}

		var template = switch(role) {
			case ID -> {
				if(context.inObject()) {
					throw notValidInObject(name, role);
				}

				yield template(
					Boolean.TRUE,
					Boolean.TRUE,
					Boolean.TRUE,
					FILTER,
					null,
					null,
					null,
					null,
					null
				);
			}
			case TITLE -> template(
				null,
				null,
				stored(context),
				null,
				sort(context),
				null,
				new StringFieldDefinition.TextUsage(
					null,
					3f,
					highlight(context),
					TYPO_TOLERANCE,
					null,
					EXACT,
					StringFieldDefinition.TextUsage.LengthNormalization.STRONG
				),
				DEFAULT_USAGE,
				null
			);
			case DESCRIPTION -> template(
				null,
				null,
				stored(context),
				null,
				null,
				null,
				new StringFieldDefinition.TextUsage(
					null,
					null,
					highlight(context),
					TYPO_TOLERANCE,
					null,
					null,
					StringFieldDefinition.TextUsage.LengthNormalization.NONE
				),
				null,
				null
			);
			case TAG -> template(
				null,
				null,
				null,
				FILTER,
				null,
				FACET,
				new StringFieldDefinition.TextUsage(
					PRESERVE_TERMS,
					null,
					null,
					null,
					null,
					null,
					null
				),
				null,
				null
			);
			case PATH -> template(
				null,
				null,
				null,
				FILTER,
				null,
				FACET,
				null,
				null,
				new StringFieldDefinition.Hierarchy(null)
			);
			case CODE -> template(
				null,
				null,
				stored(context),
				FILTER,
				null,
				null,
				new StringFieldDefinition.TextUsage(
					PRESERVE_TERMS,
					null,
					null,
					null,
					null,
					null,
					StringFieldDefinition.TextUsage.LengthNormalization.NONE
				),
				/*
				 * The engine-built autocomplete chain keeps every word whole
				 * already, and it is what adds the prefixes a search matches -
				 * naming a chain here would leave the field with none.
				 */
				DEFAULT_USAGE,
				null
			);
			case TIMESTAMP, GEO -> throw notValidForType(name, role, "string");
		};

		return merge(field, template);
	}

	private static TimestampFieldDefinition expandTimestamp(
		String name,
		TimestampFieldDefinition field
	) {
		var role = field.role();
		if(role == null) {
			return field;
		}

		if(role != Role.TIMESTAMP) {
			throw notValidForType(name, role, "timestamp");
		}

		return new TimestampFieldDefinition(
			null,
			field.primaryKey(),
			field.required(),
			field.multiple(),
			field.stored(),
			field.locales(),
			field.filter() != null ? field.filter() : FILTER,
			field.sort() != null ? field.sort() : SORT,
			field.facet() != null ? field.facet() : FACET
		);
	}

	private static GeoPointFieldDefinition expandGeoPoint(
		String name,
		GeoPointFieldDefinition field
	) {
		var role = field.role();
		if(role == null) {
			return field;
		}

		if(role != Role.GEO) {
			throw notValidForType(name, role, "geo_point");
		}

		return new GeoPointFieldDefinition(
			null,
			field.primaryKey(),
			field.required(),
			field.multiple(),
			field.stored(),
			field.locales(),
			field.filter() != null ? field.filter() : FILTER,
			field.sort() != null ? field.sort() : SORT,
			field.facet()
		);
	}

	/**
	 * Build what a string role turns on, as a definition holding nothing else.
	 */
	private static StringFieldDefinition template(
		Boolean primaryKey,
		Boolean required,
		Boolean stored,
		FieldDefinition.Filter filter,
		FieldDefinition.Sort sort,
		FieldDefinition.Facet facet,
		StringFieldDefinition.TextUsage matching,
		StringFieldDefinition.TextUsage autocomplete,
		StringFieldDefinition.Hierarchy hierarchy
	) {
		return new StringFieldDefinition(
			null,
			primaryKey,
			required,
			null,
			stored,
			null,
			filter,
			sort,
			facet,
			null,
			matching,
			autocomplete,
			hierarchy
		);
	}

	/**
	 * Take every property the field left unset from the template, and drop the
	 * role now that it has been read.
	 */
	private static StringFieldDefinition merge(
		StringFieldDefinition field,
		StringFieldDefinition template
	) {
		return new StringFieldDefinition(
			null,
			first(field.primaryKey(), template.primaryKey()),
			first(field.required(), template.required()),
			field.multiple(),
			first(field.stored(), template.stored()),
			field.locales(),
			first(field.filter(), template.filter()),
			first(field.sort(), template.sort()),
			first(field.facet(), template.facet()),
			field.keyword(),
			mergeUsage(field.matching(), template.matching()),
			mergeUsage(field.autocomplete(), template.autocomplete()),
			first(field.hierarchy(), template.hierarchy())
		);
	}

	private static StringFieldDefinition.TextUsage mergeUsage(
		StringFieldDefinition.TextUsage usage,
		StringFieldDefinition.TextUsage template
	) {
		if(usage == null || template == null) {
			return first(usage, template);
		}

		return new StringFieldDefinition.TextUsage(
			first(usage.analyzer(), template.analyzer()),
			first(usage.weight(), template.weight()),
			first(usage.highlight(), template.highlight()),
			first(usage.typoTolerance(), template.typoTolerance()),
			first(usage.decompound(), template.decompound()),
			first(usage.exact(), template.exact()),
			first(usage.lengthNormalization(), template.lengthNormalization())
		);
	}

	private static <T> T first(T value, T fallback) {
		return value != null ? value : fallback;
	}

	/**
	 * Whether a role may keep a value for reading back, which a flattened
	 * list refuses however deep below it the field sits - a single object and
	 * a nested list keep it.
	 */
	private static Boolean stored(Context context) {
		return context.underFlattenedList() ? null : Boolean.TRUE;
	}

	/**
	 * Whether a role may order by the field, which a flattened list refuses
	 * however deep below it the field sits.
	 */
	private static FieldDefinition.Sort sort(Context context) {
		return context.underFlattenedList() ? null : SORT;
	}

	/**
	 * Whether a role may mark where the matches were, which every position
	 * accepts - a flattened field's text lives in the document the way a root
	 * field's does, and a field below a nested list highlights on the hits
	 * that stand for the list's values.
	 */
	private static StringFieldDefinition.TextUsage.Highlight highlight(Context context) {
		return HIGHLIGHT;
	}

	private static EngineException notValidForType(String name, Role role, String type) {
		return new EngineException(
			NOT_VALID_FOR_TYPE,
			"name", name,
			"role", jsonName(role),
			"type", type
		);
	}

	private static EngineException notValidInObject(String name, Role role) {
		return new EngineException(
			NOT_VALID_IN_OBJECT,
			"name", name,
			"role", jsonName(role)
		);
	}

	private static String jsonName(Role role) {
		return role.name().toLowerCase(Locale.ROOT);
	}
}
