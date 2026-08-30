package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import se.l4.exofind.engine.api.v1alpha1.search.model.DocumentSerializer;
import se.l4.exofind.engine.index.Document;

/**
 * Documents read from an index in primary key order.
 *
 * @param documents
 *   the documents in primary key order, formatted as originally indexed
 * @param next
 *   primary key to pass as the after parameter on the next request. Present
 *   when the response returns as many documents as requested by the limit, and
 *   omitted at the end of the index. If a batch ends on the last document, this
 *   key is returned and the next request returns an empty list without a
 *   continuation key
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		A batch of documents read from an index. A single request reads from a \
		point-in-time snapshot and sees committed data only, so uncommitted writes \
		are not visible. For more information, see [Reading \
		documents](https://exofind.dev/reference/documents-api/#reading-documents).""",
	examples = ScanResponse.EXAMPLE
)
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
			Documents in primary key order, formatted as originally indexed. \
			Whole-number keys return in numeric order, with negative numbers \
			first. Text keys return in UTF-8 byte order."""
	)
	@JsonSerialize(contentUsing = DocumentSerializer.class)
	List<Document> documents,

	@Schema(
		description = """
			Primary key to pass as the `after` parameter on the next request. \
			Present only when the response returns as many documents as \
			requested by `limit`. If a batch ends exactly on the last document \
			of the index, `next` is returned and the subsequent request \
			returns an empty `documents` array without a `next` field.""",
		examples = "2"
	)
	String next
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{
		  "documents": [
		    { "id": "1", "name": { "sv": "blåbärssylt" }, "energy": 234 },
		    { "id": "2", "name": { "sv": "hallonsylt" }, "energy": 241 }
		  ],
		  "next": "2"
		}""";
}
