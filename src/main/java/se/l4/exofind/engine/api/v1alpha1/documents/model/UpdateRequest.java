package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;
import java.util.Map;

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
public record UpdateRequest(
	List<Map<String, Object>> documents
) {
}
