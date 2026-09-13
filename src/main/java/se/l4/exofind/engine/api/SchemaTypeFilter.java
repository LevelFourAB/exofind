package se.l4.exofind.engine.api;

import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.media.Schema;

/**
 * Drops the JSON type a schema states beside a {@code $ref} or a {@code oneOf}.
 *
 * <p>The build reads the type off the Java type of a property, so a property
 * whose Java type is an interface is written as {@code type: object} next to
 * the reference or the branches. Under OpenAPI 3.1 every keyword of a schema
 * applies, so that {@code type} narrows what the reference or the branches
 * accept. A union with a string branch, such as the {@code interpret} of a text
 * clause, then rejects the string form, and a generated client carries only the
 * object form.
 *
 * <p>The reference and the branches state their own types, so the one written
 * beside them adds nothing to the schemas where it is correct.
 */
public class SchemaTypeFilter implements OASFilter {
	@Override
	public Schema filterSchema(Schema schema) {
		var oneOf = schema.getOneOf();

		if(schema.getRef() != null || (oneOf != null && !oneOf.isEmpty())) {
			schema.setType(null);
		}

		return schema;
	}
}
