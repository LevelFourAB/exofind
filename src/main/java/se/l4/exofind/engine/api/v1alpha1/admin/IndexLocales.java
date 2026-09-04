package se.l4.exofind.engine.api.v1alpha1.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import se.l4.exofind.engine.api.v1alpha1.admin.model.BooleanFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.DoubleFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FloatFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.GeoPointFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.Int32FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.Int64FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ObjectFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.StringFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.TimestampFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.VectorFieldDefinition;
import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.locales.Locales;

/**
 * Expands the locales an index declares onto its fields.
 *
 * <p>An index declares {@code locales} once. Every field that opts in with
 * {@code "locales": {}} holds all of them, and {@code only} narrows a field to
 * fewer. A narrowed field is given fewer fallback copies.
 *
 * <p>Expansion happens before a definition is stored, so a stored definition
 * holds the locales of each field and an index keeps answering the way it was
 * defined. Nothing read back from storage carries a declaration or an
 * {@code only}.
 *
 * <p>A field on such an index cannot list its own {@code locales}. An index
 * that mixes varieties of one language declares both and narrows each field to
 * the one it holds, such as {@code no} on one field and {@code nb} on another.
 *
 * <p>An index that declares no locales is left as it was given, and a field
 * that names {@code only} there is rejected.
 */
public class IndexLocales {
	private static final ErrorType DEFAULT_LOCALE_REQUIRED =
		ErrorType.withCode("index:locales:default_locale_required")
			.withMessage(
				"The `locales` of the index has no `defaultLocale`, which every field takes as its own"
			);

	private static final ErrorType NOT_DECLARED =
		ErrorType.withCode("index:field:locales:not_declared")
			.withArguments("name", "locale")
			.withMessage(
				"Field `{{name}}` names locale `{{locale}}`, which the index does not declare in its `locales`"
			);

	private static final ErrorType DEFAULT_NOT_IN_ONLY =
		ErrorType.withCode("index:field:locales:default_not_in_only")
			.withArguments("name", "locale")
			.withMessage(
				"Field `{{name}}` narrows to locales that leave out `{{locale}}`, the locale it defaults to"
			);

	private static final ErrorType ONLY_WITHOUT_DECLARATION =
		ErrorType.withCode("index:field:locales:only_without_declaration")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` narrows with `only`, but the index declares no `locales` to narrow"
			);

	private static final ErrorType LIST_WITH_DECLARATION =
		ErrorType.withCode("index:field:locales:list_with_declaration")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` lists its own `locales` while the index declares them - narrow with `only` instead"
			);

	private IndexLocales() {
	}

	/**
	 * Replace the locale declaration of an index with the locales each of its
	 * fields holds.
	 *
	 * @param definition
	 *   definition as it was received
	 * @return
	 *   the same definition with no locale declaration and no field carrying
	 *   {@code only}
	 * @throws EngineException
	 *   with {@code index:locales:default_locale_required} if the declaration
	 *   names no default locale,
	 *   {@code index:field:locales:not_declared} if a field names a locale the
	 *   index does not declare,
	 *   {@code index:field:locales:default_not_in_only} if a field narrows to
	 *   locales that leave out the one it defaults to,
	 *   {@code index:field:locales:list_with_declaration} if a field lists its
	 *   own locales on an index that declares them, or
	 *   {@code index:field:locales:only_without_declaration} if a field narrows
	 *   on an index that declares none
	 */
	public static IndexDefinition expand(IndexDefinition definition) {
		var declaration = definition.locales();

		if(declaration == null) {
			if(definition.fields() != null) {
				refuseOnly(definition.fields(), "");
			}

			return definition;
		}

		if(declaration.defaultLocale() == null || declaration.defaultLocale().isEmpty()) {
			throw new EngineException(DEFAULT_LOCALE_REQUIRED);
		}

		/*
		 * Canonical and deduplicated, with the default at the head where
		 * resolve reads it for a field that names none of its own.
		 */
		var declared = new ArrayList<String>();
		declared.add(Locales.canonical(declaration.defaultLocale()));
		if(declaration.supported() != null) {
			for(var locale : declaration.supported()) {
				var canonical = Locales.canonical(locale);
				if(!declared.contains(canonical)) {
					declared.add(canonical);
				}
			}
		}

		return new IndexDefinition(
			definition.source(),
			definition.metadata(),
			definition.fields() == null
				? null
				: expandFields(definition.fields(), "", declared),
			definition.ranking(),
			definition.resources(),
			null,
			definition.localeFallback()
		);
	}

	private static Map<String, FieldDefinition> expandFields(
		Map<String, FieldDefinition> fields,
		String prefix,
		List<String> declared
	) {
		var expanded = new LinkedHashMap<String, FieldDefinition>();
		for(var entry : fields.entrySet()) {
			expanded.put(
				entry.getKey(),
				expand(prefix + entry.getKey(), entry.getValue(), declared)
			);
		}
		return expanded;
	}

	private static FieldDefinition expand(
		String name,
		FieldDefinition field,
		List<String> declared
	) {
		var locales = field.locales() == null
			? null
			: resolve(name, field.locales(), declared);

		Map<String, FieldDefinition> fields = null;
		if(field instanceof ObjectFieldDefinition object && object.fields() != null) {
			fields = expandFields(object.fields(), name + ".", declared);
		}

		return rebuild(field, locales, fields);
	}

	/**
	 * Work out the locales one field holds from what it says and what the index
	 * declares.
	 */
	private static FieldDefinition.Locales resolve(
		String name,
		FieldDefinition.Locales locales,
		List<String> declared
	) {
		if(locales.locales() != null) {
			throw new EngineException(LIST_WITH_DECLARATION, "name", name);
		}

		var defaultLocale = locales.defaultLocale() != null
			? Locales.canonical(locales.defaultLocale())
			: declared.get(0);

		if(!declared.contains(defaultLocale)) {
			throw new EngineException(
				NOT_DECLARED,
				"name", name,
				"locale", defaultLocale
			);
		}

		var held = declared;
		if(locales.only() != null) {
			held = new ArrayList<>();
			for(var locale : locales.only()) {
				var canonical = Locales.canonical(locale);
				if(!declared.contains(canonical)) {
					throw new EngineException(
						NOT_DECLARED,
						"name", name,
						"locale", canonical
					);
				}
				if(!held.contains(canonical)) {
					held.add(canonical);
				}
			}

			if(!held.contains(defaultLocale)) {
				throw new EngineException(
					DEFAULT_NOT_IN_ONLY,
					"name", name,
					"locale", defaultLocale
				);
			}
		}

		var rest = new ArrayList<String>();
		for(var locale : held) {
			if(!locale.equals(defaultLocale)) {
				rest.add(locale);
			}
		}

		return new FieldDefinition.Locales(
			defaultLocale,
			rest.isEmpty() ? null : List.copyOf(rest),
			null,
			locales.fallback()
		);
	}

	/**
	 * Reject {@code only} where there is no declaration to narrow. Ignoring it
	 * would leave the field holding one locale and say nothing.
	 */
	private static void refuseOnly(Map<String, FieldDefinition> fields, String prefix) {
		for(var entry : fields.entrySet()) {
			var name = prefix + entry.getKey();
			var field = entry.getValue();

			if(field.locales() != null && field.locales().only() != null) {
				throw new EngineException(ONLY_WITHOUT_DECLARATION, "name", name);
			}

			if(field instanceof ObjectFieldDefinition object && object.fields() != null) {
				refuseOnly(object.fields(), name + ".");
			}
		}
	}

	/**
	 * Build a field with the locales it holds and, for an object, the fields
	 * below it.
	 *
	 * @param locales
	 *   the resolved locales, or {@code null} for a field that is not locale
	 *   specific
	 * @param fields
	 *   the expanded fields of an object, or {@code null} for any other field
	 */
	private static FieldDefinition rebuild(
		FieldDefinition field,
		FieldDefinition.Locales locales,
		Map<String, FieldDefinition> fields
	) {
		return switch(field) {
			case StringFieldDefinition string -> new StringFieldDefinition(
				string.role(),
				string.primaryKey(),
				string.required(),
				string.multiple(),
				string.stored(),
				locales,
				string.filter(),
				string.sort(),
				string.facet(),
				string.keyword(),
				string.matching(),
				string.autocomplete(),
				string.hierarchy()
			);
			case BooleanFieldDefinition value -> new BooleanFieldDefinition(
				value.primaryKey(),
				value.required(),
				value.multiple(),
				value.stored(),
				locales,
				value.filter(),
				value.sort(),
				value.facet()
			);
			case VectorFieldDefinition vector -> new VectorFieldDefinition(
				vector.primaryKey(),
				vector.required(),
				vector.multiple(),
				vector.stored(),
				locales,
				vector.filter(),
				vector.sort(),
				vector.facet(),
				vector.dimensions(),
				vector.similarity(),
				vector.hnsw(),
				vector.quantization()
			);
			case Int32FieldDefinition value -> new Int32FieldDefinition(
				value.primaryKey(),
				value.required(),
				value.multiple(),
				value.stored(),
				locales,
				value.filter(),
				value.sort(),
				value.facet(),
				value.validation(),
				value.unit()
			);
			case Int64FieldDefinition value -> new Int64FieldDefinition(
				value.primaryKey(),
				value.required(),
				value.multiple(),
				value.stored(),
				locales,
				value.filter(),
				value.sort(),
				value.facet(),
				value.validation(),
				value.unit()
			);
			case FloatFieldDefinition value -> new FloatFieldDefinition(
				value.primaryKey(),
				value.required(),
				value.multiple(),
				value.stored(),
				locales,
				value.filter(),
				value.sort(),
				value.facet(),
				value.validation(),
				value.unit()
			);
			case DoubleFieldDefinition value -> new DoubleFieldDefinition(
				value.primaryKey(),
				value.required(),
				value.multiple(),
				value.stored(),
				locales,
				value.filter(),
				value.sort(),
				value.facet(),
				value.validation(),
				value.unit()
			);
			case TimestampFieldDefinition timestamp -> new TimestampFieldDefinition(
				timestamp.role(),
				timestamp.primaryKey(),
				timestamp.required(),
				timestamp.multiple(),
				timestamp.stored(),
				locales,
				timestamp.filter(),
				timestamp.sort(),
				timestamp.facet()
			);
			case GeoPointFieldDefinition geo -> new GeoPointFieldDefinition(
				geo.role(),
				geo.primaryKey(),
				geo.required(),
				geo.multiple(),
				geo.stored(),
				locales,
				geo.filter(),
				geo.sort(),
				geo.facet()
			);
			case ObjectFieldDefinition object -> new ObjectFieldDefinition(
				object.primaryKey(),
				object.required(),
				object.multiple(),
				object.stored(),
				locales,
				object.filter(),
				object.sort(),
				object.facet(),
				object.mode(),
				object.key(),
				fields != null ? fields : object.fields()
			);
		};
	}
}
