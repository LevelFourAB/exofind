package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Definition of a field holding an instant in time formatted as an ISO 8601
 * date-time string with a timezone offset (such as {@code Z} or
 * {@code +02:00}).
 *
 * <p>Timestamps are stored and compared at millisecond precision. Values
 * representing the same instant (such as {@code 2024-05-01T12:00:00+02:00} and
 * {@code 2024-05-01T10:00:00Z}) are identical for filtering and sorting. Search
 * results return the original string format provided during ingestion.
 * Documents containing timestamps without timezone offsets are rejected.
 *
 * <pre>
 * {
 *   "type": "timestamp",
 *   "filter": {},
 *   "sort": {}
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Represents an instant in time formatted as an ISO 8601 date-time string \
	with a timezone offset (for example, `Z` or `+02:00`). Timestamps are \
	stored and compared at millisecond precision. Values representing the same \
	instant are identical for filtering and sorting; search results return the \
	original string format provided during ingestion. Documents containing \
	timestamps without timezone offsets are rejected.""")
public record TimestampFieldDefinition(
	/**
	 * What the field is for, expanded into the usages that serve it before the
	 * definition is stored. Accepts {@code timestamp}.
	 */
	@Schema(description = FieldDefinition.ROLE_DESCRIPTION)
	Role role,

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

	@Schema(description = FieldDefinition.FILTER_DESCRIPTION)
	Filter filter,

	@Schema(description = FieldDefinition.SORT_DESCRIPTION)
	Sort sort,

	@Schema(description = FieldDefinition.FACET_DESCRIPTION)
	Facet facet
) implements FieldDefinition {
}
