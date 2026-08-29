package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Documents to put into an index, as they are received over the API.
 *
 * Each document is keyed by field name and shaped the way a search reads one
 * back, so a hit can be sent straight back to be indexed again:
 *
 * <pre>
 * {
 *   "documents": [
 *     {
 *       "id": "1",
 *       "name": { "sv": "blåbärssylt", "en": "blueberry jam" },
 *       "tags": ["sylt", "bär"],
 *       "energy": 234
 *     }
 *   ]
 * }
 * </pre>
 *
 * @param documents
 *   the documents, each of which replaces the one already indexed under its
 *   primary key
 */
@Schema(description = """
	Documents to index. Each document carries its own primary key, so indexing \
	one under an existing key replaces it; on an index that declares no \
	primary key, every request adds a new document. See [How a document is \
	shaped](https://exofind.dev/reference/documents-api/#how-a-document-is-shaped).""")
public record DocumentsRequest(
	@Schema(
		description = """
			The documents, keyed by field name. A field declared `multiple` is \
			an array, a locale-specific field an object keyed by locale tag, a \
			geo point an object with `lat` and `lon`, a vector an array of \
			numbers, an object field a nested JSON object, and a timestamp an \
			ISO 8601 string. A field set to `null` is treated as omitted.""",
		required = true
	)
	List<Map<String, Object>> documents
) {
}
