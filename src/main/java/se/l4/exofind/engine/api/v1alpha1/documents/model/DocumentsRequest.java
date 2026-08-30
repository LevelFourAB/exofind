package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Documents to index into an index.
 *
 * <p>Each document is formatted as a JSON object keyed by field name, matching
 * the format returned in search hits so that search results can be re-indexed
 * directly:
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
 *   the documents to index, each replacing any existing document under its
 *   primary key
 */
@Schema(description = """
	Documents to index. A document specifies its own primary key. Indexing a \
	document with an existing key replaces the document under that key; if an \
	index definition does not declare a primary key, each request adds a new \
	document. See [How a document is \
	shaped](https://exofind.dev/reference/documents-api/#how-a-document-is-shaped).""")
public record DocumentsRequest(
	@Schema(
		description = """
			The documents, keyed by field name. A field declared `multiple` is \
			an array, a locale-specific field an object keyed by locale tag, a \
			geo point an object with `lat` and `lon` fields, a vector an array \
			of numbers, an object field a nested JSON object, and a timestamp \
			an ISO 8601 string. A field set to `null` is treated as omitted.""",
		required = true
	)
	List<Map<String, Object>> documents
) {
}
