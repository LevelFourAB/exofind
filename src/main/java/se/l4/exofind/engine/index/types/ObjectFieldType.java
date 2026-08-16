package se.l4.exofind.engine.index.types;

import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;
import org.eclipse.collections.api.collection.MutableCollection;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.impl.factory.Lists;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.IndexEncounter;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.query.matchers.Matcher;

/**
 * Field type whose values are documents of their own, described by the fields
 * of an {@code ObjectFieldTypeDef}.
 *
 * The type is a container rather than a value: it can not be filtered, sorted
 * or matched on itself, and its values never become Lucene fields of the
 * document that holds them - each one is indexed as a document of its own,
 * which is what lets a search ask that several conditions hold inside the same
 * value. That indexing lives in {@code Index}, so the two field-producing
 * methods here are never reached; what this class owns is judging the
 * definition.
 *
 * The fields inside an object are held to the usages that work across a join to
 * the document a value belongs to. Filtering, matching, sorting and faceting
 * all do: a value says whether its document matches, how well, where it is
 * ordered and what it is counted under, and which values answer is what the
 * {@code nested} clauses of a search decide. Refused are the usages that only
 * mean something for a document of the index - being its primary key, being
 * highlighted, which reads the fields of the document rather than of a value -
 * as are locale variants, stored values and objects inside objects.
 */
public class ObjectFieldType implements FieldType {
	private static final ErrorType NO_FIELDS = ErrorType
		.withCode("index:field:object:no_fields")
		.withMessage("An object needs at least one field");

	private static final ErrorType USAGE_NOT_SUPPORTED = ErrorType
		.withCode("index:field:object:usage_not_supported")
		.withArguments("usage")
		.withMessage(
			"An object holds no value of its own, so it can not be defined for `{{usage}}`"
		);

	private static final ErrorType INNER_USAGE_NOT_SUPPORTED = ErrorType
		.withCode("index:field:object:inner_usage_not_supported")
		.withArguments("name", "usage")
		.withMessage(
			"Field `{{name}}` is inside an object, where `{{usage}}` is not supported"
		);

	private static final ErrorType INNER_OBJECT = ErrorType
		.withCode("index:field:object:inner_object")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is an object inside an object, which is not supported"
		);

	private static final ErrorType INNER_WILDCARD = ErrorType
		.withCode("index:field:object:inner_wildcard")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is inside an object, where names with wildcards are not supported"
		);

	@Override
	public boolean isSortingSupported() {
		return false;
	}

	@Override
	public boolean isDocValuesSupported() {
		return false;
	}

	@Override
	public ListIterable<ErrorMessage> validate(
		ObjectLocation location,
		FieldDef def,
		ResourcesDef resources
	) {
		var errors = Lists.mutable.<ErrorMessage>empty();

		if(def.hasFilter()) {
			errors.add(USAGE_NOT_SUPPORTED.toMessage(location, "usage", "filter"));
		}

		if(def.hasStored() && def.getStored()) {
			errors.add(USAGE_NOT_SUPPORTED.toMessage(location, "usage", "stored"));
		}

		if(def.hasLocales()) {
			errors.add(USAGE_NOT_SUPPORTED.toMessage(location, "usage", "locales"));
		}

		var objectType = def.getType().getObject();
		if(objectType.getFieldsCount() == 0) {
			errors.add(NO_FIELDS.toMessage(location));
		}

		for(var entry : objectType.getFieldsMap().entrySet()) {
			validateInner(
				location.forField("fields").forField(entry.getKey()),
				entry.getKey(),
				entry.getValue(),
				resources,
				errors
			);
		}

		return errors;
	}

	private static void validateInner(
		ObjectLocation location,
		String name,
		FieldDef def,
		ResourcesDef resources,
		MutableCollection<ErrorMessage> errors
	) {
		if(name.contains("*")) {
			errors.add(INNER_WILDCARD.toMessage(location, "name", name));
		}

		if(def.getType().getTypeCase() == FieldTypeDef.TypeCase.OBJECT) {
			/*
			 * Refused before the general validation runs, which would recurse
			 * into it and report its fields as if they could exist.
			 */
			errors.add(INNER_OBJECT.toMessage(location, "name", name));
			return;
		}

		if(def.getPrimaryKey()) {
			errors.add(INNER_USAGE_NOT_SUPPORTED.toMessage(
				location, "name", name, "usage", "primary_key"
			));
		}

		if(def.hasStored() && def.getStored()) {
			errors.add(INNER_USAGE_NOT_SUPPORTED.toMessage(
				location, "name", name, "usage", "stored"
			));
		}

		if(def.hasLocales()) {
			errors.add(INNER_USAGE_NOT_SUPPORTED.toMessage(
				location, "name", name, "usage", "locales"
			));
		}

		/*
		 * Highlighting reads the text a search matched back out of the document
		 * it is shown for, and a value is not that document. Refused rather
		 * than written and never readable.
		 */
		if(def.getType().getTypeCase() == FieldTypeDef.TypeCase.STRING) {
			var string = def.getType().getString();
			if(string.getMatching().hasHighlight() || string.getAutocomplete().hasHighlight()) {
				errors.add(INNER_USAGE_NOT_SUPPORTED.toMessage(
					location, "name", name, "usage", "highlight"
				));
			}
		}

		errors.addAllIterable(Field.validate(location, name, def, resources));
	}

	@Override
	public Iterable<? extends IndexableField> createFields(IndexEncounter encounter, Object value) {
		/*
		 * Object values never turn into fields of the document that holds them;
		 * Index writes each one as a document of its own before this could be
		 * reached.
		 */
		throw new UnsupportedOperationException(
			"Object values are indexed as documents of their own"
		);
	}

	@Override
	public Query createQuery(IndexEncounter encounter, Matcher matcher) {
		throw new IndexInvalidQueryTypeException("object", matcher.id());
	}
}
