package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Changes to documents already in an index, as they are received over the API.
 *
 * Each one carries the primary key of the document it changes and the places to
 * change, keyed by the path naming each - a path written as {@code null} is one
 * it empties, and a place it leaves out keeps whatever the document holds:
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
 * <p>What a path may name is {@code DocumentPath}, and what one means is
 * {@code DocumentPatch}. One change of this shape is also what
 * {@code PATCH /v1alpha1/indexes/{name}/documents/{key}} takes on its own,
 * with the primary key given by the path of the request.
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
			The changes, each carrying the primary key and the places to \
			change, applied in the order given. Every other key is a path: a \
			path with a value replaces what it names, a path set to `null` \
			empties it, and a place no path names is left as it is. How \
			deeply a path reaches decides how much it replaces, so \
			`variants` replaces every value of the field while \
			`variants[sku=V-2].price` replaces one field inside one of \
			them.""",
		required = true
	)
	List<Map<String, Object>> documents
) {
}
