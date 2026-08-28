package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(description = """
	One part of the documents an index holds. A single request reads from a \
	point-in-time snapshot and sees committed data only, so uncommitted writes \
	are not visible. See [Reading \
	documents](https://levelfourab.github.io/exofind/reference/documents-api/#reading-documents).""")
public record ScanResponse(
	/*
	 * Typed as an array of free-form objects rather than by the engine's
	 * Document, which is a list of named values on the inside and is written
	 * out by DocumentSerializer as an object keyed by field name. Left to the
	 * scanner the document would describe the inside rather than the wire.
	 */
	@Schema(
		type = SchemaType.ARRAY,
		implementation = Object.class,
		description = """
			The documents, each keyed by field name and formatted as \
			originally indexed, in primary key order. Whole-number keys are \
			ordered numerically with negative numbers first, and text keys in \
			UTF-8 byte order."""
	)
	@JsonSerialize(contentUsing = DocumentSerializer.class)
	List<Document> documents,

	@Schema(
		description = """
			Primary key to pass as `after` on the next request. Present only \
			when the response returns as many documents as `limit` asked for. \
			A part that fills up exactly at the last document of the index \
			still carries one, and the request that follows it answers with an \
			empty `documents` array and no `next`.""",
		examples = "2"
	)
	String next
) {
}
