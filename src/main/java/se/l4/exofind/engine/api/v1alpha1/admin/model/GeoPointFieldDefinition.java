package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(description = """
	A geographic location, given as WGS 84 `lat` and `lon`. A place is searched \
	by nearness rather than by value: `filter` enables the `distance` matcher, \
	and `sort` enables ordering by distance from an origin, nearest first.""")
public record GeoPointFieldDefinition(
	@Schema(description = FieldDefinition.PRIMARY_KEY_DESCRIPTION, defaultValue = "false")
	Boolean primaryKey,

	@Schema(description = FieldDefinition.REQUIRED_DESCRIPTION, defaultValue = "false")
	Boolean required,

	@Schema(description = FieldDefinition.MULTIPLE_DESCRIPTION, defaultValue = "false")
	Boolean multiple,

	@Schema(description = FieldDefinition.STORED_DESCRIPTION, defaultValue = "false")
	Boolean stored,

	@Schema(description = FieldDefinition.LOCALES_DESCRIPTION)
	Locales locales,

	@Schema(description = """
		Enables distance-based filtering with the `distance` matcher.""")
	Filter filter,

	@Schema(description = """
		Enables ordering documents by distance from a target origin, nearest \
		first.""")
	Sort sort,

	@Schema(description = FieldDefinition.FACET_DESCRIPTION)
	Facet facet
) implements FieldDefinition {
}
