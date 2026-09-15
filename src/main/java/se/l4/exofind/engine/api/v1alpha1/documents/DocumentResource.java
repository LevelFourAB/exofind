package se.l4.exofind.engine.api.v1alpha1.documents;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.api.ExofindApi;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.EngineExceptionMapper;
import se.l4.exofind.engine.api.errors.ErrorResponse;
import se.l4.exofind.engine.api.errors.RequestBodyTooLargeException;
import se.l4.exofind.engine.api.errors.RequestBodyUnreadableException;
import se.l4.exofind.engine.api.errors.ReturnsError;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.FreshnessTokens;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DeleteRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DeleteResponse;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentFailure;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentResponse;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsResponse;
import se.l4.exofind.engine.api.v1alpha1.documents.model.ScanResponse;
import se.l4.exofind.engine.api.v1alpha1.documents.model.UpdateRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.UpdateResponse;
import se.l4.exofind.engine.api.v1alpha1.search.SearchRequestMapper;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.Location;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.freshness.Freshness;
import se.l4.exofind.engine.freshness.FreshnessWaiter;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.DocumentPatch;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexDocumentNotFoundException;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.reindex.ReindexJobs;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * Reads, creates, updates, and deletes documents in an index.
 *
 * <p>A document specifies its own primary key. Indexing a document operates as
 * an assertion of desired state: repeating the request replaces any existing
 * document under that key. Removing a document is also a statement of desired
 * state, so requesting the deletion of an unindexed key produces a success
 * response.
 *
 * <p>Requests that describe modifications rather than desired state require
 * existing resources. Updating specific fields describes changes to an existing
 * document and is refused if the document does not exist. You can update a
 * single document by key in the URL path, or update multiple documents in a
 * batch by including the key in each change object.
 *
 * <p>Changes become searchable and replicate to remote storage after the index
 * commits. The writer commits automatically based on indexed document volume or
 * elapsed time. To commit changes immediately, send a request to {@code POST
 * /v1alpha1/admin/indexes/{name}/actions/commit}. Loading a dataset involves
 * sending multiple write requests followed by a single commit, rather than
 * committing per batch.
 *
 * <p>Documents in a batch are processed in the order sent. The first refused
 * document halts processing and fails the request; documents processed before
 * the failure remain in the index and commit with the rest. Every error says
 * which entry of the batch it is about and how many entries the index took
 * before it, so the request can be resent from where it stopped. A request sent
 * with {@code ?onError=skip} carries on past a refused document instead and
 * reports the refused ones in its response.
 *
 * <p>Reading documents returns them in primary key order, formatted as
 * originally indexed, matching the format accepted for indexing. This allows
 * you to populate a new generation from the one it replaces or create backups
 * without the originating source system. Responses are always bounded, so
 * reading an entire index requires a sequence of requests, each resuming after
 * the primary key returned by the previous request. A single document is read
 * by its key in the URL path instead. A read of a key nothing is indexed
 * under is refused with a 404, unlike removal, which takes any key.
 *
 * <p>Write requests run on the index writer node; a request received by another
 * node is forwarded automatically (see {@code IndexerForwardFilter}). Read
 * requests are served directly by whichever node receives them, using data that
 * the node has pulled from storage.
 */
@Tag(
	name = "Documents",
	description = "Reads, creates, updates, and deletes documents in an index.",
	externalDocs = @ExternalDocumentation(
		description = "Documents API reference",
		url = "https://exofind.dev/reference/documents-api/"
	)
)
@SecurityRequirement(name = ExofindApi.API_KEY)
@Path("/v1alpha1/indexes/{name}/documents")
@Produces(MediaType.APPLICATION_JSON)
public class DocumentResource {
	/**
	 * Media type of newline delimited JSON, one document per line - what a
	 * dataset too large to hold in memory is sent as. Declared for the API as a
	 * whole, because how large a body in it may be is decided there.
	 */
	public static final String NDJSON = ExofindApi.NDJSON;

	/**
	 * How many documents a request that reads them back answers with when it
	 * does not say.
	 */
	public static final int SCAN_DEFAULT_LIMIT = 100;

	/**
	 * The most documents one request that reads them back can answer with.
	 * Reading holds the index against a pull while it runs, so what a caller
	 * can ask for at once is bounded whether or not the caller bounds it.
	 */
	public static final int SCAN_MAX_LIMIT = 10_000;

	/**
	 * What is written between two documents of a newline delimited answer,
	 * which is nothing - the newline after each is the separator.
	 */
	private static final SerializedString NOTHING = new SerializedString("");

	private static final ErrorType MISSING_BODY = ErrorType.withCode("request:body_required")
		.withStatus(400)
		.withMessage("Documents are required");

	private static final ErrorType NOT_AN_OBJECT = ErrorType
		.withCode("document:not_an_object")
		.withStatus(400)
		.withMessage("A document has to be an object, keyed by field name");

	private static final ErrorType MALFORMED = ErrorType.withCode("document:malformed")
		.withStatus(400)
		.withArguments("reason")
		.withMessage("The document could not be read as JSON: {{reason}}");

	private static final ErrorType IO_ERROR = ErrorType.withCode("storage:io_error")
		.withStatus(409)
		.withArguments("index")
		.withMessage("The index `{{index}}` could not be updated on disk");

	private static final ErrorType READ_ERROR = ErrorType.withCode("storage:io_error")
		.withStatus(409)
		.withArguments("index")
		.withMessage("The index `{{index}}` could not be read from disk");

	private static final ErrorType SCAN_LIMIT_INVALID =
		ErrorType.withCode("request:limit_out_of_range")
			.withStatus(400)
			.withArguments("value", "max")
			.withMessage(
				"A limit is a whole number from 1 to {{max}}, which `{{value}}` is not"
			);

	private static final ErrorType UPDATE_MISSING_UNKNOWN =
		ErrorType.withCode("document:patch:missing_invalid")
			.withStatus(400)
			.withArguments("value")
			.withMessage(
				"A key nothing is indexed under is handled by `fail` or `skip`, not by `{{value}}`"
			);

	private static final ErrorType ON_ERROR_UNKNOWN =
		ErrorType.withCode("document:on_error_invalid")
			.withStatus(400)
			.withArguments("value")
			.withMessage(
				"A document the index refuses is handled by `fail` or `skip`, not by `{{value}}`"
			);

	private static final ErrorType UPDATE_NOT_FOUND =
		ErrorType.withCode("document:not_found")
			.withStatus(404)
			.withArguments("key")
			.withMessage(
				"Nothing is indexed under the key `{{key}}`, so there is nothing to change"
			);

	private static final ErrorType CHANGE_MISSING_BODY = ErrorType
		.withCode("request:body_required")
		.withStatus(400)
		.withMessage("A change is required");

	private static final ErrorType DOCUMENT_MISSING_BODY = ErrorType
		.withCode("request:body_required")
		.withStatus(400)
		.withMessage("A document is required");

	private static final ErrorType KEY_CONFLICTING =
		ErrorType.withCode("document:key_conflicting")
			.withStatus(400)
			.withArguments("key", "name")
			.withMessage(
				"The request is for the document `{{key}}`, so `{{name}}` in the body cannot give another key"
			);

	private static final ErrorType DELETE_TARGET_REQUIRED =
		ErrorType.withCode("document:delete:target_required")
			.withStatus(400)
			.withMessage(
				"Documents are removed by `keys`, by `query` or by `all`, and one of them is required"
			);

	private static final ErrorType DELETE_TARGET_CONFLICTING =
		ErrorType.withCode("document:delete:target_conflicting")
			.withStatus(400)
			.withMessage(
				"Documents are removed by `keys`, by `query` or by `all`, and only one of them can be used"
			);

	private static final ErrorType DELETE_QUERY_EMPTY =
		ErrorType.withCode("document:delete:query_empty")
			.withStatus(400)
			.withMessage(
				"A `query` requires at least one clause; use `all` to remove every document"
			);

	private static final ErrorType DELETE_LOCALE_WITHOUT_QUERY =
		ErrorType.withCode("document:delete:locale_without_query")
			.withStatus(400)
			.withMessage("A locale says how to match a `query`, which this request does not have");

	private static final ErrorType DELETE_KEY_REQUIRED =
		ErrorType.withCode("document:key_required")
			.withStatus(400)
			.withMessage("A key is required");

	/**
	 * The headers of the request being served, for the freshness token a
	 * read carries in {@link FreshnessTokens#HEADER}. {@code null} on a
	 * resource built outside a request, the way a test builds one, which is
	 * a request with no headers.
	 */
	@Context
	HttpHeaders headers;

	private final Indexes indexes;
	private final ObjectMapper mapper;
	private final ReindexJobs reindexJobs;
	private final FreshnessWaiter freshnessWaiter;
	private final RequestMetrics metrics;

	/**
	 * Write documents that report nothing, for a test that is not measuring.
	 */
	public DocumentResource(
		Indexes indexes,
		ObjectMapper mapper,
		ReindexJobs reindexJobs,
		FreshnessWaiter freshnessWaiter
	) {
		this(indexes, mapper, reindexJobs, freshnessWaiter, RequestMetrics.none());
	}

	@Inject
	public DocumentResource(
		Indexes indexes,
		ObjectMapper mapper,
		ReindexJobs reindexJobs,
		FreshnessWaiter freshnessWaiter,
		RequestMetrics metrics
	) {
		this.indexes = indexes;
		this.mapper = mapper;
		this.reindexJobs = reindexJobs;
		this.freshnessWaiter = freshnessWaiter;
		this.metrics = metrics;
	}

	/**
	 * Refuse a request that cannot be served before its body is read: a name
	 * nothing here holds, and a generation a reindex is filling - what lands
	 * in that has to come from the job alone, or the job's replay would
	 * overwrite it.
	 */
	private void checkWritable(String name) {
		reindexJobs.checkTargetWritable(name);
		indexes.getOrThrow(name);
	}

	/**
	 * Make one change to the generation a name answers from.
	 *
	 * <p>Each change resolves the name for itself rather than the request
	 * resolving it once. A request that indexes a stream of documents is open
	 * for as long as the stream runs, and the generation the name answers from
	 * can be promoted while it is - see {@link Indexes#write}.
	 */
	private <T> T write(String name, Landed landed, Indexes.WriteAction<T> action) {
		return indexes.write(name, index -> {
			try(var change = index.beginChange()) {
				try {
					return action.apply(index);
				} finally {
					/*
					 * Recorded whatever the change did. A change the index
					 * refused changed nothing, and the commit it names is then
					 * the one already open - which the token still has to
					 * name, as the answer says which state the request left.
					 */
					landed.record(index, change.landsIn());
				}
			}
		});
	}

	/**
	 * The state the writes of one request land in, collected one write at a
	 * time, for the request to answer with as a freshness token.
	 *
	 * <p>A request that writes a stream of documents resolves the name once
	 * per document, so its writes can land in two generations when the name
	 * is promoted while the stream runs. The token carries the last generation
	 * written and the highest commit sequence the writes landed in there. The
	 * earlier writes went to a generation the name no longer answers from,
	 * which no token could make a search see.
	 */
	private final class Landed {
		private IndexName generation;
		private long commit;

		void record(Index index, long landsIn) {
			var name = IndexName.parse(index.getId());
			if(!name.equals(generation)) {
				generation = name;
				commit = landsIn;
			} else {
				commit = Math.max(commit, landsIn);
			}
		}

		/**
		 * The token the request answers with. A request that wrote nothing
		 * gives the state the generation is in now.
		 */
		String token(String name) {
			if(generation == null) {
				var index = indexes.getOrThrow(name);
				record(index, index.visibleCommit());
			}

			return FreshnessTokens.encode(Freshness.ofCommit(generation, commit));
		}
	}

	/**
	 * Time a change to an index and report it under an operation name.
	 *
	 * <p>The measurement starts once the index has been found, so it covers
	 * reading the request and changing the index. A change that throws is
	 * reported as failed and as covering no documents, and its exception is
	 * passed on.
	 *
	 * <p>The operation names are persistent identifiers, carried as a tag on
	 * the {@code exofind.write} meters: {@code add}, {@code update},
	 * {@code delete} and {@code delete_by_query}. Both forms of a request, the
	 * JSON one and the newline delimited one, report the same name.
	 *
	 * @param change
	 *   the change to make, answering with how many documents it covered
	 */
	private int measure(String operation, IntSupplier change) {
		var started = System.nanoTime();

		int documents;
		try {
			documents = change.getAsInt();
		} catch(RuntimeException e) {
			metrics.recordWrite(operation, System.nanoTime() - started, 0, false);
			throw e;
		}

		metrics.recordWrite(operation, System.nanoTime() - started, documents, true);
		return documents;
	}

	/**
	 * Indexes documents into an index.
	 *
	 * @param name
	 * @param onError
	 *   behavior when the index refuses a document: {@code fail} (the default)
	 *   fails the request, and {@code skip} indexes the remaining documents and
	 *   returns the refused ones
	 * @param body
	 * @return
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.DOCUMENTS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "indexDocuments",
		summary = "Index documents",
		description = """
			Indexes one or more documents into the specified index. Each \
			document specifies its own primary key. Indexing a document with \
			an existing key replaces the document under that key. Documents in \
			a batch are processed in the order sent. The first refused document \
			halts processing and fails the request; documents processed before \
			the failure remain in the index. Every error names the document it \
			is about: `position` counts the documents of the request from zero, \
			`processed` says how many the index took before the failure, and a \
			newline-delimited body also carries `line`. Send `?onError=skip` to \
			index the rest of the batch instead and read the refused documents \
			from `failed`.

			Format the request body as `application/json` with a `documents` \
			array, or `application/x-ndjson` with one document object per line \
			and no outer wrapper. Newline-delimited documents are indexed as \
			they are read, so the node holds a buffer rather than the whole \
			body and a single request can carry a whole dataset. A JSON body is \
			held in memory and is bounded by a smaller size; both sizes are set \
			by the deployment, and a body past either is refused with `413`.

			Changes become searchable and replicate to remote storage after \
			the index commits. The writer commits automatically based on \
			indexed document volume or elapsed time. To commit changes \
			immediately, call `POST \
			/v1alpha1/admin/indexes/{name}/actions/commit`.

			The operation runs on the index writer node. A write request \
			received by another node is forwarded automatically."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The documents were indexed successfully.",
		content = @Content(
			schema = @Schema(implementation = DocumentsResponse.class),
			examples = @ExampleObject(name = "indexed", value = DocumentsResponse.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			A document was rejected by validation, a line could not be read as \
			JSON, or the request body could not be parsed. The `path` of each \
			error identifies the document and field location as the body \
			carries it, such as `documents[1].nonexistent` for a `documents` \
			array and `[1].nonexistent` for a newline-delimited body. The \
			`arguments` of each error carry the same place as numbers to resume \
			from: `position` for the document, `processed` for how many \
			documents the index took before it, and `line` for the line of a \
			newline-delimited body.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "413",
		description = """
			The request body is larger than the node accepts. The node states \
			one size for a body it holds in memory and another for a \
			newline-delimited body it reads as it arrives; the `limit` argument \
			carries the one this request passed, in bytes. A newline-delimited \
			body also carries `processed`, how many documents the index took \
			before the body was cut off, so the rest can be sent again.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index with the specified name exists on this node, or the API \
			key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "The index cannot be written to right now.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The node holding the index did not answer.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = "The index is not open on the node right now.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "document:malformed",
		status = 400,
		when = "A line of the body could not be read as JSON."
	)
	@ReturnsError(
		value = "request:body_required",
		status = 400,
		when = "The request carries no documents."
	)
	@ReturnsError(
		value = "document:not_an_object",
		status = 400,
		when = "A document is not an object keyed by field name."
	)
	@ReturnsError(
		value = "document:on_error_invalid",
		status = 400,
		when = "`onError` is neither `fail` nor `skip`."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "The node holds no such index, or the key has no permission on it."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:out_of_date",
		status = 409,
		when = "The index is synchronizing. Send the request again."
	)
	@ReturnsError(
		value = "index:readonly",
		status = 409,
		when = "The node lost the writer role while the request ran. Send the request again to reach the new writer."
	)
	@ReturnsError(
		value = "reindex:target_busy",
		status = 409,
		when = "An active reindex job holds the target generation. Wait for the job, or write to another generation."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed to free local resources. Sending it again reopens the index."
	)
	@ReturnsError(
		value = "document:field_unknown",
		status = 400,
		when = "A document gives a field the index does not have."
	)
	@ReturnsError(
		value = "document:field_inside_object",
		status = 400,
		when = "A document gives a dotted path to a field inside an object instead of the object that holds it."
	)
	@ReturnsError(
		value = "document:field_required",
		status = 400,
		when = "A document leaves out a field the definition marks as required."
	)
	@ReturnsError(
		value = "document:locale_unknown",
		status = 400,
		when = "A value carries a locale the field does not hold values in."
	)
	@ReturnsError(
		value = "document:locale_unsupported",
		status = 400,
		when = "A value carries a locale on a field that is not locale specific."
	)
	@ReturnsError(
		value = "document:object_required",
		status = 400,
		when = "A field that holds objects is given a value that is not one."
	)
	@ReturnsError(
		value = "document:object_unsupported",
		status = 400,
		when = "A field that does not hold objects is given one."
	)
	@ReturnsError(
		value = "document:multiple_unsupported",
		status = 400,
		when = "A field that holds a single value is given several."
	)
	@ReturnsError(
		value = "document:multiple_per_locale_unsupported",
		status = 400,
		when = "A field that holds a single value per locale is given several in one locale."
	)
	@ReturnsError(
		value = "document:object_key_duplicate",
		status = 400,
		when = "Two values of an object field read the same under the key that tells them apart."
	)
	@ReturnsError(
		value = "document:number:value_invalid",
		status = 400,
		when = "A number field is given a value that cannot be read as its type."
	)
	@ReturnsError(
		value = "document:number:value_out_of_range",
		status = 400,
		when = "A number field is given a value outside the bounds its definition declares."
	)
	@ReturnsError(
		value = "document:geo_point:value_invalid",
		status = 400,
		when = "A geo point field is given a value that is not a latitude and a longitude."
	)
	@ReturnsError(
		value = "document:geo_point:value_out_of_range",
		status = 400,
		when = "A geo point field is given a point that is not on the earth."
	)
	@ReturnsError(
		value = "document:timestamp:value_invalid",
		status = 400,
		when = "A timestamp field is given a value that is not an ISO 8601 date and time with an offset."
	)
	@ReturnsError(
		value = "document:vector:value_invalid",
		status = 400,
		when = "A vector field is given a value that is not an array of floats."
	)
	@ReturnsError(
		value = "document:vector:value_not_finite",
		status = 400,
		when = "A vector field is given a value that is not a finite number."
	)
	@ReturnsError(
		value = "document:vector:dimensions_mismatch",
		status = 400,
		when = "A vector field is given a vector with other dimensions than the field declares."
	)
	@ReturnsError(
		value = "document:vector:value_zero",
		status = 400,
		when = "A vector field compared by cosine is given a vector of only zeros."
	)
	@ReturnsError(
		value = "index:generation:unsettled",
		status = 400,
		when = "The generation the index serves from kept changing while the write was made. Send the request again."
	)
	public DocumentsResponse add(
		@Parameter(
			description = """
				Name of the index to write to. To write to a specific \
				generation, append `@` and the name of the generation, such as \
				`books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Behavior when the index refuses a document: `fail` (default) \
				stops at the first one and fails the request, while `skip` \
				indexes the remaining documents and returns the refused ones \
				under `failed`. A body that cannot be read as JSON fails the \
				request either way.""",
			schema = @Schema(enumeration = {"fail", "skip"}, defaultValue = "fail")
		)
		@QueryParam("onError") String onError,
		@RequestBody(content = @Content(
			schema = @Schema(implementation = DocumentsRequest.class),
			examples = @ExampleObject(
				name = "documents",
				summary = "One document with a locale-specific field",
				value = DocumentsRequest.EXAMPLE
			)
		))
		DocumentsRequest body
	) {
		if(body == null || body.documents() == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		checkWritable(name);
		var skipErrors = skipErrors(onError);
		var documents = body.documents();
		var failures = Lists.mutable.<DocumentFailure>empty();
		var landed = new Landed();

		var indexed = measure("add", () -> {
			var written = 0;

			for(var i = 0; i < documents.size(); i++) {
				var entry = BatchEntry.inDocuments(i);
				var json = documents.get(i);
				var processed = written;

				try {
					write(name, landed, index -> {
						addDocument(index, name, json, entry, processed);
						return null;
					});
				} catch(ValidationException e) {
					if(!skipErrors) {
						throw e;
					}

					failures.add(toFailure(entry, e));
					continue;
				}

				written++;
			}

			return written;
		});

		return new DocumentsResponse(indexed, failures, landed.token(name));
	}

	/**
	 * Put documents into an index, one JSON object per line. The documents are
	 * indexed as they are read, so the request costs the node a buffer rather
	 * than the whole body, and it carries as many documents as the deployment
	 * allows - by default as many as the connection can carry. See
	 * {@code RequestBodyLimits}.
	 *
	 * @param name
	 * @param onError
	 * @param body
	 * @return
	 */
	@POST
	@Consumes(NDJSON)
	@RequiresPermission(Permission.DOCUMENTS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	/*
	 * Carries no OpenAPI annotations of its own beyond the code only a streamed
	 * body answers with, which is read onto the shared operation. It shares a
	 * path and method with the endpoint above, so the two are one operation in
	 * the document - what this one contributes is the media type it consumes,
	 * which comes from @Consumes. Describing it separately here would be
	 * dropped in the merge, so what it does is said in that operation's
	 * description instead.
	 */
	@ReturnsError(
		value = "request:body_too_large",
		status = 413,
		when = "The body passed the size the node accepts for a newline-delimited request. The documents read before that are indexed; `processed` says how many, so the rest can be sent again."
	)
	@ReturnsError(
		value = "request:body_unreadable",
		status = 400,
		when = "The body stopped arriving part way through. The documents read before that are indexed; send the rest again."
	)
	public DocumentsResponse addStream(
		@PathParam("name") String name,
		@QueryParam("onError") String onError,
		InputStream body
	) {
		if(body == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		checkWritable(name);
		var skipErrors = skipErrors(onError);
		var failures = Lists.mutable.<DocumentFailure>empty();
		var landed = new Landed();

		var indexed = measure("add", () -> {
			var read = 0;
			var written = 0;

			try(var documents = mapper.readerFor(Map.class).<Map<String, Object>>readValues(body)) {
				while(hasNext(documents, read, written)) {
					var entry = BatchEntry.onLine(read, lineOf(documents));

					/*
					 * nextValue rather than next: next rewraps what it reads,
					 * turning a document that is not JSON into an exception
					 * neither catch below sees and answering with no position.
					 */
					var json = documents.nextValue();
					var processed = written;

					read++;

					try {
						write(name, landed, index -> {
							addDocument(index, name, json, entry, processed);
							return null;
						});
					} catch(ValidationException e) {
						if(!skipErrors) {
							throw e;
						}

						failures.add(toFailure(entry, e));
						continue;
					}

					written++;
				}
			} catch(RequestBodyTooLargeException e) {
				throw tooLarge(e, read, written);
			} catch(JacksonException e) {
				throw malformed(e, read, written);
			} catch(IOException e) {
				throw unreadable(e, read, written);
			}

			return written;
		});

		return new DocumentsResponse(indexed, failures, landed.token(name));
	}

	/**
	 * Updates specific fields of existing documents in the index, leaving the
	 * remaining fields unchanged.
	 *
	 * @param name
	 * @param missing
	 *   behavior when a document key does not exist: {@code fail} (the default)
	 *   fails the request, and {@code skip} updates the remaining documents and
	 *   returns the missing keys
	 * @param onError
	 *   behavior when the index refuses a change: {@code fail} (the default)
	 *   fails the request, and {@code skip} applies the remaining changes and
	 *   returns the refused ones
	 * @param body
	 * @return
	 */
	@POST
	@Path("/actions/update")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.DOCUMENTS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "updateDocuments",
		summary = "Update fields of existing documents",
		description = """
			Changes named parts of documents already in the index, leaving the \
			rest of each document unchanged. Each key in a change object is a \
			path naming a location in the document: a path with a value \
			replaces what the path names, a path set to `null` empties what it \
			names, and an omitted path leaves the existing value unchanged.

			The path replaces exactly what it names and leaves surrounding \
			content unchanged. `variants` replaces every value of the field, \
			`variants[sku=V-2]` replaces the object value whose `sku` field \
			reads as `V-2`, and `variants[sku=V-2].price` replaces one field \
			inside that value. Similarly, `title` replaces every variant and \
			`title[sv]` replaces the Swedish variant. `variants[]` adds a \
			value to the values the field holds.

			Send `application/json` with a `documents` array containing change \
			objects, or `application/x-ndjson` with one change object per line \
			and no outer wrapper.

			Unlike indexing, this endpoint describes modifications rather than \
			desired state and requires existing documents. Multiple updates to \
			the same document in a single batch apply in the order provided, \
			and the updated document is validated as a whole.

			Changes in a batch are applied in the order sent. The first refused \
			change halts processing and fails the request; changes applied \
			before the failure remain in the index. Every error names the change \
			it is about: `position` counts the changes of the request from zero, \
			`processed` says how many the index applied before the failure, and \
			a newline-delimited body also carries `line`. Send `?onError=skip` \
			to apply the rest of the batch instead and read the refused changes \
			from `failed`.

			The index has to declare a primary key and retain document source \
			copies."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The documents were updated.",
		content = @Content(
			schema = @Schema(implementation = UpdateResponse.class),
			examples = @ExampleObject(name = "updated", value = UpdateResponse.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			A change failed validation, or a path in it names something the \
			index or the document does not hold. The `arguments` of each error \
			carry where in the batch the change sat and how much of the batch \
			had landed: `position`, `processed`, and `line` for a \
			newline-delimited body.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "413",
		description = """
			The request body is larger than the node accepts. The node states \
			one size for a body it holds in memory and another for a \
			newline-delimited body it reads as it arrives; the `limit` argument \
			carries the one this request passed, in bytes. A newline-delimited \
			body also carries `processed`, how many documents the index took \
			before the body was cut off, so the rest can be sent again.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index with the specified name exists on this node, or the API \
			key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "The index cannot be written to right now.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The node holding the index did not answer.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = "The index is not open on the node right now.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "document:not_found",
		status = 400,
		when = "A document the change names is not indexed and `missing` is `fail`."
	)
	@ReturnsError(
		value = "index:no_primary_key",
		status = 400,
		when = "The index definition declares no primary key, so a document cannot be named."
	)
	@ReturnsError(
		value = "document:source_not_kept",
		status = 400,
		when = "The index does not store document copies. Send the complete document instead."
	)
	@ReturnsError(
		value = "document:patch:path_invalid",
		status = 400,
		when = "A path in the change could not be read."
	)
	@ReturnsError(
		value = "document:patch:field_unknown",
		status = 400,
		when = "A path reaches into a field the index does not have."
	)
	@ReturnsError(
		value = "document:patch:selector_unsupported",
		status = 400,
		when = "A path names one value of a field that holds neither locale variants nor objects."
	)
	@ReturnsError(
		value = "document:locale_unknown",
		status = 400,
		when = "A path names a locale the field holds no variant for."
	)
	@ReturnsError(
		value = "document:patch:add_unsupported",
		status = 400,
		when = "A change adds a value to a field that holds a single value."
	)
	@ReturnsError(
		value = "document:patch:not_an_object",
		status = 400,
		when = "A path reaches inside a field whose values are not objects."
	)
	@ReturnsError(
		value = "document:patch:selector_required",
		status = 400,
		when = "A path reaches into a list of objects without saying which value."
	)
	@ReturnsError(
		value = "document:patch:no_match",
		status = 400,
		when = "A selector names no value the document holds. A selector never creates the value it names."
	)
	@ReturnsError(
		value = "document:patch:key_unsupported",
		status = 400,
		when = "A path names one value of a list by a key that the field declares none of. Match on a field inside the value instead."
	)
	@ReturnsError(
		value = "document:patch:match_not_an_object",
		status = 400,
		when = "A path matches on a field inside a list whose values are not objects."
	)
	@ReturnsError(
		value = "document:patch:add_reaches_inside",
		status = 400,
		when = "A path reaches inside a value that the same change adds, which does not exist yet. Give the whole value instead."
	)
	@ReturnsError(
		value = "document:patch:missing_invalid",
		status = 400,
		when = "`missing` is neither `fail` nor `skip`."
	)
	@ReturnsError(
		value = "document:on_error_invalid",
		status = 400,
		when = "`onError` is neither `fail` nor `skip`."
	)
	@ReturnsError(
		value = "request:body_required",
		status = 400,
		when = "The request carries no changes."
	)
	@ReturnsError(
		value = "document:malformed",
		status = 400,
		when = "A line of the body could not be read as JSON."
	)
	@ReturnsError(
		value = "document:not_an_object",
		status = 400,
		when = "A change is not an object keyed by path."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "The node holds no such index, or the key has no permission on it."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:out_of_date",
		status = 409,
		when = "The index is synchronizing. Send the request again."
	)
	@ReturnsError(
		value = "index:readonly",
		status = 409,
		when = "The node lost the writer role while the request ran. Send the request again to reach the new writer."
	)
	@ReturnsError(
		value = "reindex:target_busy",
		status = 409,
		when = "An active reindex job holds the target generation. Wait for the job, or write to another generation."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed to free local resources. Sending it again reopens the index."
	)
	@ReturnsError(
		value = "document:field_unknown",
		status = 400,
		when = "A document gives a field the index does not have."
	)
	@ReturnsError(
		value = "document:field_inside_object",
		status = 400,
		when = "A document gives a dotted path to a field inside an object instead of the object that holds it."
	)
	@ReturnsError(
		value = "document:field_required",
		status = 400,
		when = "A document leaves out a field the definition marks as required."
	)
	@ReturnsError(
		value = "document:locale_unsupported",
		status = 400,
		when = "A value carries a locale on a field that is not locale specific."
	)
	@ReturnsError(
		value = "document:object_required",
		status = 400,
		when = "A field that holds objects is given a value that is not one."
	)
	@ReturnsError(
		value = "document:object_unsupported",
		status = 400,
		when = "A field that does not hold objects is given one."
	)
	@ReturnsError(
		value = "document:multiple_unsupported",
		status = 400,
		when = "A field that holds a single value is given several."
	)
	@ReturnsError(
		value = "document:multiple_per_locale_unsupported",
		status = 400,
		when = "A field that holds a single value per locale is given several in one locale."
	)
	@ReturnsError(
		value = "document:object_key_duplicate",
		status = 400,
		when = "Two values of an object field read the same under the key that tells them apart."
	)
	@ReturnsError(
		value = "document:number:value_invalid",
		status = 400,
		when = "A number field is given a value that cannot be read as its type."
	)
	@ReturnsError(
		value = "document:number:value_out_of_range",
		status = 400,
		when = "A number field is given a value outside the bounds its definition declares."
	)
	@ReturnsError(
		value = "document:geo_point:value_invalid",
		status = 400,
		when = "A geo point field is given a value that is not a latitude and a longitude."
	)
	@ReturnsError(
		value = "document:geo_point:value_out_of_range",
		status = 400,
		when = "A geo point field is given a point that is not on the earth."
	)
	@ReturnsError(
		value = "document:timestamp:value_invalid",
		status = 400,
		when = "A timestamp field is given a value that is not an ISO 8601 date and time with an offset."
	)
	@ReturnsError(
		value = "document:vector:value_invalid",
		status = 400,
		when = "A vector field is given a value that is not an array of floats."
	)
	@ReturnsError(
		value = "document:vector:value_not_finite",
		status = 400,
		when = "A vector field is given a value that is not a finite number."
	)
	@ReturnsError(
		value = "document:vector:dimensions_mismatch",
		status = 400,
		when = "A vector field is given a vector with other dimensions than the field declares."
	)
	@ReturnsError(
		value = "document:vector:value_zero",
		status = 400,
		when = "A vector field compared by cosine is given a vector of only zeros."
	)
	@ReturnsError(
		value = "document:primary_key_required",
		status = 400,
		when = "A change to some of a document carries no primary key, so it names no document."
	)
	@ReturnsError(
		value = "index:generation:unsettled",
		status = 400,
		when = "The generation the index serves from kept changing while the write was made. Send the request again."
	)
	public UpdateResponse update(
		@Parameter(
			description = """
				Name of the index to write to, optionally specifying a \
				generation such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Behavior when a document key does not exist: `fail` (default) \
				fails the request, while `skip` updates the remaining \
				documents and returns the missing keys under `missing`.""",
			schema = @Schema(enumeration = {"fail", "skip"}, defaultValue = "fail")
		)
		@QueryParam("missing") String missing,
		@Parameter(
			description = """
				Behavior when the index refuses a change: `fail` (default) stops \
				at the first one and fails the request, while `skip` applies the \
				remaining changes and returns the refused ones under `failed`. A \
				key nothing is indexed under is governed by `missing` instead \
				when that says `skip`.""",
			schema = @Schema(enumeration = {"fail", "skip"}, defaultValue = "fail")
		)
		@QueryParam("onError") String onError,
		@RequestBody(content = @Content(
			schema = @Schema(implementation = UpdateRequest.class),
			examples = @ExampleObject(
				name = "changes",
				summary = "Two documents changed by path",
				value = UpdateRequest.EXAMPLE
			)
		))
		UpdateRequest body
	) {
		if(body == null || body.documents() == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		checkWritable(name);
		var skipMissing = skipMissing(missing);
		var skipErrors = skipErrors(onError);
		var missingKeys = Lists.mutable.<String>empty();
		var failures = Lists.mutable.<DocumentFailure>empty();
		var documents = body.documents();
		var landed = new Landed();

		var updated = measure("update", () -> {
			var changed = 0;

			for(var i = 0; i < documents.size(); i++) {
				var entry = BatchEntry.inDocuments(i);
				var json = documents.get(i);
				var processed = changed;

				boolean applied;
				try {
					applied = write(name, landed, index -> updateDocument(
						index, name, json, entry, processed, skipMissing, missingKeys
					));
				} catch(ValidationException e) {
					if(!skipErrors) {
						throw e;
					}

					failures.add(toFailure(entry, e));
					continue;
				}

				if(applied) {
					changed++;
				}
			}

			return changed;
		});

		return new UpdateResponse(updated, missingKeys, failures, landed.token(name));
	}

	/**
	 * Change some of the fields of documents already in an index, one JSON
	 * object per line.
	 *
	 * @param name
	 * @param missing
	 * @param onError
	 * @param body
	 * @return
	 */
	@POST
	@Path("/actions/update")
	@Consumes(NDJSON)
	@RequiresPermission(Permission.DOCUMENTS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	/*
	 * Carries no OpenAPI annotations beyond the code only a streamed body
	 * answers with, for the reason the newline delimited indexing endpoint
	 * above does not.
	 */
	@ReturnsError(
		value = "request:body_too_large",
		status = 413,
		when = "The body passed the size the node accepts for a newline-delimited request. The changes read before that are applied; `processed` says how many, so the rest can be sent again."
	)
	@ReturnsError(
		value = "request:body_unreadable",
		status = 400,
		when = "The body stopped arriving part way through. The changes read before that are applied; send the rest again."
	)
	public UpdateResponse updateStream(
		@PathParam("name") String name,
		@QueryParam("missing") String missing,
		@QueryParam("onError") String onError,
		InputStream body
	) {
		if(body == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		checkWritable(name);
		var skipMissing = skipMissing(missing);
		var skipErrors = skipErrors(onError);
		var missingKeys = Lists.mutable.<String>empty();
		var failures = Lists.mutable.<DocumentFailure>empty();
		var landed = new Landed();

		var updated = measure("update", () -> {
			var read = 0;
			var changed = 0;

			try(var documents = mapper.readerFor(Map.class).<Map<String, Object>>readValues(body)) {
				while(hasNext(documents, read, changed)) {
					var entry = BatchEntry.onLine(read, lineOf(documents));
					// nextValue rather than next, see addStream
					var json = documents.nextValue();
					var processed = changed;

					read++;

					boolean applied;
					try {
						applied = write(name, landed, index -> updateDocument(
							index, name, json, entry, processed, skipMissing, missingKeys
						));
					} catch(ValidationException e) {
						if(!skipErrors) {
							throw e;
						}

						failures.add(toFailure(entry, e));
						continue;
					}

					if(applied) {
						changed++;
					}
				}
			} catch(RequestBodyTooLargeException e) {
				throw tooLarge(e, read, changed);
			} catch(JacksonException e) {
				throw malformed(e, read, changed);
			} catch(IOException e) {
				throw unreadable(e, read, changed);
			}

			return changed;
		});

		return new UpdateResponse(updated, missingKeys, failures, landed.token(name));
	}

	/**
	 * Read what a request asked to happen about a key nothing is indexed under.
	 */
	private static boolean skipMissing(String missing) {
		if(missing == null || missing.equals("fail")) {
			return false;
		}

		if(missing.equals("skip")) {
			return true;
		}

		throw new ValidationException(
			UPDATE_MISSING_UNKNOWN.toMessage(Location.create("missing"), "value", missing)
		);
	}

	/**
	 * Read what a request asked to happen about a document the index refuses.
	 *
	 * <p>This covers a document or a change the index will not take: one that is
	 * not an object, one that breaks the definition of the index, and a key
	 * nothing is indexed under where {@code missing} did not already say to skip
	 * it. It does not cover a body that cannot be read as JSON, because the
	 * reader cannot then say where the next document begins, nor a failure of
	 * the index itself, which the documents after it would meet as well.
	 */
	private static boolean skipErrors(String onError) {
		if(onError == null || onError.equals("fail")) {
			return false;
		}

		if(onError.equals("skip")) {
			return true;
		}

		throw new ValidationException(
			ON_ERROR_UNKNOWN.toMessage(Location.create("onError"), "value", onError)
		);
	}

	/**
	 * Apply one change of a request, reporting what is wrong with it as
	 * problems of the request rather than of the change on its own.
	 *
	 * @param entry
	 *   where in the request the change sits, which is what its errors are
	 *   placed under and say about the batch
	 * @param processed
	 *   how many documents of the batch the index has changed before this one
	 * @param missingKeys
	 *   where a key nothing was indexed under is collected, for a request that
	 *   asked for those to be skipped
	 * @return
	 *   whether a document was changed
	 */
	private static boolean updateDocument(
		Index index,
		String name,
		Map<String, Object> json,
		BatchEntry entry,
		int processed,
		boolean skipMissing,
		MutableList<String> missingKeys
	) {
		var reported = entry.reported(processed);

		if(json == null) {
			throw new ValidationException(
				NOT_AN_OBJECT.toMessage(entry.location(), reported)
			);
		}

		DocumentPatch patch;
		boolean updated;
		try {
			patch = DocumentMapper.toPatch(index, json);
			updated = index.updateDocument(patch);
		} catch(ValidationException e) {
			throw new ValidationException(e.getErrors().collect(
				error -> error.at(at(entry.location(), error.getLocation())).with(reported)
			));
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, reported.withKeyValue("index", name), e);
		}

		if(updated) {
			return true;
		}

		/*
		 * The key is known to be there - a patch without one is refused by the
		 * index before it looks for anything.
		 */
		var key = keyText(patch.get(index.getPrimaryKey().orElseThrow().getName()));

		if(!skipMissing) {
			throw new ValidationException(
				UPDATE_NOT_FOUND.toMessage(
					entry.location(),
					reported.withKeyValue("key", key)
				)
			);
		}

		missingKeys.add(key);
		return false;
	}

	/**
	 * Indexes one document under the primary key in the URL path, replacing
	 * whatever is indexed under that key.
	 *
	 * <p>Provide the key as text in the URL path, parsed according to the key
	 * field type. The body can leave the primary key field out, because the
	 * document is indexed under the key in the path.
	 *
	 * @param name
	 * @param key
	 * @param body
	 *   the whole document, keyed by field name
	 * @return
	 *   no content, whether or not a document was indexed under the key before
	 *   the request
	 */
	@PUT
	@Path("/{key}")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.DOCUMENTS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "putDocument",
		summary = "Index a document under a key",
		description = """
			Indexes the document in the request body under the primary key in \
			the path, replacing whatever is indexed under that key. Indexing \
			is a statement of desired state, so repeating the request produces \
			the same outcome and the response says the same thing whether or \
			not a document was indexed under the key before the request.

			The body is one document object, formatted like an entry of `POST \
			/v1alpha1/indexes/{name}/documents`. Leave the primary key field \
			out. The document is indexed under the key in the path. A body \
			that does give the primary key field has to give that same key.

			A document sent this way goes to the index as a whole. Use `PATCH` \
			on the same path to change named parts of a document and leave the \
			rest.

			One request carries one document. To load a dataset, send batches \
			to `POST /v1alpha1/indexes/{name}/documents`, which takes a \
			newline delimited body and costs one request for each batch \
			instead of one for each document.

			Changes become searchable and replicate to remote storage after \
			the index commits. The writer commits automatically based on \
			indexed document volume or elapsed time. To commit changes \
			immediately, call `POST \
			/v1alpha1/admin/indexes/{name}/actions/commit`.

			The index definition has to declare a primary key.

			The operation runs on the index writer node. A write request \
			received by another node is forwarded automatically."""
	)
	@APIResponse(
		responseCode = "204",
		description = """
			The document was indexed, whether or not a document existed under \
			the specified key."""
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The key cannot be read as the type of the primary key field, the \
			index declares no primary key, the body names another document \
			than the path, or the index refused the document.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "search:value_invalid",
		status = 400,
		when = "The key in the path cannot be read as the type of the primary key field."
	)
	@ReturnsError(
		value = "index:no_primary_key",
		status = 400,
		when = "The index definition declares no primary key, so a document cannot be named."
	)
	@ReturnsError(
		value = "document:key_conflicting",
		status = 400,
		when = "The body gives the primary key field a value other than the key in the path."
	)
	@ReturnsError(
		value = "request:body_required",
		status = 400,
		when = "The request carries no document."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "The node holds no such index, or the key has no permission on it."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:out_of_date",
		status = 409,
		when = "The index is synchronizing. Send the request again."
	)
	@ReturnsError(
		value = "index:readonly",
		status = 409,
		when = "The node lost the writer role while the request ran. Send the request again to reach the new writer."
	)
	@ReturnsError(
		value = "reindex:target_busy",
		status = 409,
		when = "An active reindex job holds the target generation. Wait for the job, or write to another generation."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed to free local resources. Sending it again reopens the index."
	)
	@ReturnsError(
		value = "index:generation:unsettled",
		status = 400,
		when = "The generation the index serves from kept changing while the write was made. Send the request again."
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index with the specified name exists on this node, or the API \
			key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			No node is available to write the index, the index is currently \
			synchronizing, or the target generation is locked by an active \
			reindex job.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = """
			The request was forwarded to the index writer and the writer did \
			not respond.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources. \
			Repeating the request reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public Response put(
		@Parameter(
			description = """
				Name of the index to write to, optionally specifying a \
				generation such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Primary key to index the document under. Parsed according to \
				the key field type.""",
			example = "1"
		)
		@PathParam("key") String key,
		@RequestBody(
			description = """
				The document, keyed by field name. The primary key field can \
				be left out, because the document is indexed under the key in \
				the path.""",
			required = true,
			content = @Content(
				schema = @Schema(type = SchemaType.OBJECT, implementation = Object.class),
				examples = @ExampleObject(
					name = "document",
					summary = "A document with a locale-specific field",
					value = """
						{ "name": { "sv": "blåbärssylt" }, "tags": ["sylt", "bär"], "energy": 234 }"""
				)
			)
		)
		Map<String, Object> body
	) {
		if(body == null) {
			throw new ValidationException(DOCUMENT_MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		checkWritable(name);
		var landed = new Landed();

		measure("put", () -> write(name, landed, index -> {
			var primaryKey = index.parsePrimaryKey(key);
			index.checkPrimaryKey(primaryKey);

			var keyField = index.getPrimaryKey().orElseThrow().getName();

			try {
				index.addDocument(
					DocumentMapper.toEngine(index, withKey(index, body, keyField, primaryKey))
				);
			} catch(IOException e) {
				throw new IndexException(IO_ERROR, e, "index", name);
			}

			return 1;
		}));

		return Response.noContent()
			.header(FreshnessTokens.HEADER, landed.token(name))
			.build();
	}

	/**
	 * Give a document the primary key taken from the path, so the write goes
	 * to the key the request was sent to. A key field the body carries is
	 * replaced by that key, so the key the write carries has the type the key
	 * field holds even when the body wrote it as another JSON type.
	 *
	 * @throws ValidationException
	 *   if the body gives the key field another key, which would leave the
	 *   path and the body disagreeing - the write would go where the body
	 *   says, and nothing would be indexed under the key that was requested
	 */
	private static Map<String, Object> withKey(
		Index index,
		Map<String, Object> body,
		String keyField,
		Object primaryKey
	) {
		var given = body.get(keyField);

		if(given != null && !primaryKey.equals(index.parsePrimaryKey(keyText(given)))) {
			throw new ValidationException(
				KEY_CONFLICTING.toMessage(
					ObjectLocation.root().forField(keyField),
					"key", keyText(primaryKey),
					"name", keyField
				)
			);
		}

		/*
		 * Copied before the key goes in: the body is the request as it was
		 * read, and an error about it names where a field sat in what arrived.
		 */
		var document = new LinkedHashMap<>(body);
		document.put(keyField, primaryKey);

		return document;
	}

	/**
	 * Updates specific fields of the document indexed under the specified
	 * primary key, leaving the remaining fields unchanged.
	 *
	 * <p>The primary key is provided in the URL path and parsed according to
	 * the defined key field type, so the request body contains only field
	 * paths.
	 *
	 * @param name
	 * @param key
	 * @param body
	 *   the changes to apply, formatted as field paths mapping to new values
	 * @return
	 *   no content on success; returns not found if no document exists under
	 *   the key
	 */
	@PATCH
	@Path("/{key}")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.DOCUMENTS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "updateDocument",
		summary = "Update fields of one document",
		description = """
			Changes named parts of a single document, leaving the remaining \
			fields unchanged. The request body is a single change object \
			formatted like an entry in `POST /documents/actions/update`, with \
			the primary key supplied in the URL path: each key is a path \
			naming a location in the document, a path with a value replaces \
			what the path names, a path set to `null` empties what it names, \
			and an omitted path leaves the existing value unchanged.

			The body may repeat the primary key field as long as it matches \
			the key specified in the path.

			Unlike indexing, this endpoint describes modifications rather than \
			desired state; requesting an update for an unindexed key returns \
			`404` rather than creating a document. The updated document is \
			validated as a whole.

			The index has to declare a primary key and retain document source \
			copies."""
	)
	@APIResponse(
		responseCode = "204",
		description = "The document was changed."
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The change was rejected by validation, or a path in it names \
			something the index or the document does not hold. A path is \
			refused for the same reasons as in a batch update.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			Nothing is indexed under the key, no index with the specified name \
			exists on this node, or the caller key lacks permissions on the \
			index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = "The index cannot be written to right now.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The node holding the index did not answer.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = "The index is not open on the node right now.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "search:value_invalid",
		status = 400,
		when = "The key in the path cannot be read as the type of the primary key field."
	)
	@ReturnsError(
		value = "document:key_conflicting",
		status = 400,
		when = "The body gives the primary key field a value other than the key in the path."
	)
	@ReturnsError(
		value = "index:no_primary_key",
		status = 400,
		when = "The index definition declares no primary key, so a document cannot be named."
	)
	@ReturnsError(
		value = "document:source_not_kept",
		status = 400,
		when = "The index does not store document copies. Send the complete document instead."
	)
	@ReturnsError(
		value = "document:patch:path_invalid",
		status = 400,
		when = "A path in the change could not be read."
	)
	@ReturnsError(
		value = "document:patch:no_match",
		status = 400,
		when = "A selector names no value the document holds. A selector never creates the value it names."
	)
	@ReturnsError(
		value = "document:patch:field_unknown",
		status = 400,
		when = "A path reaches into a field the index does not have."
	)
	@ReturnsError(
		value = "document:patch:not_an_object",
		status = 400,
		when = "A path reaches inside a field whose values are not objects."
	)
	@ReturnsError(
		value = "document:patch:selector_required",
		status = 400,
		when = "A path reaches into a list of objects without saying which value."
	)
	@ReturnsError(
		value = "document:patch:selector_unsupported",
		status = 400,
		when = "A path names one value of a field that holds neither locale variants nor objects."
	)
	@ReturnsError(
		value = "document:locale_unknown",
		status = 400,
		when = "A path names a locale the field holds no variant for."
	)
	@ReturnsError(
		value = "document:patch:add_unsupported",
		status = 400,
		when = "The change adds a value to a field that holds a single value. Name the field on its own to replace it."
	)
	@ReturnsError(
		value = "document:patch:add_reaches_inside",
		status = 400,
		when = "A path reaches inside a value that the same change adds, which does not exist yet. Give the whole value instead."
	)
	@ReturnsError(
		value = "document:patch:key_unsupported",
		status = 400,
		when = "A path names one value of a list by a key that the field declares none of. Match on a field inside the value instead."
	)
	@ReturnsError(
		value = "document:patch:match_not_an_object",
		status = 400,
		when = "A path matches on a field inside a list whose values are not objects."
	)
	@ReturnsError(
		value = "request:body_required",
		status = 400,
		when = "The request carries no change."
	)
	@ReturnsError(
		value = "document:not_found",
		status = 404,
		when = "Nothing is indexed under the key. Index the document whole first."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "The node holds no such index, or the key has no permission on it."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:out_of_date",
		status = 409,
		when = "The index is synchronizing. Send the request again."
	)
	@ReturnsError(
		value = "index:readonly",
		status = 409,
		when = "The node lost the writer role while the request ran. Send the request again to reach the new writer."
	)
	@ReturnsError(
		value = "reindex:target_busy",
		status = 409,
		when = "An active reindex job holds the target generation. Wait for the job, or write to another generation."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed to free local resources. Sending it again reopens the index."
	)
	@ReturnsError(
		value = "document:field_unknown",
		status = 400,
		when = "A document gives a field the index does not have."
	)
	@ReturnsError(
		value = "document:field_inside_object",
		status = 400,
		when = "A document gives a dotted path to a field inside an object instead of the object that holds it."
	)
	@ReturnsError(
		value = "document:field_required",
		status = 400,
		when = "A document leaves out a field the definition marks as required."
	)
	@ReturnsError(
		value = "document:locale_unsupported",
		status = 400,
		when = "A value carries a locale on a field that is not locale specific."
	)
	@ReturnsError(
		value = "document:object_required",
		status = 400,
		when = "A field that holds objects is given a value that is not one."
	)
	@ReturnsError(
		value = "document:object_unsupported",
		status = 400,
		when = "A field that does not hold objects is given one."
	)
	@ReturnsError(
		value = "document:multiple_unsupported",
		status = 400,
		when = "A field that holds a single value is given several."
	)
	@ReturnsError(
		value = "document:multiple_per_locale_unsupported",
		status = 400,
		when = "A field that holds a single value per locale is given several in one locale."
	)
	@ReturnsError(
		value = "document:object_key_duplicate",
		status = 400,
		when = "Two values of an object field read the same under the key that tells them apart."
	)
	@ReturnsError(
		value = "document:number:value_invalid",
		status = 400,
		when = "A number field is given a value that cannot be read as its type."
	)
	@ReturnsError(
		value = "document:number:value_out_of_range",
		status = 400,
		when = "A number field is given a value outside the bounds its definition declares."
	)
	@ReturnsError(
		value = "document:geo_point:value_invalid",
		status = 400,
		when = "A geo point field is given a value that is not a latitude and a longitude."
	)
	@ReturnsError(
		value = "document:geo_point:value_out_of_range",
		status = 400,
		when = "A geo point field is given a point that is not on the earth."
	)
	@ReturnsError(
		value = "document:timestamp:value_invalid",
		status = 400,
		when = "A timestamp field is given a value that is not an ISO 8601 date and time with an offset."
	)
	@ReturnsError(
		value = "document:vector:value_invalid",
		status = 400,
		when = "A vector field is given a value that is not an array of floats."
	)
	@ReturnsError(
		value = "document:vector:value_not_finite",
		status = 400,
		when = "A vector field is given a value that is not a finite number."
	)
	@ReturnsError(
		value = "document:vector:dimensions_mismatch",
		status = 400,
		when = "A vector field is given a vector with other dimensions than the field declares."
	)
	@ReturnsError(
		value = "document:vector:value_zero",
		status = 400,
		when = "A vector field compared by cosine is given a vector of only zeros."
	)
	@ReturnsError(
		value = "index:generation:unsettled",
		status = 400,
		when = "The generation the index serves from kept changing while the write was made. Send the request again."
	)
	public Response patch(
		@Parameter(
			description = """
				Name of the index to write to, optionally specifying a \
				generation such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Primary key of the document to change. Parsed according to the \
				key field type.""",
			example = "1"
		)
		@PathParam("key") String key,
		@RequestBody(
			description = """
				The places to change, keyed by path. A path with a value \
				replaces what the path names, a path set to `null` empties \
				what the path names, and an omitted path leaves what it would \
				name unchanged.""",
			required = true,
			content = @Content(
				schema = @Schema(type = SchemaType.OBJECT, implementation = Object.class),
				examples = @ExampleObject(
					name = "change",
					value = """
						{ "price": 34.50, "variants[sku=V-2].price": 29.0 }"""
				)
			)
		)
		Map<String, Object> body
	) {
		if(body == null) {
			throw new ValidationException(CHANGE_MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		checkWritable(name);
		var landed = new Landed();

		measure("update", () -> write(name, landed, index -> {
			var primaryKey = index.parsePrimaryKey(key);
			var keyField = index.getPrimaryKey().orElseThrow().getName();

			var patch = withKey(index, DocumentMapper.toPatch(index, body), keyField, primaryKey);

			try {
				if(!index.updateDocument(patch)) {
					throw new IndexDocumentNotFoundException(key);
				}
			} catch(IOException e) {
				throw new IndexException(IO_ERROR, e, "index", name);
			}

			return 1;
		}));

		return Response.noContent()
			.header(FreshnessTokens.HEADER, landed.token(name))
			.build();
	}

	/**
	 * Give a patch the primary key the path named, so that the document to
	 * change is the one the path names. A change naming the key field is
	 * replaced by that key, so the key the write carries has the type the key
	 * field holds even when the body wrote it as another JSON type.
	 *
	 * @throws ValidationException
	 *   if the body names the key field as another document, which would leave
	 *   the path and the body naming two - the merge reads what the path names
	 *   and the write goes where the merged document says
	 */
	private static DocumentPatch withKey(
		Index index,
		DocumentPatch patch,
		String keyField,
		Object primaryKey
	) {
		var changes = Lists.mutable.<DocumentPatch.Change>empty();
		var named = false;

		for(var change : patch.changes()) {
			if(!change.field().equals(keyField)) {
				changes.add(change);
				continue;
			}

			checkNamesTheSameDocument(index, change, keyField, primaryKey);
			changes.add(keyChange(keyField, primaryKey));
			named = true;
		}

		if(!named) {
			changes.add(keyChange(keyField, primaryKey));
		}

		return new DocumentPatch(changes.toImmutable());
	}

	/**
	 * Refuse a change to the primary key field that names a document other than
	 * the one the path names. The value is read as the key field holds it, the
	 * way the key in the path is read, so a key and the same key written as
	 * another JSON type name one document.
	 */
	private static void checkNamesTheSameDocument(
		Index index,
		DocumentPatch.Change change,
		String keyField,
		Object primaryKey
	) {
		var given = change.values().notEmpty() ? change.values().getFirst().value() : null;

		if(
			change.inner() == null
				&& change.selector() instanceof DocumentPatch.Selector.All
				&& given != null
				&& primaryKey.equals(index.parsePrimaryKey(keyText(given)))
		) {
			return;
		}

		throw new ValidationException(
			KEY_CONFLICTING.toMessage(
				ObjectLocation.root().forField(keyField),
				"key", keyText(primaryKey),
				"name", keyField
			)
		);
	}

	/**
	 * A primary key as text, the one shape a key takes on the way out. The
	 * {@code missing} keys of a batch change, the {@code next} key of a read
	 * and the {@code key} argument of an error are written this way, and the
	 * {@code after} parameter and the key of a path are read back from it.
	 *
	 * <p>A whole number written as a decimal, such as {@code 1.0}, loses the
	 * fraction, so a body that writes a key as another JSON type names the
	 * key the path names.
	 */
	private static String keyText(Object given) {
		if(given instanceof Number number) {
			try {
				return new BigDecimal(number.toString()).toBigIntegerExact().toString();
			} catch(ArithmeticException | NumberFormatException e) {
				// Not a whole number, so it is compared as it was written
			}
		}

		return String.valueOf(given);
	}

	/**
	 * The change that gives the primary key field one key, replacing whatever
	 * the document holds under it.
	 */
	private static DocumentPatch.Change keyChange(String keyField, Object primaryKey) {
		return new DocumentPatch.Change(
			keyField,
			DocumentPatch.Selector.ALL,
			null,
			Lists.immutable.of(new Document.Value(keyField, primaryKey))
		);
	}

	/**
	 * Delete the document indexed under a primary key.
	 *
	 * <p>Provide the key as text in the URL path, parsed according to the key
	 * field type.
	 *
	 * @param name
	 * @param key
	 * @return
	 *   no content, whether or not a document existed under the specified key -
	 *   removing a document is a statement of desired state, so repeating the
	 *   request produces the same outcome
	 */
	@DELETE
	@Path("/{key}")
	@RequiresPermission(Permission.DOCUMENTS_DELETE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "deleteDocument",
		summary = "Delete a document by key",
		description = """
			Removes the document indexed under the specified primary key. \
			Removing a document is a statement of desired state, so requesting \
			the deletion of an unindexed key is not an error and returns \
			status `204`.

			The index definition has to declare a primary key."""
	)
	@APIResponse(
		responseCode = "204",
		description = """
			The document was removed, whether or not a document existed under \
			the specified key."""
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The key cannot be read as the type of the primary key field, or the \
			index declares no primary key.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "search:value_invalid",
		status = 400,
		when = "The key in the path cannot be read as the type of the primary key field."
	)
	@ReturnsError(
		value = "index:no_primary_key",
		status = 400,
		when = "The index definition declares no primary key, so a document cannot be named."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "The node holds no such index, or the key has no permission on it."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:out_of_date",
		status = 409,
		when = "The index is synchronizing. Send the request again."
	)
	@ReturnsError(
		value = "index:readonly",
		status = 409,
		when = "The node lost the writer role while the request ran. Send the request again to reach the new writer."
	)
	@ReturnsError(
		value = "reindex:target_busy",
		status = 409,
		when = "An active reindex job holds the target generation. Wait for the job, or write to another generation."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed to free local resources. Sending it again reopens the index."
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index with the specified name exists on this node, or the API \
			key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			No node is available to write the index, the index is currently \
			synchronizing, or the target generation is locked by an active \
			reindex job.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = """
			The request was forwarded to the index writer and the writer did \
			not respond.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources. \
			Repeating the request reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "index:generation:unsettled",
		status = 400,
		when = "The generation the index serves from kept changing while the write was made. Send the request again."
	)
	public Response delete(
		@Parameter(
			description = """
				Name of the index to write to, optionally specifying a \
				generation such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Primary key of the document to remove. Parsed according to the \
				key field type.""",
			example = "1"
		)
		@PathParam("key") String key
	) {
		checkWritable(name);
		var landed = new Landed();

		measure("delete", () -> write(name, landed, index -> {
			try {
				index.deleteDocument(index.parsePrimaryKey(key));
			} catch(IOException e) {
				throw new IndexException(IO_ERROR, e, "index", name);
			}

			return 1;
		}));

		return Response.noContent()
			.header(FreshnessTokens.HEADER, landed.token(name))
			.build();
	}

	/**
	 * Deletes documents from an index matching a list of primary keys or a
	 * search query, or every document in the index.
	 *
	 * @param name
	 * @param body
	 * @return
	 */
	@POST
	@Path("/actions/delete")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.DOCUMENTS_DELETE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "deleteDocuments",
		summary = "Delete documents by keys, query, or all",
		description = """
			Deletes multiple documents matching a list of primary keys or a \
			search query, or empties the index. The request body must name \
			exactly one of `keys`, `query`, and `all`.

			When deleting by `keys`, all keys are validated before any \
			documents are removed. If any key is invalid, no documents are \
			removed. When deleting by `query`, the operation removes matching \
			committed searchable documents along with any uncommitted \
			documents indexed since the last commit. A `query` requires at \
			least one clause. To empty the index, set `all` to `true`."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The documents were removed.",
		content = @Content(
			schema = @Schema(implementation = DeleteResponse.class),
			examples = @ExampleObject(name = "deleted", value = DeleteResponse.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The body does not name what to delete, or it names a key or a query \
			the index cannot use.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "document:delete:target_required",
		status = 400,
		when = "The body holds none of `keys`, `query`, and `all`."
	)
	@ReturnsError(
		value = "document:delete:target_conflicting",
		status = 400,
		when = "The body holds more than one of `keys`, `query`, and `all`. Send one of them."
	)
	@ReturnsError(
		value = "document:delete:query_empty",
		status = 400,
		when = "The body holds a `query` without clauses. Send `all` to empty the index."
	)
	@ReturnsError(
		value = "document:delete:locale_without_query",
		status = 400,
		when = "The body states a `locale` without a `query`."
	)
	@ReturnsError(
		value = "search:value_invalid",
		status = 400,
		when = "A key cannot be read as the type of the primary key field."
	)
	@ReturnsError(
		value = "document:key_required",
		status = 400,
		when = "An entry of `keys` carries no key."
	)
	@ReturnsError(
		value = "index:no_primary_key",
		status = 400,
		when = "The index definition declares no primary key, so a document cannot be named by `keys`."
	)
	@ReturnsError(
		value = "request:value_required",
		status = 400,
		when = "A part of `query` that needs a value carries none. The `path` names it."
	)
	@ReturnsError(
		value = "search:clause:field_required",
		status = 400,
		when = "A `field` clause of `query` does not name the field to match."
	)
	@ReturnsError(
		value = "search:clause:match_required",
		status = 400,
		when = "A `field` clause of `query` does not say what to look for in the field."
	)
	@ReturnsError(
		value = "search:clause:text_required",
		status = 400,
		when = "A `text` clause of `query` carries no text to search for."
	)
	@ReturnsError(
		value = "search:clause:path_required",
		status = 400,
		when = "A `nested` clause of `query` does not name the object field to match inside."
	)
	@ReturnsError(
		value = "search:clause:vector_required",
		status = 400,
		when = "A `knn` clause of `query` carries no vector to find the neighbours of."
	)
	@ReturnsError(
		value = "search:clause:k_out_of_range",
		status = 400,
		when = "The `k` of a `knn` clause is missing or not above zero."
	)
	@ReturnsError(
		value = "search:clause:weight_out_of_range",
		status = 400,
		when = "The `weight` of a `boost` clause is missing, below zero or not a finite number."
	)
	@ReturnsError(
		value = "search:clause:slop_out_of_range",
		status = 400,
		when = "The `slop` of a `text` clause is below zero."
	)
	@ReturnsError(
		value = "search:clause:slop_unsupported",
		status = 400,
		when = "A `text` clause sets `slop` without matching as a phrase."
	)
	@ReturnsError(
		value = "search:clause:join_unsupported",
		status = 400,
		when = "A `text` clause sets `join` without matching what somebody typed."
	)
	@ReturnsError(
		value = "search:clause:rankings_too_few",
		status = 400,
		when = "A `fuse` clause holds fewer than two rankings to fuse."
	)
	@ReturnsError(
		value = "search:clause:ranking_empty",
		status = 400,
		when = "A ranking of a `fuse` clause holds nothing to rank by."
	)
	@ReturnsError(
		value = "search:clause:rank_constant_out_of_range",
		status = 400,
		when = "The `rankConstant` of a `fuse` clause is not a number above zero."
	)
	@ReturnsError(
		value = "search:clause:depth_out_of_range",
		status = 400,
		when = "The `depth` of a `fuse` clause is below one result."
	)
	@ReturnsError(
		value = "search:clause:interpret_fields_required",
		status = 400,
		when = "The `interpret` of a `text` clause names no target field."
	)
	@ReturnsError(
		value = "search:clause:interpret_when_unsupported",
		status = 400,
		when = "The `when` of an `interpret` target holds a `nested`, `knn` or `fuse` clause."
	)
	@ReturnsError(
		value = "search:matcher:value_required",
		status = 400,
		when = "A matcher carries no value to look for."
	)
	@ReturnsError(
		value = "search:matcher:range_empty",
		status = 400,
		when = "A range matcher carries no bound."
	)
	@ReturnsError(
		value = "search:matcher:range_conflicting",
		status = 400,
		when = "A range matcher combines `gte` with `gt`, or `lte` with `lt`."
	)
	@ReturnsError(
		value = "search:matcher:origin_required",
		status = 400,
		when = "A distance matcher carries no `lat` and `lon` to measure from."
	)
	@ReturnsError(
		value = "search:matcher:radius_required",
		status = 400,
		when = "A distance matcher does not say how far from the origin values may be."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "The node holds no such index, or the key has no permission on it."
	)
	@ReturnsError(
		value = "indexer:unavailable",
		status = 409,
		when = "No node is available to write the index. Send the request again once one is."
	)
	@ReturnsError(
		value = "index:out_of_date",
		status = 409,
		when = "The index is synchronizing. Send the request again."
	)
	@ReturnsError(
		value = "index:readonly",
		status = 409,
		when = "The node lost the writer role while the request ran. Send the request again to reach the new writer."
	)
	@ReturnsError(
		value = "reindex:target_busy",
		status = 409,
		when = "An active reindex job holds the target generation. Wait for the job, or write to another generation."
	)
	@ReturnsError(
		value = "indexer:unreachable",
		status = 502,
		when = "The request was forwarded to the index writer and the writer did not answer. Send it again."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed to free local resources. Sending it again reopens the index."
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index with the specified name exists on this node, or the API \
			key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			No node is available to write the index, the index is currently \
			synchronizing, or the target generation is locked by an active \
			reindex job.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = """
			The request was forwarded to the index writer and the writer did \
			not respond.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources. \
			Repeating the request reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "search:field_unknown",
		status = 400,
		when = "A clause, sort or facet names a field the index does not have."
	)
	@ReturnsError(
		value = "search:usage_unsupported",
		status = 400,
		when = "A clause, sort or facet uses a field in a way the definition does not enable for it."
	)
	@ReturnsError(
		value = "search:matcher:type_unsupported",
		status = 400,
		when = "A matcher is used on a field whose type cannot answer it."
	)
	@ReturnsError(
		value = "search:locale_unsupported",
		status = 400,
		when = "The `locale` names one the engine has no rules for."
	)
	@ReturnsError(
		value = "search:no_searchable_fields",
		status = 400,
		when = "A text clause names no fields and the index has none defined for matching."
	)
	@ReturnsError(
		value = "search:nested:path_not_nested",
		status = 400,
		when = "A `nested` clause names a path whose values are flattened."
	)
	@ReturnsError(
		value = "search:nested:field_not_inside",
		status = 400,
		when = "A clause inside a `nested` clause names a field outside its path."
	)
	@ReturnsError(
		value = "search:nested:field_outside",
		status = 400,
		when = "A clause outside a `nested` clause names a field inside a nested list."
	)
	@ReturnsError(
		value = "search:nested:clause_unsupported",
		status = 400,
		when = "A `nested` clause holds a clause that cannot run against a single value, such as `fuse`."
	)
	@ReturnsError(
		value = "search:interpret:unit_required",
		status = 400,
		when = "An `interpret` target names a field that is not a number field or declares no `unit`."
	)
	@ReturnsError(
		value = "search:interpret:fallback_unit_mismatch",
		status = 400,
		when = "A `fallback` target declares another unit than the target it stands in for."
	)
	@ReturnsError(
		value = "search:clause:k_required",
		status = 400,
		when = "A `knn` clause carries no `k`."
	)
	@ReturnsError(
		value = "index:generation:unsettled",
		status = 400,
		when = "The generation the index serves from kept changing while the write was made. Send the request again."
	)
	public DeleteResponse delete(
		@Parameter(
			description = """
				Name of the index to write to, optionally specifying a \
				generation such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@RequestBody(content = @Content(
			schema = @Schema(implementation = DeleteRequest.class),
			examples = {
				@ExampleObject(
					name = "keys",
					summary = "By primary key",
					value = DeleteRequest.BY_KEYS
				),
				@ExampleObject(
					name = "query",
					summary = "By query",
					value = DeleteRequest.BY_QUERY
				),
				@ExampleObject(
					name = "all",
					summary = "Every document",
					value = DeleteRequest.ALL
				)
			}
		))
		DeleteRequest body
	) {
		var everything = body != null && body.removesEverything();

		if(body == null || body.keys() == null && body.query() == null && !everything) {
			throw new ValidationException(DELETE_TARGET_REQUIRED.toMessage(Location.create("")));
		}

		var targets = (body.keys() != null ? 1 : 0)
			+ (body.query() != null ? 1 : 0)
			+ (everything ? 1 : 0);

		if(targets > 1) {
			throw new ValidationException(
				DELETE_TARGET_CONFLICTING.toMessage(Location.create(""))
			);
		}

		if(body.query() != null && body.query().isEmpty()) {
			throw new ValidationException(DELETE_QUERY_EMPTY.toMessage(Location.create("query")));
		}

		if(body.locale() != null && body.query() == null) {
			throw new ValidationException(
				DELETE_LOCALE_WITHOUT_QUERY.toMessage(Location.create("locale"))
			);
		}

		checkWritable(name);
		var landed = new Landed();

		if(everything) {
			return new DeleteResponse(measure("delete_by_query", () -> write(name, landed, index -> {
				try {
					return index.deleteByQuery(Lists.immutable.empty(), null);
				} catch(IOException e) {
					throw new IndexException(IO_ERROR, e, "index", name);
				}
			})), landed.token(name));
		}

		if(body.keys() != null) {
			return new DeleteResponse(measure("delete", () -> write(name, landed, index -> {
				try {
					return index.deleteDocuments(toKeys(body.keys()));
				} catch(IOException e) {
					throw new IndexException(IO_ERROR, e, "index", name);
				}
			})), landed.token(name));
		}

		return new DeleteResponse(measure("delete_by_query", () -> write(name, landed, index -> {
			try {
				return index.deleteByQuery(
					SearchRequestMapper.toQuery(body.query(), "query"),
					body.locale()
				);
			} catch(IOException e) {
				throw new IndexException(IO_ERROR, e, "index", name);
			}
		})), landed.token(name));
	}

	/**
	 * Read the keys of a request, saying which of them is missing rather than
	 * removing the ones around it.
	 */
	private static ListIterable<Object> toKeys(List<Object> keys) {
		var errors = Lists.mutable.<ErrorMessage>empty();
		for(var i = 0; i < keys.size(); i++) {
			if(keys.get(i) == null) {
				errors.add(DELETE_KEY_REQUIRED.toMessage(Location.create("keys[" + i + "]")));
			}
		}

		if(errors.notEmpty()) {
			throw new ValidationException(errors);
		}

		return Lists.immutable.ofAll(keys);
	}

	/**
	 * Reads one document back out of an index by its primary key.
	 *
	 * <p>Provide the key as text in the URL path, parsed according to the key
	 * field type.
	 *
	 * @param name
	 * @param key
	 * @return
	 *   the document, formatted as originally indexed, with the state it was
	 *   read from
	 */
	@GET
	@Path("/{key}")
	@Produces(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.DOCUMENTS_READ)
	@Operation(
		operationId = "readDocument",
		summary = "Read a document by key",
		description = """
			Reads the document indexed under the specified primary key, \
			returning it as originally indexed. The document sits under \
			`document`, so send that value back to `POST \
			/v1alpha1/indexes/{name}/documents` to index it again.

			The read is answered from a point-in-time snapshot of the index \
			and sees committed data only, so a document indexed since the last \
			commit is reported as missing and one removed since the last \
			commit is still returned. To read a write back as soon as it \
			lands, pass the freshness token the write returned in the \
			`X-Exofind-Freshness` header.

			The index has to declare a primary key and retain document source \
			copies.

			Read requests are served directly by whichever node receives them, \
			using data that the node has pulled from storage, and are never \
			forwarded to the writer."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The document, formatted as originally indexed.",
		content = @Content(
			schema = @Schema(implementation = DocumentResponse.class),
			examples = @ExampleObject(name = "document", value = DocumentResponse.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The key cannot be read as the type of the primary key field, the \
			index declares no primary key, or the index keeps no copies of its \
			documents.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "search:value_invalid",
		status = 400,
		when = "The key in the path cannot be read as the type of the primary key field."
	)
	@ReturnsError(
		value = "index:no_primary_key",
		status = 400,
		when = "The index definition declares no primary key, so a document cannot be named."
	)
	@ReturnsError(
		value = "document:source_not_kept",
		status = 400,
		when = "The index does not store document copies, so there is nothing to return."
	)
	@ReturnsError(
		value = "search:freshness:invalid",
		status = 400,
		when = "The `X-Exofind-Freshness` header carries a token the engine did not issue. Pass a token back unchanged."
	)
	@ReturnsError(
		value = "search:freshness:version_unsupported",
		status = 400,
		when = "The freshness token was issued in a format version this node does not read. The `version` argument carries it; send the request to a node of the release that issued the token."
	)
	@ReturnsError(
		value = "search:freshness:index_mismatch",
		status = 400,
		when = "The freshness token is of another index than the one in the path. The `index` argument carries the index the token is of."
	)
	@APIResponse(
		responseCode = "404",
		description = """
			Nothing is indexed under the specified key, no index with the \
			specified name exists on this node, or the API key lacks \
			permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "document:not_found",
		status = 404,
		when = "Nothing is indexed under the key in the path, as of the last commit."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "The node holds no such index, or the key has no permission on it."
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources. \
			Repeating the request reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed to free local resources. Sending it again reopens the index."
	)
	@ReturnsError(
		value = "search:freshness:unavailable",
		status = 503,
		when = "The node did not reach the state the freshness token asks for within `EXOFIND_SEARCH_FRESHNESS_WAIT`. Send the request again after the `Retry-After` header."
	)
	@Parameter(
		name = FreshnessTokens.HEADER,
		in = ParameterIn.HEADER,
		description = """
			A freshness token an earlier response returned. The document is \
			read only once the node holds the state it names.""",
		example = "AQoIcHJvZHVjdHMSATIYBw"
	)
	public DocumentResponse read(
		@Parameter(
			description = """
				Name of the index to read, optionally naming one generation as \
				`books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Primary key of the document to read. Parsed according to the \
				key field type.""",
			example = "1"
		)
		@PathParam("key") String key
	) {
		var index = freshnessWaiter.await(
			name,
			FreshnessTokens.decode(null, freshnessHeader(), name)
		);

		index.checkReadable();

		Document document;
		try {
			document = index.getDocument(index.parsePrimaryKey(key));
		} catch(IOException e) {
			throw new IndexException(READ_ERROR, e, "index", name);
		}

		if(document == null) {
			throw new IndexDocumentNotFoundException(key);
		}

		return new DocumentResponse(
			document,
			FreshnessTokens.encode(freshnessWaiter.stateOf(index))
		);
	}

	/**
	 * Reads documents back out of an index in primary key order.
	 *
	 * @param name
	 * @param after
	 *   primary key to resume reading after, which is omitted from the
	 *   response. Formatted as text matching the key in the path of a delete,
	 *   so numeric keys are written as numbers. Omit to start at the first
	 *   document
	 * @param limit
	 *   maximum number of documents to return
	 * @return
	 *   documents in primary key order, with the continuation key to resume
	 *   reading after when more documents are available
	 */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.DOCUMENTS_READ)
	@Operation(
		operationId = "readDocuments",
		summary = "Read documents",
		description = """
			Reads documents back out of an index in primary key order, \
			returning them as originally indexed. Whole-number keys return in \
			numeric order with negative numbers first, and text keys return in \
			UTF-8 byte order.

			Set the `Accept` request header to select the response format. The \
			default format is `application/json`, which also applies to \
			`Accept: */*`. The format `application/x-ndjson` returns one \
			document per line with no outer wrapper, matching byte-for-byte \
			the format accepted by the indexing endpoint. A newline-delimited \
			body contains only documents, so the line count indicates whether \
			more documents are available rather than a `next` key.

			Every response is bounded, so reading an entire index requires a \
			sequence of requests, each passing the previous response's `next` \
			key in the `after` parameter. A single request reads from a \
			point-in-time snapshot of the index and sees committed data only. \
			Across multiple requests, documents indexed under keys that the \
			read has already passed are omitted from subsequent responses.

			Read requests are served directly by whichever node receives them, \
			using data that the node has pulled from storage, and are never \
			forwarded to the writer."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The documents, in primary key order.",
		content = @Content(
			schema = @Schema(implementation = ScanResponse.class),
			examples = @ExampleObject(name = "batch", value = ScanResponse.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The index cannot be scanned, or the `limit` parameter is out of \
			range.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "index:no_primary_key",
		status = 400,
		when = "The index definition declares no primary key, so documents cannot be scanned in key order."
	)
	@ReturnsError(
		value = "document:source_not_kept",
		status = 400,
		when = "The index does not store document copies, so a scan has nothing to return."
	)
	@ReturnsError(
		value = "request:limit_out_of_range",
		status = 400,
		when = "The `limit` parameter is not a whole number from 1 to 10000."
	)
	@ReturnsError(
		value = "search:value_invalid",
		status = 400,
		when = "The `after` parameter cannot be read as the type of the primary key field."
	)
	@ReturnsError(
		value = "index:not_found",
		status = 404,
		when = "The node holds no such index, or the key has no permission on it."
	)
	@ReturnsError(
		value = "index:closed",
		status = 503,
		when = "The request raced the index being closed to free local resources. Sending it again reopens the index."
	)
	@APIResponse(
		responseCode = "404",
		description = """
			No index with the specified name exists on this node, or the API \
			key lacks permissions on the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources. \
			Repeating the request reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@ReturnsError(
		value = "search:freshness:invalid",
		status = 400,
		when = "The `X-Exofind-Freshness` header carries a token the engine did not issue. Pass a token back unchanged."
	)
	@ReturnsError(
		value = "search:freshness:version_unsupported",
		status = 400,
		when = "The freshness token was issued in a format version this node does not read. The `version` argument carries it; send the request to a node of the release that issued the token."
	)
	@ReturnsError(
		value = "search:freshness:index_mismatch",
		status = 400,
		when = "The freshness token is of another index than the one in the path. The `index` argument carries the index the token is of."
	)
	@ReturnsError(
		value = "search:freshness:unavailable",
		status = 503,
		when = "The node did not reach the state the freshness token asks for within `EXOFIND_SEARCH_FRESHNESS_WAIT`. Send the request again after the `Retry-After` header."
	)
	@Parameter(
		name = FreshnessTokens.HEADER,
		in = ParameterIn.HEADER,
		description = """
			A freshness token an earlier response returned. The documents are \
			read only once the node holds the state it names.""",
		example = "AQoIcHJvZHVjdHMSATIYBw"
	)
	public ScanResponse scan(
		@Parameter(
			description = """
				Name of the index to read, optionally naming one generation as \
				`books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Primary key to resume reading after. The specified key is \
				omitted from the response. Formatted as text matching the key \
				in the path of a delete (for example, numeric keys are written \
				as numbers). If no document exists under this key, reading \
				resumes from where the key would be positioned in the order. \
				Omit to start at the first document."""
		)
		@QueryParam("after") String after,
		@Parameter(
			description = "Maximum number of documents to return.",
			schema = @Schema(
				type = SchemaType.INTEGER,
				defaultValue = "100",
				minimum = "1",
				maximum = "10000"
			)
		)
		@QueryParam("limit") String limit
	) {
		var index = freshnessWaiter.await(
			name,
			FreshnessTokens.decode(null, freshnessHeader(), name)
		);
		var wanted = scanLimit(limit);
		var from = scanAfter(index, after);

		var documents = Lists.mutable.<Document>empty();

		int read;
		try {
			read = index.scanDocuments(from, wanted, documents::add);
		} catch(IOException e) {
			throw new IndexException(READ_ERROR, e, "index", name);
		}

		return new ScanResponse(
			documents,
			read < wanted ? null : keyOf(index, documents.getLast()),
			FreshnessTokens.encode(freshnessWaiter.stateOf(index))
		);
	}

	/**
	 * Reads documents from an index as newline-delimited JSON, containing one
	 * document object per line with no outer wrapper, matching byte-for-byte
	 * the format accepted for indexing.
	 *
	 * <p>The response body contains only documents. To determine if more
	 * documents are available, check the number of lines returned: if the
	 * response returns as many lines as requested by the limit, resume the next
	 * request by passing the primary key of the last document in the
	 * continuation parameter. When the response returns fewer lines than the
	 * limit, all documents have been read.
	 *
	 * @param name
	 * @param after
	 * @param limit
	 * @return
	 */
	@GET
	@Produces(NDJSON + ";qs=0.9")
	@RequiresPermission(Permission.DOCUMENTS_READ)
	/*
	 * Declares only what the merged operation cannot get from anywhere else:
	 * that a 200 may come back as newline delimited JSON. The media type is
	 * named rather than inherited from @Produces, which carries the `qs=0.9`
	 * that steers content negotiation and has no business in the document.
	 * Everything else is said by the endpoint above, which the two merge into.
	 */
	@APIResponse(
		responseCode = "200",
		description = "The documents, one JSON object per line, in primary key order.",
		content = @Content(mediaType = NDJSON)
	)
	public Response scanStream(
		@PathParam("name") String name,
		@QueryParam("after") String after,
		@QueryParam("limit") String limit
	) {
		var index = freshnessWaiter.await(
			name,
			FreshnessTokens.decode(null, freshnessHeader(), name)
		);
		var wanted = scanLimit(limit);
		var from = scanAfter(index, after);

		/*
		 * What an index cannot be read this way is said before the body
		 * starts, as an answer that has begun can no longer be turned into the
		 * error that stopped it.
		 */
		index.checkScannable(from);

		StreamingOutput body = out -> {
			try(var generator = mapper.getFactory().createGenerator(out)) {
				/*
				 * Documents follow one another with nothing between them, so
				 * the newline written after each is what separates them.
				 */
				generator.setRootValueSeparator(NOTHING);

				index.scanDocuments(from, wanted, document -> {
					mapper.writeValue(generator, document);
					generator.writeRaw('\n');
				});
			}
		};

		return Response.ok(body)
			.header(FreshnessTokens.HEADER, FreshnessTokens.encode(freshnessWaiter.stateOf(index)))
			.build();
	}

	/**
	 * The token the request carries in its header, or {@code null} when it
	 * carries none.
	 */
	private String freshnessHeader() {
		return headers == null ? null : headers.getHeaderString(FreshnessTokens.HEADER);
	}

	/**
	 * Read how many documents a request asked for.
	 */
	private static int scanLimit(String limit) {
		if(limit == null) {
			return SCAN_DEFAULT_LIMIT;
		}

		int value;
		try {
			value = Integer.parseInt(limit);
		} catch(NumberFormatException e) {
			value = 0;
		}

		if(value < 1 || value > SCAN_MAX_LIMIT) {
			throw new ValidationException(
				SCAN_LIMIT_INVALID.toMessage(
					Location.create("limit"),
					"value", limit,
					"max", SCAN_MAX_LIMIT
				)
			);
		}

		return value;
	}

	/**
	 * Read the key a request asked to carry on after as the type of the key
	 * field, {@code null} for a request that starts at the first document.
	 */
	private static Object scanAfter(Index index, String after) {
		return after == null ? null : index.parsePrimaryKey(after);
	}

	/**
	 * Get the key of a document, as the request that carries on after it
	 * writes it.
	 */
	private static String keyOf(Index index, Document document) {
		return keyText(document.get(index.getPrimaryKey().orElseThrow().getName()));
	}

	/**
	 * Read whether there is another document to index, saying where the batch
	 * had got to when the answer itself fails.
	 */
	private static boolean hasNext(
		MappingIterator<Map<String, Object>> documents,
		int position,
		int processed
	) {
		try {
			return documents.hasNextValue();
		} catch(JacksonException e) {
			throw malformed(e, position, processed);
		} catch(IOException e) {
			throw unreadable(e, position, processed);
		}
	}

	/**
	 * Say that a newline delimited body could not be read as JSON. Only such a
	 * body reads documents one at a time, so the value is placed as one of a
	 * body with no wrapper, and the line is the one the reader stopped on rather
	 * than the one the value started on.
	 */
	private static ValidationException malformed(
		JacksonException e,
		int position,
		int processed
	) {
		/*
		 * A body the node refused for its size stops the reader the same way a
		 * body that ran out does, and Jackson reports both as JSON it could not
		 * read. The refusal is the node's answer, not the caller's mistake.
		 */
		var refused = RequestBodyTooLargeException.wrappedIn(e);
		if(refused != null) {
			throw tooLarge(refused, position, processed);
		}

		var entry = BatchEntry.onLine(position, lineOf(e));

		return new ValidationException(
			MALFORMED.toMessage(
				entry.location(),
				entry.reported(processed).withKeyValue("reason", e.getOriginalMessage())
			)
		);
	}

	/**
	 * Say that the body stopped arriving part way through a streamed batch,
	 * carrying how far the batch got so the caller can send the rest.
	 */
	private static RequestBodyUnreadableException unreadable(
		IOException e,
		int position,
		int processed
	) {
		return new RequestBodyUnreadableException(
			e,
			"position", position,
			"processed", processed
		);
	}

	/**
	 * Say that a streamed body passed the size the node accepts, carrying how
	 * far the batch got so the caller can send the rest.
	 */
	private static RequestBodyTooLargeException tooLarge(
		RequestBodyTooLargeException e,
		int position,
		int processed
	) {
		return new RequestBodyTooLargeException(
			e,
			"position", position,
			"processed", processed
		);
	}

	/**
	 * The line the value about to be read starts on, counted from one.
	 * {@link MappingIterator#hasNextValue()} leaves the reader on the first
	 * token of the value, so where that token sits is where the value begins.
	 */
	private static int lineOf(MappingIterator<Map<String, Object>> documents) {
		return documents.getParser().currentTokenLocation().getLineNr();
	}

	/**
	 * The line a body stopped being readable on, or {@code null} for a failure
	 * that names no place in the body.
	 */
	private static Integer lineOf(JacksonException e) {
		var location = e.getLocation();

		return location == null ? null : location.getLineNr();
	}

	/**
	 * Index one document of the request, reporting what is wrong with it as
	 * problems of the request rather than of the document on its own.
	 *
	 * @param index
	 * @param name
	 * @param json
	 * @param entry
	 *   where in the request the document sits, which is what the errors of
	 *   the document are placed under and say about the batch
	 * @param processed
	 *   how many documents of the batch the index has taken before this one
	 */
	private static void addDocument(
		Index index,
		String name,
		Map<String, Object> json,
		BatchEntry entry,
		int processed
	) {
		var reported = entry.reported(processed);

		if(json == null) {
			throw new ValidationException(
				NOT_AN_OBJECT.toMessage(entry.location(), reported)
			);
		}

		try {
			index.addDocument(DocumentMapper.toEngine(index, json));
		} catch(ValidationException e) {
			throw new ValidationException(e.getErrors().collect(
				error -> error.at(at(entry.location(), error.getLocation())).with(reported)
			));
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, reported.withKeyValue("index", name), e);
		}
	}

	/**
	 * Where one entry of a batch sits in the body that carried it, and what an
	 * error about that entry says about the batch around it.
	 *
	 * <p>A body with a {@code documents} array places the third entry at
	 * {@code documents[2]} and has no line to name. A newline delimited body has
	 * no wrapper to name, so the third entry is placed at {@code [2]} and also
	 * carries the line it starts on. The position and the line count different
	 * things, and a body that spreads an entry over several lines or separates
	 * entries with blank lines has more lines than entries.
	 *
	 * @param location
	 *   where the errors of the entry are placed
	 * @param position
	 *   which entry of the batch this is, counted from zero
	 * @param line
	 *   the line of the body the entry starts on, counted from one, or
	 *   {@code null} where the body has no line to give
	 */
	private record BatchEntry(ObjectLocation location, int position, Integer line) {
		/**
		 * Place one entry of a body that carries the documents in a
		 * {@code documents} array, so the third entry reads
		 * {@code documents[2]}.
		 */
		static BatchEntry inDocuments(int position) {
			return new BatchEntry(
				ObjectLocation.root().forField("documents").forIndex(position),
				position,
				null
			);
		}

		/**
		 * Place one entry of a newline delimited body, which carries the
		 * documents one value at a time and has no wrapper to name, so the third
		 * entry reads {@code [2]}.
		 */
		static BatchEntry onLine(int position, Integer line) {
			return new BatchEntry(ObjectLocation.root().forIndex(position), position, line);
		}

		/**
		 * What an error about this entry says about the batch it came from:
		 * where the entry sat, and how many documents the index had taken before
		 * it. A caller that has to send the rest of the batch again resumes at
		 * {@code position}, and knows from {@code processed} what already
		 * landed.
		 *
		 * @param processed
		 * @return
		 *   the arguments, which the caller adds its own to
		 */
		MutableMap<String, Object> reported(int processed) {
			var arguments = Maps.mutable.<String, Object>of(
				"position", position,
				"processed", processed
			);

			if(line != null) {
				arguments.put("line", line);
			}

			return arguments;
		}
	}

	/**
	 * Report one entry of a batch the index refused, for a request that carries
	 * on past it.
	 */
	private static DocumentFailure toFailure(BatchEntry entry, ValidationException e) {
		return new DocumentFailure(
			entry.position(),
			entry.line(),
			EngineExceptionMapper.toDetails(e.getErrors())
		);
	}

	/**
	 * Place something said about a document inside the request that carried
	 * it, so {@code name} of the third document of a {@code documents} array
	 * reads {@code documents[2].name}.
	 *
	 * @param document
	 * @param within
	 * @return
	 */
	private static ObjectLocation at(ObjectLocation document, Location within) {
		var inside = within.describe();

		return inside.isEmpty() ? document : document.forField(inside);
	}
}
