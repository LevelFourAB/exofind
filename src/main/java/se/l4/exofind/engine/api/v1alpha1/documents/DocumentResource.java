package se.l4.exofind.engine.api.v1alpha1.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
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
import se.l4.exofind.engine.api.errors.ErrorResponse;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DeleteRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DeleteResponse;
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
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.DocumentPatch;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexDocumentNotFoundException;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.IndexNoPrimaryKeyException;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.reindex.ReindexJobs;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
 * <p>Documents in a batch are processed in the order sent. The first invalid
 * document halts processing and fails the request; documents processed before
 * the failure remain in the index and commit with the rest. Error paths
 * identify which document failed, allowing you to safely resend the request
 * after fixing the error.
 *
 * <p>Reading documents returns them in primary key order, formatted as
 * originally indexed, matching the format accepted for indexing. This allows
 * you to populate a new generation from the one it replaces or create backups
 * without the originating source system. Responses are always bounded, so
 * reading an entire index requires a sequence of requests, each resuming after
 * the primary key returned by the previous request.
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
	 * dataset too large to hold in memory is sent as.
	 */
	public static final String NDJSON = "application/x-ndjson";

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

	private static final ErrorType MISSING_BODY = ErrorType.withCode("request:missing_body")
		.withMessage("Documents are required");

	private static final ErrorType NOT_AN_OBJECT = ErrorType
		.withCode("request:document:not_an_object")
		.withMessage("A document has to be an object, keyed by field name");

	private static final ErrorType MALFORMED = ErrorType.withCode("request:document:malformed")
		.withArguments("reason")
		.withMessage("The document could not be read as JSON: {{reason}}");

	private static final ErrorType IO_ERROR = ErrorType.withCode("index:io_error")
		.withArguments("index")
		.withMessage("The index `{{index}}` could not be updated on disk");

	private static final ErrorType READ_ERROR = ErrorType.withCode("index:io_error")
		.withArguments("index")
		.withMessage("The index `{{index}}` could not be read from disk");

	private static final ErrorType SCAN_LIMIT_INVALID =
		ErrorType.withCode("request:scan:limit_invalid")
			.withArguments("value", "max")
			.withMessage(
				"A limit is a whole number from 1 to {{max}}, which `{{value}}` is not"
			);

	private static final ErrorType UPDATE_MISSING_UNKNOWN =
		ErrorType.withCode("request:update:missing_unknown")
			.withArguments("value")
			.withMessage(
				"A key nothing is indexed under is handled by `fail` or `skip`, not by `{{value}}`"
			);

	private static final ErrorType UPDATE_NOT_FOUND =
		ErrorType.withCode("request:update:not_found")
			.withArguments("key")
			.withMessage(
				"Nothing is indexed under the key `{{key}}`, so there is nothing to change"
			);

	private static final ErrorType CHANGE_MISSING_BODY = ErrorType
		.withCode("request:missing_body")
		.withMessage("A change is required");

	private static final ErrorType UPDATE_KEY_CONFLICTING =
		ErrorType.withCode("request:update:key_conflicting")
			.withArguments("key", "name")
			.withMessage(
				"The document to change is the one the path names, `{{key}}`, so `{{name}}` in the body cannot name another"
			);

	private static final ErrorType DELETE_TARGET_REQUIRED =
		ErrorType.withCode("request:delete:target_required")
			.withMessage("Documents are removed by `keys` or by `query`, and one is required");

	private static final ErrorType DELETE_TARGET_CONFLICTING =
		ErrorType.withCode("request:delete:target_conflicting")
			.withMessage("Documents are removed by `keys` or by `query`, not by both");

	private static final ErrorType DELETE_LOCALE_WITHOUT_QUERY =
		ErrorType.withCode("request:delete:locale_without_query")
			.withMessage("A locale says how to match a `query`, which this request does not have");

	private static final ErrorType DELETE_KEY_REQUIRED =
		ErrorType.withCode("request:delete:key_required")
			.withMessage("A key is required");

	private final Indexes indexes;
	private final ObjectMapper mapper;
	private final ReindexJobs reindexJobs;
	private final RequestMetrics metrics;

	/**
	 * Write documents that report nothing, for a test that is not measuring.
	 */
	public DocumentResource(Indexes indexes, ObjectMapper mapper, ReindexJobs reindexJobs) {
		this(indexes, mapper, reindexJobs, RequestMetrics.none());
	}

	@Inject
	public DocumentResource(
		Indexes indexes,
		ObjectMapper mapper,
		ReindexJobs reindexJobs,
		RequestMetrics metrics
	) {
		this.indexes = indexes;
		this.mapper = mapper;
		this.reindexJobs = reindexJobs;
		this.metrics = metrics;
	}

	/**
	 * The index a write may go to. A generation a reindex is filling is
	 * refused - what lands in it has to come from the job alone, or the
	 * job's replay would overwrite it.
	 */
	private Index writable(String name) {
		reindexJobs.checkTargetWritable(name);
		return indexes.getOrThrow(name);
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
			a batch are processed in the order sent. The first invalid \
			document halts processing and fails the request; documents \
			processed before the failure remain in the index.

			Format the request body as `application/json` with a `documents` \
			array, or `application/x-ndjson` with one document object per line \
			and no outer wrapper. Newline-delimited documents are indexed as \
			they are read, so request size is bounded by network capacity \
			rather than available memory, making it suitable for loading large \
			datasets.

			Changes become searchable and replicate to remote storage after \
			the index commits. The writer commits automatically based on \
			indexed document volume or elapsed time. To commit changes \
			immediately, call `POST \
			/v1alpha1/admin/indexes/{name}/actions/commit`.

			The operation runs on the index writer node. A write request \
			received by another node is forwarded automatically. Requires the \
			`documents.write` permission."""
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
			JSON (`request:document:malformed`), or the request body could not \
			be parsed. The `path` of each error identifies the document and \
			field location, such as `documents[1].nonexistent`.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key lacks the `documents.write` permission.",
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
		description = """
			No node is available to write the index (`indexer:unavailable`), \
			the index is currently synchronizing (`index:out-of-date`), the \
			node lost the writer role during execution (`index:readonly`), or \
			the target generation is locked by an active reindex job \
			(`reindex:target_busy`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = """
			The request was forwarded to the index writer and the writer did \
			not respond (`indexer:unreachable`). Retrying the same request is \
			expected to work.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced an index being closed to free local resources on \
			the node (`index:closed`). Retrying the request reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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

		var index = writable(name);
		var documents = body.documents();

		return new DocumentsResponse(measure("add", () -> {
			for(var i = 0; i < documents.size(); i++) {
				addDocument(index, name, documents.get(i), i);
			}

			return documents.size();
		}));
	}

	/**
	 * Put documents into an index, one JSON object per line. The documents
	 * are indexed as they are read, so the size of the request is what the
	 * connection can carry rather than what fits in memory.
	 *
	 * @param name
	 * @param body
	 * @return
	 */
	@POST
	@Consumes(NDJSON)
	@RequiresPermission(Permission.DOCUMENTS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	/*
	 * Carries no OpenAPI annotations of its own. It shares a path and method
	 * with the endpoint above, so the two are one operation in the document -
	 * what this one contributes is the media type it consumes, which comes
	 * from @Consumes. Describing it separately here would be dropped in the
	 * merge, so what it does is said in that operation's description instead.
	 */
	public DocumentsResponse addStream(@PathParam("name") String name, InputStream body) {
		if(body == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var index = writable(name);

		return new DocumentsResponse(measure("add", () -> {
			var indexed = 0;

			try(var documents = mapper.readerFor(Map.class).<Map<String, Object>>readValues(body)) {
				while(hasNext(documents, indexed)) {
					addDocument(index, name, documents.next(), indexed);
					indexed++;
				}
			} catch(JacksonException e) {
				throw malformed(e, indexed);
			} catch(IOException e) {
				throw new IndexException(IO_ERROR, e, "index", name);
			}

			return indexed;
		}));
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

			Requires the `documents.write` permission, an index that declares \
			a primary key, and an index that retains document source copies."""
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
			A change failed validation, a document key was not found when \
			`missing` was set to `fail` (`request:update:not_found`), the \
			index definition declares no primary key (`index:no_primary_key`), \
			or the index does not store document copies \
			(`index:source:not_kept`) - resend the complete document in that \
			case.

			A path is rejected when it cannot be parsed \
			(`request:update:path_invalid`), reaches into a field the index \
			does not have (`request:update:path_unknown_field`), names a \
			single value of a field that holds neither locale variants nor \
			objects (`request:update:selector_not_supported`), names a locale \
			the field holds no variant for (`request:update:locale_unknown`), \
			adds a value to a field that holds a single value \
			(`request:update:add_not_multiple`), reaches inside a field whose \
			values are not objects (`request:update:not_an_object`), or \
			reaches into a list of objects without specifying which value \
			(`request:update:value_required`). A selector that names no value \
			the document holds is rejected with `request:update:no_match`.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key lacks the `documents.write` permission.",
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

		var index = writable(name);
		var skipMissing = skipMissing(missing);
		var missingKeys = Lists.mutable.empty();
		var documents = body.documents();

		var updated = measure("update", () -> {
			var changed = 0;
			for(var i = 0; i < documents.size(); i++) {
				if(updateDocument(index, name, documents.get(i), i, skipMissing, missingKeys)) {
					changed++;
				}
			}

			return changed;
		});

		return new UpdateResponse(updated, missingKeys);
	}

	/**
	 * Change some of the fields of documents already in an index, one JSON
	 * object per line.
	 *
	 * @param name
	 * @param missing
	 * @param body
	 * @return
	 */
	@POST
	@Path("/actions/update")
	@Consumes(NDJSON)
	@RequiresPermission(Permission.DOCUMENTS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	/*
	 * Carries no OpenAPI annotations, for the reason the newline delimited
	 * indexing endpoint above does not.
	 */
	public UpdateResponse updateStream(
		@PathParam("name") String name,
		@QueryParam("missing") String missing,
		InputStream body
	) {
		if(body == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var index = writable(name);
		var skipMissing = skipMissing(missing);
		var missingKeys = Lists.mutable.empty();

		var updated = measure("update", () -> {
			var read = 0;
			var changed = 0;

			try(var documents = mapper.readerFor(Map.class).<Map<String, Object>>readValues(body)) {
				while(hasNext(documents, read)) {
					if(updateDocument(index, name, documents.next(), read, skipMissing, missingKeys)) {
						changed++;
					}

					read++;
				}
			} catch(JacksonException e) {
				throw malformed(e, read);
			} catch(IOException e) {
				throw new IndexException(IO_ERROR, e, "index", name);
			}

			return changed;
		});

		return new UpdateResponse(updated, missingKeys);
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
			UPDATE_MISSING_UNKNOWN.toMessage(Location.create("/missing"), "value", missing)
		);
	}

	/**
	 * Apply one change of a request, reporting what is wrong with it as
	 * problems of the request rather than of the change on its own.
	 *
	 * @param position
	 *   where in the request the change sits, which is what its errors are
	 *   placed under
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
		int position,
		boolean skipMissing,
		MutableList<Object> missingKeys
	) {
		if(json == null) {
			throw new ValidationException(
				NOT_AN_OBJECT.toMessage(at(position, ObjectLocation.root()))
			);
		}

		DocumentPatch patch;
		boolean updated;
		try {
			patch = DocumentMapper.toPatch(index, json);
			updated = index.updateDocument(patch);
		} catch(ValidationException e) {
			throw new ValidationException(
				e.getErrors().collect(error -> error.at(at(position, error.getLocation())))
			);
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		if(updated) {
			return true;
		}

		/*
		 * The key is known to be there - a patch without one is refused by the
		 * index before it looks for anything.
		 */
		var key = patch.get(index.getPrimaryKey().orElseThrow().getName());

		if(!skipMissing) {
			throw new ValidationException(
				UPDATE_NOT_FOUND.toMessage(
					at(position, ObjectLocation.root()),
					"key", String.valueOf(key)
				)
			);
		}

		missingKeys.add(key);
		return false;
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

			Requires the `documents.write` permission, an index that declares \
			a primary key, and an index that retains document source copies."""
	)
	@APIResponse(
		responseCode = "204",
		description = "The document was changed."
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The change was rejected by validation, the key value cannot be \
			parsed as the defined key field type \
			(`index:query:invalid_value`), the body names the primary key \
			field as another document than the path does \
			(`request:update:key_conflicting`), the index definition declares \
			no primary key (`index:no_primary_key`), or the index does not \
			store document copies (`index:source:not_kept`) - resend the whole \
			document in that case.

			A path is refused for the same reasons as in batch updates, \
			reported by the same `request:update:*` codes.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key lacks the `documents.write` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			Nothing is indexed under the key (`index:document:not_found`), no \
			index with the specified name exists on this node, or the caller \
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

		var index = writable(name);

		measure("update", () -> {
			var primaryKey = index.parsePrimaryKey(key);
			var keyField = index.getPrimaryKey().orElseThrow().getName();

			var patch = withKey(DocumentMapper.toPatch(index, body), keyField, primaryKey);

			try {
				if(!index.updateDocument(patch)) {
					throw new IndexDocumentNotFoundException(key);
				}
			} catch(IOException e) {
				throw new IndexException(IO_ERROR, e, "index", name);
			}

			return 1;
		});

		return Response.noContent().build();
	}

	/**
	 * Give a patch the primary key the path named, so that the document to
	 * change is the one the path names.
	 *
	 * @throws ValidationException
	 *   if the body names the key field as another document, which would leave
	 *   the path and the body naming two - the merge reads what the path names
	 *   and the write goes where the merged document says
	 */
	private static DocumentPatch withKey(
		DocumentPatch patch,
		String keyField,
		Object primaryKey
	) {
		for(var change : patch.changes()) {
			if(!change.field().equals(keyField)) {
				continue;
			}

			var given = change.values().notEmpty() ? change.values().getFirst().value() : null;
			if(change.inner() != null
				|| !(change.selector() instanceof DocumentPatch.Selector.All)
				|| !String.valueOf(primaryKey).equals(String.valueOf(given))) {
				throw new ValidationException(
					UPDATE_KEY_CONFLICTING.toMessage(
						ObjectLocation.root().forField(keyField),
						"key", String.valueOf(primaryKey),
						"name", keyField
					)
				);
			}

			return patch;
		}

		return new DocumentPatch(
			patch.changes().toList().with(
				new DocumentPatch.Change(
					keyField,
					DocumentPatch.Selector.ALL,
					null,
					Lists.immutable.of(new Document.Value(keyField, primaryKey))
				)
			).toImmutable()
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

			Requires the `documents.delete` permission and an index definition \
			that declares a primary key."""
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
			The key value cannot be parsed as the defined key field type \
			(`index:query:invalid_value`), or the index definition declares no \
			primary key (`index:no_primary_key`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `documents.delete` permission.",
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
		var index = writable(name);

		measure("delete", () -> {
			try {
				index.deleteDocument(index.parsePrimaryKey(key));
			} catch(IOException e) {
				throw new IndexException(IO_ERROR, e, "index", name);
			}

			return 1;
		});

		return Response.noContent().build();
	}

	/**
	 * Deletes documents from an index matching a list of primary keys or a
	 * search query.
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
		summary = "Delete documents by keys or query",
		description = """
			Deletes multiple documents matching a list of primary keys or a \
			search query. The request body must include either `keys` or \
			`query`, but not both.

			When deleting by `keys`, all keys are validated before any \
			documents are removed. If any key is invalid, no documents are \
			removed. When deleting by `query`, the operation removes matching \
			committed searchable documents along with any uncommitted \
			documents indexed since the last commit.

			Requires the `documents.delete` permission."""
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
			The request body includes neither `keys` nor `query` \
			(`request:delete:target_required`), includes both \
			(`request:delete:target_conflicting`), specifies a `locale` \
			without a `query` (`request:delete:locale_without_query`), \
			contains a key that cannot be parsed as the defined key field \
			type, or contains a query the index cannot execute.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `documents.delete` permission.",
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
				)
			}
		))
		DeleteRequest body
	) {
		if(body == null || body.keys() == null && body.query() == null) {
			throw new ValidationException(DELETE_TARGET_REQUIRED.toMessage(Location.create("")));
		}

		if(body.keys() != null && body.query() != null) {
			throw new ValidationException(
				DELETE_TARGET_CONFLICTING.toMessage(Location.create(""))
			);
		}

		if(body.locale() != null && body.query() == null) {
			throw new ValidationException(
				DELETE_LOCALE_WITHOUT_QUERY.toMessage(Location.create("/locale"))
			);
		}

		var index = writable(name);

		if(body.keys() != null) {
			return new DeleteResponse(measure("delete", () -> {
				try {
					return index.deleteDocuments(toKeys(body.keys()));
				} catch(IOException e) {
					throw new IndexException(IO_ERROR, e, "index", name);
				}
			}));
		}

		return new DeleteResponse(measure("delete_by_query", () -> {
			try {
				return index.deleteByQuery(
					SearchRequestMapper.toQuery(body.query(), "/query"),
					body.locale()
				);
			} catch(IOException e) {
				throw new IndexException(IO_ERROR, e, "index", name);
			}
		}));
	}

	/**
	 * Read the keys of a request, saying which of them is missing rather than
	 * removing the ones around it.
	 */
	private static ListIterable<Object> toKeys(List<Object> keys) {
		var errors = Lists.mutable.<ErrorMessage>empty();
		for(var i = 0; i < keys.size(); i++) {
			if(keys.get(i) == null) {
				errors.add(DELETE_KEY_REQUIRED.toMessage(Location.create("/keys/" + i)));
			}
		}

		if(errors.notEmpty()) {
			throw new ValidationException(errors);
		}

		return Lists.immutable.ofAll(keys);
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
			forwarded to the writer. Reading documents requires the \
			`documents.read` permission. The `writer` and `admin` roles \
			include this permission; the `reader` role does not."""
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
			The index definition declares no primary key \
			(`index:no_primary_key`), the index does not store document copies \
			(`index:source:not_kept`), or the `limit` parameter is not a whole \
			number from 1 to 10000 (`request:scan:limit_invalid`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `documents.read` permission.",
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
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources. \
			Repeating the request reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
		var index = indexes.getOrThrow(name);
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
			read < wanted ? null : keyOf(index, documents.getLast())
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
		var index = indexes.getOrThrow(name);
		var wanted = scanLimit(limit);
		var from = scanAfter(index, after);

		/*
		 * What an index cannot be read this way is said before the body
		 * starts, as an answer that has begun can no longer be turned into the
		 * error that stopped it.
		 */
		checkReadable(index);

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

		return Response.ok(body).build();
	}

	/**
	 * Refuse an index that cannot be read back out, for the reasons a scan
	 * itself would refuse it.
	 */
	private static void checkReadable(Index index) {
		if(index.getPrimaryKey().isEmpty()) {
			throw new IndexNoPrimaryKeyException(index.getId());
		}

		if(!index.isSourceStored()) {
			throw new IndexSourceNotKeptException(index.getId());
		}
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
					Location.create("/limit"),
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
		return String.valueOf(document.get(index.getPrimaryKey().orElseThrow().getName()));
	}

	/**
	 * Read whether there is another document to index, saying which line
	 * could not be read when the answer itself fails.
	 */
	private static boolean hasNext(
		MappingIterator<Map<String, Object>> documents,
		int position
	) {
		try {
			return documents.hasNextValue();
		} catch(JacksonException e) {
			throw malformed(e, position);
		} catch(IOException e) {
			throw new IllegalStateException("Unable to read the documents of the request", e);
		}
	}

	private static ValidationException malformed(JacksonException e, int position) {
		return new ValidationException(
			MALFORMED.toMessage(
				at(position, ObjectLocation.root()),
				"reason",
				e.getOriginalMessage()
			)
		);
	}

	/**
	 * Index one document of the request, reporting what is wrong with it as
	 * problems of the request rather than of the document on its own.
	 *
	 * @param index
	 * @param name
	 * @param json
	 * @param position
	 *   where in the request the document sits, which is what the errors of
	 *   the document are placed under
	 */
	private static void addDocument(
		Index index,
		String name,
		Map<String, Object> json,
		int position
	) {
		if(json == null) {
			throw new ValidationException(
				NOT_AN_OBJECT.toMessage(at(position, ObjectLocation.root()))
			);
		}

		try {
			index.addDocument(DocumentMapper.toEngine(index, json));
		} catch(ValidationException e) {
			throw new ValidationException(
				e.getErrors().collect(error -> error.at(at(position, error.getLocation())))
			);
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}
	}

	/**
	 * Place something said about a document inside the request that carried
	 * it, so {@code name} of the third document reads
	 * {@code documents[2].name}.
	 *
	 * @param position
	 * @param within
	 * @return
	 */
	private static ObjectLocation at(int position, Location within) {
		var prefix = "documents[" + position + ']';
		var inside = within.describe();

		return () -> inside.isEmpty() ? prefix : prefix + '.' + inside;
	}
}
