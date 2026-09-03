package se.l4.exofind.engine.api.errors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.map.MapIterable;

import se.l4.exofind.engine.auth.ForbiddenException;
import se.l4.exofind.engine.auth.KeyNotFoundException;
import se.l4.exofind.engine.auth.KeyStorageException;
import se.l4.exofind.engine.auth.UnauthenticatedException;
import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.IndexClosedException;
import se.l4.exofind.engine.index.IndexDefinitionIncompatibleException;
import se.l4.exofind.engine.index.IndexDocumentNotFoundException;
import se.l4.exofind.engine.index.IndexFieldNotFoundException;
import se.l4.exofind.engine.index.IndexFieldUsageException;
import se.l4.exofind.engine.index.IndexInvalidCursorException;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.IndexNoLiveGenerationException;
import se.l4.exofind.engine.index.IndexStorageHeldException;
import se.l4.exofind.engine.index.IndexNoPrimaryKeyException;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.IndexOutOfDateException;
import se.l4.exofind.engine.index.IndexReadonlyException;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.index.IndexSourceRequiredException;
import se.l4.exofind.engine.index.IndexUnsupportedException;
import se.l4.exofind.engine.index.IndexVersionMismatchException;
import se.l4.exofind.engine.index.SearchTimeoutException;
import se.l4.exofind.engine.index.registry.RegistryAuditUnavailableException;
import se.l4.exofind.engine.index.registry.RegistryException;
import se.l4.exofind.engine.index.settings.SearchSettingsException;
import se.l4.exofind.engine.index.settings.SearchSettingsNotFoundException;
import se.l4.exofind.engine.index.settings.SearchSettingsVersionMismatchException;
import se.l4.exofind.engine.index.state.IndexerLeadershipUnreadableException;
import se.l4.exofind.engine.index.state.IndexerUnavailableException;
import se.l4.exofind.engine.index.state.IndexerUnreachableException;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.reindex.ReindexInProgressException;
import se.l4.exofind.engine.reindex.ReindexNotFoundException;
import se.l4.exofind.engine.reindex.ReindexTargetBusyException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Turns the exceptions of the engine into {@link ErrorResponse}.
 *
 * Mapping every {@link EngineException} in one place keeps the codes and the
 * status they map to in a single list, and means a new exception gets a usable
 * response - with its code and message - without any work here.
 */
@Provider
public class EngineExceptionMapper implements ExceptionMapper<EngineException> {
	private static final Log logger = Log.of(EngineExceptionMapper.class);

	private final RequestMetrics metrics;

	public EngineExceptionMapper(RequestMetrics metrics) {
		this.metrics = metrics;
	}

	@Override
	public Response toResponse(EngineException e) {
		var status = statusOf(e);

		/*
		 * Counted by code rather than by status, since the code names what went
		 * wrong and several of them share a status.
		 */
		metrics.recordError(e.getCode());

		if(status.getStatusCode() >= 500) {
			logger.atError()
				.addKeyValue("code", e.getCode())
				.setCause(e)
				.log("Request failed; " + e.getMessage());
		}

		var response = Response.status(status)
			.type(MediaType.APPLICATION_JSON)
			.entity(toBody(e));

		if(e instanceof UnauthenticatedException) {
			/*
			 * Says which scheme to present a credential under, which is what
			 * makes a 401 answerable rather than only a refusal.
			 */
			response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}

		return response.build();
	}

	private static Response.Status statusOf(EngineException e) {
		if(e instanceof UnauthenticatedException) {
			/*
			 * No credential this node accepts. Kept apart from being refused
			 * below, because presenting a different credential is what would
			 * change the answer.
			 */
			return Response.Status.UNAUTHORIZED;
		} else if(e instanceof ForbiddenException) {
			/*
			 * A known caller reaching something they were not granted. An index
			 * they were granted nothing at all on never gets here - that is
			 * answered as a missing index, so a refusal can not be used to find
			 * out what a deployment holds.
			 */
			return Response.Status.FORBIDDEN;
		} else if(e instanceof KeyNotFoundException) {
			return Response.Status.NOT_FOUND;
		} else if(e instanceof KeyStorageException) {
			/*
			 * The change to the keys is well formed but could not be stored,
			 * which leaves the keys exactly as they were.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof SearchSettingsNotFoundException) {
			return Response.Status.NOT_FOUND;
		} else if(e instanceof SearchSettingsVersionMismatchException) {
			// The settings changed after the caller read them
			return Response.Status.PRECONDITION_FAILED;
		} else if(e instanceof SearchSettingsException) {
			/*
			 * The change to the settings is well formed but could not be stored,
			 * which leaves the stored settings exactly as they were.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof IndexDefinitionIncompatibleException) {
			/*
			 * The definition is well formed - an empty generation takes the same
			 * one - and disagrees with the documents the generation holds, so it
			 * is the state and the request that conflict. Checked before
			 * ValidationException, whose errors it carries.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof ValidationException) {
			return Response.Status.BAD_REQUEST;
		} else if(e instanceof IndexFieldNotFoundException
			|| e instanceof IndexFieldUsageException
			|| e instanceof IndexSourceRequiredException
			|| e instanceof IndexSourceNotKeptException
			|| e instanceof IndexInvalidQueryTypeException
			|| e instanceof IndexInvalidQueryValueException
			|| e instanceof IndexInvalidCursorException) {
			/*
			 * The query asks the index for something it does not have, which
			 * is the caller's to fix - the arguments the exception carries say
			 * which field and how.
			 */
			return Response.Status.BAD_REQUEST;
		} else if(e instanceof IndexNoPrimaryKeyException) {
			/*
			 * Naming a document by a key in an index that has none is the
			 * caller asking for something the definition never set up, the same
			 * as using a field in a way it was not defined for.
			 */
			return Response.Status.BAD_REQUEST;
		} else if(e instanceof IndexNotFoundException) {
			return Response.Status.NOT_FOUND;
		} else if(e instanceof IndexDocumentNotFoundException) {
			/*
			 * A request that named one document by its key and the index holds
			 * none - the key in the path names nothing, the same as a name no
			 * index answers to.
			 */
			return Response.Status.NOT_FOUND;
		} else if(e instanceof IndexVersionMismatchException) {
			// The definition changed after the caller read it
			return Response.Status.PRECONDITION_FAILED;
		} else if(e instanceof IndexReadonlyException || e instanceof IndexOutOfDateException) {
			/*
			 * The request is well formed but the index can not be modified
			 * right now, either because this node is not the indexer or
			 * because the index is being synchronized.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof IndexerUnavailableException) {
			/*
			 * The request needed the indexer and there was none to pass it to.
			 * Sent again once one is up it is served, so the deployment is
			 * what has to change rather than the request.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof IndexerUnreachableException) {
			// This node relayed the request and the node behind it did not answer
			return Response.Status.BAD_GATEWAY;
		} else if(e instanceof IndexerLeadershipUnreadableException) {
			/*
			 * Who writes which index is kept in the storage and could not be
			 * read. Asking again once it can be reached is answered, so the
			 * caller is told to retry rather than that something is wrong.
			 */
			return Response.Status.SERVICE_UNAVAILABLE;
		} else if(e instanceof UnrepresentableStateException) {
			/*
			 * The index holds state that belongs to another version of the API.
			 * Nothing about the request can be changed to make this one able to
			 * serve it, so it is the state and the request that conflict rather
			 * than the request being wrong.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof IndexUnsupportedException) {
			/*
			 * The index says it needs engine features this node does not have,
			 * so this node refuses to resolve its name. Like an unrepresentable
			 * definition, nothing about the request changes the answer - it is
			 * the node that is too old.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof IndexNoLiveGenerationException) {
			/*
			 * The index exists but answers for none of its generations, which
			 * is fixed by promoting one rather than by changing the request.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof IndexStorageHeldException) {
			/*
			 * The storage holds data under the name being created that nothing
			 * said was deleted. The request is fine; the storage has to be
			 * repaired or cleared first.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof RegistryException) {
			/*
			 * The change is well formed but could not be stored, which leaves
			 * the indexes exactly as they were.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof RegistryAuditUnavailableException) {
			/*
			 * The node stores locally, so there is no storage to compare the
			 * registry with. Nothing about the request changes the answer -
			 * it is the deployment that has no audit to give.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof ReindexNotFoundException) {
			return Response.Status.NOT_FOUND;
		} else if(e instanceof ReindexInProgressException
			|| e instanceof ReindexTargetBusyException) {
			/*
			 * The request is well formed but collides with a reindex that is
			 * running - it is served once the job finishes or is cancelled,
			 * so the state is what has to change rather than the request.
			 */
			return Response.Status.CONFLICT;
		} else if(e instanceof SearchTimeoutException) {
			/*
			 * The search is well formed and the node could not answer it in the
			 * time it allows. A narrower search is answered, and so is the same
			 * one on a node under less load. The caller is told to retry rather
			 * than that something is wrong.
			 */
			return Response.Status.SERVICE_UNAVAILABLE;
		} else if(e instanceof IndexClosedException) {
			/*
			 * The request raced the index being closed to make room on this
			 * node. Repeating the request opens the index again, so the caller
			 * is told to retry rather than that something is wrong.
			 */
			return Response.Status.SERVICE_UNAVAILABLE;
		}

		return Response.Status.INTERNAL_SERVER_ERROR;
	}

	private static ErrorResponse toBody(EngineException e) {
		if(e instanceof ValidationException validation) {
			var errors = validation.getErrors();

			return new ErrorResponse(
				e.getCode(),
				errors.size() == 1
					? errors.get(0).getMessage()
					: "Request contains " + errors.size() + " errors",
				errors.collect(EngineExceptionMapper::toDetail).toList()
			);
		}

		var detail = new ErrorResponse.ErrorDetail(
			e.getCode(),
			e.getMessage(),
			null,
			toArguments(e.getArguments())
		);

		return new ErrorResponse(e.getCode(), e.getMessage(), List.of(detail));
	}

	private static ErrorResponse.ErrorDetail toDetail(ErrorMessage message) {
		var path = message.getLocation().describe();

		return new ErrorResponse.ErrorDetail(
			message.getCode(),
			message.getMessage(),
			path.isEmpty() ? null : path,
			toArguments(message.getArguments())
		);
	}

	/**
	 * Render the arguments of an error as strings. Arguments exist so callers
	 * can build their own message from the code, which does not need the
	 * types the engine happens to use internally.
	 *
	 * @param arguments
	 * @return
	 */
	private static Map<String, String> toArguments(MapIterable<String, Object> arguments) {
		if(arguments.isEmpty()) {
			return null;
		}

		var result = new LinkedHashMap<String, String>();
		for(var entry : arguments.keyValuesView()) {
			result.put(entry.getOne(), String.valueOf(entry.getTwo()));
		}

		return result;
	}
}
