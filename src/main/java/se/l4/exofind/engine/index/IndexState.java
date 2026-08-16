package se.l4.exofind.engine.index;

public enum IndexState {
	/**
	 * The index needs to be pulled from the remote to ensure that the
	 * contents are up to date.
	 */
	NEEDS_PULL,
	/**
	 * The index is in a usable state, meaning it is likely up to date but
	 * for read only indexes this is not guaranteed.
	 */
	USABLE,
	/**
	 * The index is modified, meaning it has local changes that are not yet
	 * pushed to the remote.
	 */
	MODIFIED,
	/**
	 * The index is being pulled from the remote. This state is a temporary
	 * state and will be changed to USABLE when the pull is complete.
	 */
	PULLING,
	/**
	 * The index is being pushed to the remote. This state is a temporary
	 * state and will be changed to USABLE when the push is complete.
	 */
	PUSHING,
	/**
	 * The definition of the index needs something this version of the engine
	 * does not have, so it can not be read or written here. Reached when a
	 * newer version wrote the definition, and left behind when this node is
	 * upgraded and pulls again.
	 */
	UNSUPPORTED,
	/**
	 * The Lucene files were created by a version so far back that this build
	 * can no longer open them. Unlike {@link #UNSUPPORTED} this is not fixed by
	 * upgrading the node - a newer build is further from being able to read
	 * them, not closer - so the way out is indexing the documents into a new
	 * generation and promoting it.
	 */
	INCOMPATIBLE,
	/**
	 * The index has been closed on this node and can no longer be used. This
	 * state is final for the instance - the index itself is opened again by
	 * asking for it anew, which creates a fresh instance.
	 */
	CLOSED;

	public boolean canModifyContents() {
		switch(this) {
			case NEEDS_PULL:
			case PULLING:
			case UNSUPPORTED:
			case INCOMPATIBLE:
			case CLOSED:
				return false;
			case USABLE:
			case MODIFIED:
			case PUSHING:
				return true;
			default:
				throw new IllegalStateException("Unknown index state: " + this);
		}
	}
}
