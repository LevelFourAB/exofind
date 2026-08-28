package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Definition of a field holding a point in time.
 *
 * A value is an ISO 8601 date and time with an offset - {@code Z} or one like
 * {@code +02:00} - and is filtered and ordered as the instant it names, at
 * millisecond precision. The offset only says where the clock was read, so
 * {@code 2024-05-01T12:00:00+02:00} and {@code 2024-05-01T10:00:00Z} are the
 * same value; what was given is what results return. A value without an
 * offset is refused, as it names no instant at all.
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
	An instant in time, written as an ISO 8601 date-time with a timezone \
	offset such as `Z` or `+02:00`. Stored and compared at millisecond \
	precision, so values naming the same instant are identical for filtering \
	and sorting; results return the string as it was indexed. A value without \
	an offset is rejected, as it names no instant at all.""")
public record TimestampFieldDefinition(
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
