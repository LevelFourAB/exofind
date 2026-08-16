package se.l4.exofind.engine.api.v1alpha1.admin.model;

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
public record TimestampFieldDefinition(
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
