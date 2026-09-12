package se.l4.exofind.engine.reindex;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when the record of a reindex could not be read or written.
 *
 * <p>The request is well formed and the storage behind the record did not
 * answer, which leaves the record exactly as it was. Sending the request again
 * once the storage responds is served, so it is reported the same way as a
 * change to the keys, the registry or the search settings that could not be
 * stored.
 */
public class ReindexStorageException extends EngineException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType IO_ERROR = ErrorType.withCode("reindex:io_error")
		.withMessage("The reindex record could not be read or written");

	public ReindexStorageException(Throwable cause) {
		super(IO_ERROR, ErrorType.toArguments(), cause);
	}
}
