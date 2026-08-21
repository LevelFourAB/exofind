package se.l4.exofind.engine.api.v1alpha1.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DeleteRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DeleteResponse;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsResponse;
import se.l4.exofind.engine.api.v1alpha1.documents.model.UpdateRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.UpdateResponse;
import se.l4.exofind.engine.api.v1alpha1.search.SearchRequestMapper;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.Location;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.DocumentPatch;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
 * Only the indexer writes, so a request that reaches another node is passed
 * along to the one that does - see {@code IndexerForwardFilter}.
 */
@Path("/v1alpha1/indexes/{name}/documents")
@Produces(MediaType.APPLICATION_JSON)
public class DocumentResource {
	/**
	 * Media type of newline delimited JSON, one document per line - what a
	 * dataset too large to hold in memory is sent as.
	 */
	public static final String NDJSON = "application/x-ndjson";

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

	public DocumentResource(Indexes indexes, ObjectMapper mapper) {
		this.indexes = indexes;
		this.mapper = mapper;
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
	public DocumentsResponse add(@PathParam("name") String name, DocumentsRequest body) {
		if(body == null || body.documents() == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var index = indexes.getOrThrow(name);

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
	public DocumentsResponse addStream(@PathParam("name") String name, InputStream body) {
		if(body == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var index = indexes.getOrThrow(name);
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
	public UpdateResponse update(
		@PathParam("name") String name,
		@QueryParam("missing") String missing,
		UpdateRequest body
	) {
		if(body == null || body.documents() == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var index = indexes.getOrThrow(name);
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
	public UpdateResponse updateStream(
		@PathParam("name") String name,
		@QueryParam("missing") String missing,
		InputStream body
	) {
		if(body == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var index = indexes.getOrThrow(name);
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
	public Response delete(@PathParam("name") String name, @PathParam("key") String key) {
		var index = indexes.getOrThrow(name);

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
	public DeleteResponse delete(@PathParam("name") String name, DeleteRequest body) {
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

		var index = indexes.getOrThrow(name);

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
