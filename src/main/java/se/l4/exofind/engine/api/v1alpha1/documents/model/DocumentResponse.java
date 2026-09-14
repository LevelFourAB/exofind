package se.l4.exofind.engine.api.v1alpha1.documents.model;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import se.l4.exofind.engine.api.v1alpha1.search.model.DocumentSerializer;
import se.l4.exofind.engine.index.Document;

/**
 * One document read from an index by its primary key.
 *
 * @param document
 *   the document, formatted as originally indexed
 */
@Schema(
	description = """
		One document read from an index by its primary key. The read is \
		answered from a point-in-time snapshot and sees committed data only, \
		so an uncommitted write is not visible. For more information, see \
		[Reading one document](https://exofind.dev/reference/documents-api/#reading-one-document).""",
	examples = DocumentResponse.EXAMPLE
)
public record DocumentResponse(
	/*
	 * Typed as a free-form object rather than by the engine's Document, which
	 * is a list of named values on the inside and is written out by
	 * DocumentSerializer as an object keyed by field name. Left to the scanner
	 * the document would describe the inside instead of the wire.
	 */
	@Schema(
		type = SchemaType.OBJECT,
		implementation = Object.class,
		description = """
			The document, formatted as originally indexed. Send it back to \
			`POST /v1alpha1/indexes/{name}/documents` to index it again."""
	)
	@JsonSerialize(using = DocumentSerializer.class)
	Document document,

	/**
	 * The state the document was read from, as a freshness token.
	 */
	@Schema(
		description = """
			A freshness token for the state the document was read from. Pass \
			it in the `X-Exofind-Freshness` header of the next request, and \
			that request is answered from this state or a later one whichever \
			node it lands on. Opaque; pass it back unchanged. See \
			[Freshness](https://exofind.dev/reference/search-api/#freshness).""",
		examples = "AQoIcHJvZHVjdHMSATIYBw"
	)
	String freshness
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{
		  "document": { "id": "1", "name": { "sv": "blåbärssylt" }, "energy": 234 },
		  "freshness": "AQoIcHJvZHVjdHMSATIYBw"
		}""";
}
