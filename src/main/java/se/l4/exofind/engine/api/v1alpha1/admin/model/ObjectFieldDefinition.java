package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Definition of a field whose values are documents of their own, described by
 * {@code fields} the same way the index describes its documents.
 *
 * Every value is matched as one unit: a search can ask that several conditions
 * hold inside the same value, through the {@code nested} clause of the search
 * API. Declare the field {@code multiple} to hold a list of values:
 *
 * <pre>
 * {
 *   "type": "object",
 *   "multiple": true,
 *   "fields": {
 *     "color": { "type": "string", "filter": {} },
 *     "price": { "type": "double", "filter": {} }
 *   }
 * }
 * </pre>
 *
 * Outside the object its fields go by the dotted path through it, such as
 * {@code variants.price}. The fields inside can filter, validate and be
 * required or multiple; the usages that rank, order or count the documents of
 * the index - matching, sorting, faceting - are refused, as are locale
 * variants, stored values and objects inside objects. Values come back in
 * results through the kept copy of the document, so an index that keeps no
 * copy does not return them.
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
	 * The fields a value holds, keyed by their name inside the value.
	 */
	Map<String, FieldDefinition> fields
) implements FieldDefinition {
}
