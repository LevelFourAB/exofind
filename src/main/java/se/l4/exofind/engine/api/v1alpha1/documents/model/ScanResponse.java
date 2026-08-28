package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import se.l4.exofind.engine.api.v1alpha1.search.model.DocumentSerializer;
import se.l4.exofind.engine.index.Document;

/**
 * One part of the documents an index holds, read back in the order of their
 * primary keys.
 *
 * @param documents
 *   the documents, as they were given, in key order
 * @param next
 *   the key to carry on after, present whenever the request read as many
 *   documents as it asked for and left out at the end of the index. A part
 *   that filled up exactly at the last document carries one, and the request
 *   that follows it answers with nothing
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScanResponse(
	@JsonSerialize(contentUsing = DocumentSerializer.class)
	List<Document> documents,

	String next
) {
}
