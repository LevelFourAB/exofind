package se.l4.exofind.engine.index.state;

import java.io.IOException;

/**
 * Thrown when the remote describes an index whose Lucene files were created too
 * far back for this build to open. Thrown while pulling, before any of the
 * files are downloaded, because downloading them would only spend the transfer
 * to arrive at the same refusal locally.
 *
 * Unlike most failures a pull can hit this one does not pass with time or a
 * retry, and upgrading the node moves away from being able to read the index
 * rather than towards it. Reindexing the documents into a new index is what
 * resolves it, so the caller is expected to stop pulling rather than try again.
 */
public class SyncIncompatibleException extends IOException {
	private final int createdMajor;

	public SyncIncompatibleException(String message, int createdMajor) {
		super(message);

		this.createdMajor = createdMajor;
	}

	/**
	 * The major Lucene version the index was created with.
	 *
	 * @return
	 */
	public int getCreatedMajor() {
		return createdMajor;
	}
}
