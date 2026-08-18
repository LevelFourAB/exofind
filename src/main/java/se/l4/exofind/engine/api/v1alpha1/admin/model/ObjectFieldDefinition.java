package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Definition of a field whose values are objects, described by {@code fields}
 * the same way the index describes its documents.
 *
 * How the values relate to the document is {@code mode}. A {@code flattened}
 * object folds its fields into the document: outside the object they are
 * ordinary fields named by the dotted path through it, such as
 * {@code dimensions.width}, filtered, matched and counted with no extra
 * ceremony. A {@code nested} object keeps every value as one unit instead, so
 * a search can ask that several conditions hold inside the same value -
 * through the {@code nested} clause of the search API, which is also the only
 * place a clause may name a field inside it. Declare the field
 * {@code multiple} to hold a list of values:
 *
 * <pre>
 * {
 *   "type": "object",
 *   "multiple": true,
 *   "mode": "nested",
 *   "fields": {
 *     "color": { "type": "string", "filter": {} },
 *     "price": { "type": "double", "filter": {} }
 *   }
 * }
 * </pre>
 *
 * The mode is required exactly when the field is {@code multiple}, where the
 * two answer searches differently: flattened, {@code color = red} and
 * {@code price < 10} match a document where one value is red and another is
 * cheap, while nested asks both of the same value. A field holding a single
 * value is one unit either way and is always flattened, so {@code mode} is
 * refused on it.
 *
 * The fields inside can filter, match, complete, facet, validate and be
 * required or multiple; sorting works when values are single units - a
 * flattened single object, or through the values of a nested one. Refused are
 * the usages that only mean something for a document of the index - being its
 * primary key, being highlighted - as are locale variants, stored values and
 * objects inside objects. Values come back in results through the kept copy
 * of the document, so an index that keeps no copy does not return them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObjectFieldDefinition(
	Boolean primaryKey,
	Boolean required,
	Boolean multiple,
	Boolean stored,
	Locales locales,
	Filter filter,
	Sort sort,
	Facet facet,

	/**
	 * How the values relate to the document. Required when the field is
	 * {@code multiple}, refused when it is not.
	 */
	Mode mode,

	/**
	 * The fields a value holds, keyed by their name inside the value.
	 */
	Map<String, FieldDefinition> fields
) implements FieldDefinition {
	/**
	 * How the values of an object field relate to the document that holds
	 * them.
	 */
	public enum Mode {
		/**
		 * Every value is one unit, so a search can ask that several
		 * conditions hold inside the same value.
		 */
		@JsonProperty("nested")
		NESTED,

		/**
		 * The fields of every value belong to the document itself, matched
		 * independently of which value they sit in.
		 */
		@JsonProperty("flattened")
		FLATTENED
	}
}
