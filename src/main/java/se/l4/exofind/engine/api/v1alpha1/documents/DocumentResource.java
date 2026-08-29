package se.l4.exofind.engine.api.v1alpha1.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
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
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.IndexNoPrimaryKeyException;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.reindex.ReindexJobs;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * Putting documents into the indexes on this node and taking them out again.
 *
 * A document carries its own primary key, so indexing one is desired state
 * the way a definition is: the same request can be sent again and leaves the
 * index holding what it says, replacing whatever was indexed under that key
 * before. Removing one is the same kind of statement, which is why a key
 * nothing was indexed under is not an error.
 *
 * A change to some of the fields of a document says something else, which is
 * why it has an endpoint of its own: it describes what to change about a
 * document rather than what should be there, so it needs the document to
 * already be there and is refused when it is not.
 *
 * What is indexed or removed takes effect for searches when the index is
 * committed, which is also what pushes it to the remote. The indexer commits on
 * its own once enough has been indexed or enough time has passed, and
 * {@code POST /v1alpha1/admin/indexes/{name}/actions/commit} commits whatever is
 * waiting there and then. Loading a dataset is many requests here and one commit
 * at the end, rather than a commit per batch.
 *
 * Documents are taken in the order they were sent, and the first one the
 * index refuses fails the request - the ones before it are already in the
 * index and are committed with everything else. Which document failed is said
 * by the path of the errors, so sending the same request again after fixing
 * it is safe.
 *
 * Reading them back out is the same documents in the other direction: an index
 * that keeps its documents whole answers with them as they were given, in the
 * order of their primary keys, and what comes back is what this endpoint takes
 * back in. That is what lets a new generation be filled from the one it
 * replaces, and what a backup of an index is, without the system the documents
 * first came from. An answer is always bounded, so reading everything is a
 * request per part, each carrying on after the key the one before it ended on.
 *
 * Only the indexer writes, so a request that reaches another node is passed
 * along to the one that does - see {@code IndexerForwardFilter}. Reading is
 * served wherever it lands, from what that node has pulled.
 */
@Tag(
	name = "Documents",
	description = "Reads, creates, updates and deletes the documents of an index.",
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

	public DocumentResource(Indexes indexes, ObjectMapper mapper, ReindexJobs reindexJobs) {
		this.indexes = indexes;
		this.mapper = mapper;
		this.reindexJobs = reindexJobs;
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
	 * Put documents into an index.
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
			document carries its own primary key, so repeating the request \
			replaces whatever was indexed under that key. Documents are taken \
			in the order sent, and the first one the index refuses fails the \
			request - the documents before it stay indexed.

			Send `application/json` with a `documents` array, or \
			`application/x-ndjson` with one document object per line and no \
			outer wrapper. Newline-delimited documents are indexed as they are \
			read, so the size of such a request is bounded by what the \
			connection can carry rather than by what fits in memory - which is \
			what makes it the form to load a dataset with.

			Changes become searchable and replicate to remote storage when the \
			index commits, which the writer does on its own once enough has \
			been indexed or enough time has passed. To commit at once, call \
			`POST /v1alpha1/admin/indexes/{name}/actions/commit`.

			Runs on the node that writes the index; a request that reaches \
			another node is forwarded there. Requires the `documents.write` \
			permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The documents were indexed.",
		content = @Content(schema = @Schema(implementation = DocumentsResponse.class))
	)
	@APIResponse(
		responseCode = "400",
		description = """
			A document was rejected by validation, a line could not be read as \
			JSON (`request:document:malformed`), or the body could not be \
			read. The `path` of each error names the document and field it \
			belongs to, such as `documents[1].nonexistent`.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `documents.write` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = "No index has this name, or the key has no grant covering it.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			No node is available to write the index \
			(`indexer:unavailable`), the index is synchronizing \
			(`index:out-of-date`), this node lost the writer role while \
			serving the request (`index:readonly`), or the generation is \
			being filled by a reindex job (`reindex:target_busy`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = """
			The request was forwarded to the index writer and the writer did \
			not respond (`indexer:unreachable`). Send it again.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = """
			The request raced the index being closed to free local resources \
			(`index:closed`). Sending it again reopens the index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public DocumentsResponse add(
		@Parameter(
			description = """
				Name of the index to write to. To write to one generation, add \
				`@` and the name of the generation, such as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		DocumentsRequest body
	) {
		if(body == null || body.documents() == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var index = writable(name);

		var documents = body.documents();
		for(var i = 0; i < documents.size(); i++) {
			addDocument(index, name, documents.get(i), i);
		}

		return new DocumentsResponse(documents.size());
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

		return new DocumentsResponse(indexed);
	}

	/**
	 * Change some of the fields of documents already in an index, leaving the
	 * rest of each document as it is.
	 *
	 * @param name
	 * @param missing
	 *   what to do about a key nothing is indexed under: {@code fail}, the
	 *   default, or {@code skip} to change the rest and answer with the keys
	 *   that were not there
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
			Changes named fields of documents already in the index, leaving \
			the rest of each document as it is. A field with a value replaces \
			the current value, a field set to `null` clears it, and an omitted \
			field is left alone; locale-specific and object fields are \
			replaced entirely rather than merged.

			Send `application/json` with a `documents` array, or \
			`application/x-ndjson` with one change object per line and no \
			outer wrapper.

			Unlike indexing, this describes a change rather than desired \
			state, so it needs the document to already be there. Several \
			changes to one document in a batch apply in the order given, and \
			the updated document is validated as a whole.

			Requires the `documents.write` permission, an index that declares \
			a primary key, and an index that keeps document sources."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The documents were updated.",
		content = @Content(schema = @Schema(implementation = UpdateResponse.class))
	)
	@APIResponse(
		responseCode = "400",
		description = """
			A change was rejected by validation, a key was not found while \
			`missing` was `fail` (`request:update:not_found`), the index \
			declares no primary key (`index:no_primary_key`), or the index \
			keeps no document sources (`index:source:not_kept`) - resend the \
			whole document in that case.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `documents.write` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = "No index has this name, or the key has no grant covering it.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			No node is available to write the index, the index is \
			synchronizing, or the generation is being filled by a reindex \
			job.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = "The request raced the index being closed. Send it again.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public UpdateResponse update(
		@Parameter(
			description = """
				Name of the index to write to, optionally naming one \
				generation as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				What to do about a key nothing is indexed under: `fail` fails \
				the request, `skip` changes the rest and lists the keys that \
				were not there under `missing`.""",
			schema = @Schema(enumeration = {"fail", "skip"}, defaultValue = "fail")
		)
		@QueryParam("missing") String missing,
		UpdateRequest body
	) {
		if(body == null || body.documents() == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var index = writable(name);
		var skipMissing = skipMissing(missing);
		var missingKeys = Lists.mutable.empty();

		var documents = body.documents();
		var updated = 0;
		for(var i = 0; i < documents.size(); i++) {
			if(updateDocument(index, name, documents.get(i), i, skipMissing, missingKeys)) {
				updated++;
			}
		}

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

		var read = 0;
		var updated = 0;

		try(var documents = mapper.readerFor(Map.class).<Map<String, Object>>readValues(body)) {
			while(hasNext(documents, read)) {
				if(updateDocument(index, name, documents.next(), read, skipMissing, missingKeys)) {
					updated++;
				}

				read++;
			}
		} catch(JacksonException e) {
			throw malformed(e, read);
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

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
	 * Remove the document indexed under a primary key.
	 *
	 * The key arrives as text and is read as the type of the key field, so a
	 * numeric key is written into the path the way it is written in a document.
	 *
	 * @param name
	 * @param key
	 * @return
	 *   no content, whether or not anything was indexed under the key - a
	 *   removal is desired state, so repeating it changes nothing
	 */
	@DELETE
	@Path("/{key}")
	@RequiresPermission(Permission.DOCUMENTS_DELETE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "deleteDocument",
		summary = "Delete a document by key",
		description = """
			Removes the document indexed under a primary key. A removal is a \
			statement of desired state, so a key nothing was indexed under is \
			not an error and answers `204` all the same.

			Requires the `documents.delete` permission and an index that \
			declares a primary key."""
	)
	@APIResponse(
		responseCode = "204",
		description = """
			Nothing is indexed under the key any more, whether or not anything \
			was."""
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The key cannot be read as the type of the key field \
			(`index:query:invalid_value`), or the index declares no primary \
			key (`index:no_primary_key`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `documents.delete` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = "No index has this name, or the key has no grant covering it.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			No node is available to write the index, the index is \
			synchronizing, or the generation is being filled by a reindex \
			job.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = "The request raced the index being closed. Send it again.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public Response delete(
		@Parameter(
			description = """
				Name of the index to write to, optionally naming one \
				generation as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Primary key of the document to remove, read as the type of the \
				key field - so a numeric key is written the way a document \
				writes it.""",
			example = "1"
		)
		@PathParam("key") String key
	) {
		var index = writable(name);

		try {
			index.deleteDocument(index.parsePrimaryKey(key));
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		return Response.noContent().build();
	}

	/**
	 * Remove documents from an index, naming them by their primary keys or by
	 * a query they match.
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
			Removes documents named either by a list of primary keys or by a \
			query they match. The body must carry exactly one of the two.

			Deleting by `keys` validates every key before removing anything, \
			so an invalid key removes nothing. Deleting by `query` removes the \
			matching committed searchable documents along with any uncommitted \
			ones indexed since the last commit.

			Requires the `documents.delete` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The documents were removed.",
		content = @Content(schema = @Schema(implementation = DeleteResponse.class))
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The body names neither `keys` nor `query` \
			(`request:delete:target_required`), names both \
			(`request:delete:target_conflicting`), carries a `locale` without \
			a `query` (`request:delete:locale_without_query`), holds a key \
			that cannot be read as the key field's type, or holds a query the \
			index cannot answer.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `documents.delete` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = "No index has this name, or the key has no grant covering it.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			No node is available to write the index, the index is \
			synchronizing, or the generation is being filled by a reindex \
			job.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = "The request raced the index being closed. Send it again.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public DeleteResponse delete(
		@Parameter(
			description = """
				Name of the index to write to, optionally naming one \
				generation as `books@2`.""",
			example = "books"
		)
		@PathParam("name") String name,
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

		try {
			if(body.keys() != null) {
				return new DeleteResponse(index.deleteDocuments(toKeys(body.keys())));
			}

			return new DeleteResponse(
				index.deleteByQuery(
					SearchRequestMapper.toQuery(body.query(), "/query"),
					body.locale()
				)
			);
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}
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
	 * Read documents back out of an index, in the order of their primary keys.
	 *
	 * @param name
	 * @param after
	 *   the key to carry on after, which is not itself answered - left out to
	 *   start at the first document. Written the way it is written in the path
	 *   of a removal, so a numeric key is written as a number
	 * @param limit
	 *   how many documents to answer at most
	 * @return
	 *   the documents, with the key to carry on after when there may be more
	 */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.DOCUMENTS_READ)
	@Operation(
		operationId = "readDocuments",
		summary = "Read documents",
		description = """
			Reads documents back out of an index in primary key order, \
			formatted as they were indexed - so what comes back is what the \
			indexing endpoint takes back in. Whole-number keys come back in \
			numeric order with negative numbers first, and text keys in UTF-8 \
			byte order.

			`Accept` selects the format. `application/json` is the default, \
			and also what `*/*` gets; `application/x-ndjson` answers one \
			document per line with no outer wrapper, matching byte for byte \
			what the indexing endpoint accepts - which is what lets a \
			generation be filled from the one it replaces. A newline-delimited \
			body says nothing but the documents, so what says there may be \
			more is the line count rather than a `next` key.

			Every response is bounded, so reading a whole index is a sequence \
			of requests, each passing the previous response's `next` as \
			`after`. A single request reads from a point-in-time snapshot and \
			sees committed data only; across requests, documents indexed under \
			keys the read has already passed are not returned.

			Served by whichever node receives the request, from what that node \
			has pulled - reads are never forwarded to the writer. Requires the \
			`documents.read` permission, which the `writer` and `admin` roles \
			include and the `reader` role does not."""
	)
	@APIResponse(
		responseCode = "200",
		description = "The documents, in primary key order.",
		content = @Content(schema = @Schema(implementation = ScanResponse.class))
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The index declares no primary key (`index:no_primary_key`), the \
			index keeps no document sources (`index:source:not_kept`), or \
			`limit` is not a whole number from 1 to 10000 \
			(`request:scan:limit_invalid`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `documents.read` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = "No index has this name, or the key has no grant covering it.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "503",
		description = "The request raced the index being closed. Send it again.",
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
				Primary key to resume reading after, which is itself left out \
				of the response. Written the way the key appears in the path \
				of a delete, so a numeric key is written as a number. If \
				nothing is indexed under it, reading resumes where the key \
				would sit in the order. Omit to start at the first document."""
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
	 * Read documents back out of an index as newline delimited JSON, one
	 * document per line - what the same index, or one being filled to replace
	 * it, takes back as it stands.
	 *
	 * <p>The body says nothing but the documents, so what says there may be
	 * more is the count: a request that answered with as many documents as it
	 * asked for is carried on from the key of the last of them.
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
