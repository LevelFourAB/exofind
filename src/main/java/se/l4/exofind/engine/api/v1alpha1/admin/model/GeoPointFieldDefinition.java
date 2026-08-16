package se.l4.exofind.engine.api.v1alpha1.admin.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Definition of a field holding a point on the earth, as a WGS 84 latitude
 * and longitude.
 *
 * A place is searched by nearness rather than by value: {@code filter}
 * enables the {@code distance} matcher, and {@code sort} enables ordering by
 * distance from an origin, nearest first.
 *
 * <pre>
 * {
 *   "type": "geo_point",
 *   "filter": {},
 *   "sort": {}
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeoPointFieldDefinition(
	Boolean primaryKey,
	Boolean required,
	Boolean multiple,
	Boolean stored,
	Locales locales,
	Filter filter,
	Sort sort,
	Facet facet
) implements FieldDefinition {
}
