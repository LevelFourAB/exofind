package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Updates specific fields of existing documents in the index.
 *
 * <p>Each change object must include the primary key of the document to update.
 * Every other key is a path naming a location in the document. A path with a
 * value replaces what the path names, a path set to {@code null} empties what
 * the path names, and an omitted path leaves the existing value unchanged:
 *
 * <pre>
 * {
 *   "documents": [
 *     { "id": "1", "price": 34.50, "inStock": true },
 *     { "id": "2", "price": 12.00, "variants[sku=V-2].price": 29.0 }
 *   ]
 * }
 * </pre>
 *
 * <p>Path syntax is defined by {@code DocumentPath}, and update semantics are
 * defined by {@code DocumentPatch}. A single change object with this structure
 * is also accepted by {@code PATCH /v1alpha1/indexes/{name}/documents/{key}},
 * with the primary key specified in the URL path.
 *
 * @param documents
 *   the changes to apply, in the order provided
 */
@Schema(description = """
	Field-level changes to documents already in an index. Every change to one \
	document is applied and validated as a whole. If validation fails, the \
	request is rejected and the document remains unchanged. For more \
	information, see [Update \
	behavior](https://exofind.dev/reference/documents-api/#update-behavior).""")
public record UpdateRequest(
	@Schema(
		description = """
			The changes, each carrying the primary key and the locations to \
			change, applied in the order provided. Every other key is a path: \
			a path with a value replaces what it names, a path set to `null` \
			empties what it names, and an omitted path leaves the existing \
			value unchanged. The path replaces exactly what it names: \
			`variants` replaces every value of the field, while \
			`variants[sku=V-2].price` replaces one field inside those values.""",
		required = true
	)
	List<Map<String, Object>> documents
) {
}
