package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Changes to documents already in an index, as they are received over the API.
 *
 * Each one carries the primary key of the document it changes and the fields to
 * change, which are the fields it replaces - a field written as {@code null} is
 * one it empties, and a field it leaves out keeps whatever the document holds:
 *
 * <pre>
 * {
 *   "documents": [
 *     { "id": "1", "price": 34.50, "inStock": true },
 *     { "id": "2", "price": 12.00, "discount": null }
 *   ]
 * }
 * </pre>
 *
 * @param documents
 *   the changes, applied in the order they are given
 */
@Schema(description = """
	Field-level changes to documents already in an index. The updated document \
	is validated as a whole, so a change that fails validation is rejected and \
	leaves the document unchanged. See [Update \
	behavior](https://exofind.dev/reference/documents-api/#update-behavior).""")
public record UpdateRequest(
	@Schema(
		description = """
			The changes, each carrying the primary key and the fields to \
			change, applied in the order given. A field with a value replaces \
			the current value, a field set to `null` clears it, and an omitted \
			field is left as it is. Locale-specific fields and object fields \
			are replaced entirely rather than merged.""",
		required = true
	)
	List<Map<String, Object>> documents
) {
}
