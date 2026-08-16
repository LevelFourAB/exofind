package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;
import java.util.Map;

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
public record DocumentsRequest(
	List<Map<String, Object>> documents
) {
}
