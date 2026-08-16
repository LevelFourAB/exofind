package se.l4.exofind.engine.storage;

/**
 * Where a deployment keeps the things that outlive a node: the indexes, the
 * registry naming them and the keys that reach them.
 *
 * <p>The mode is named in configuration rather than worked out from whether
 * the object storage settings happen to be there. A node meant for a cluster
 * whose storage URL is missing or misspelt looks exactly like a node meant to
 * run alone, and guessing turns that into a node that quietly serves a
 * registry of its own and grants itself the indexer role - where naming the
 * mode makes it refuse to start instead.
 */
public enum StorageMode {
	/**
	 * Everything lives on this node's disk, under the local storage directory.
	 * There is one node and nothing to coordinate with, so the conditional
	 * writes the registry and the key store are built on only have to hold
	 * against this process - which is what {@link StorageDirectoryLock} makes
	 * true.
	 *
	 * <p>Nothing is copied anywhere else: the disk is the deployment, and
	 * losing it loses the indexes and the keys together.
	 */
	LOCAL,

	/**
	 * Everything lives in an S3 compatible bucket that any number of nodes
	 * share. The bucket is the source of truth, a node holds a copy, and the
	 * conditional writes of the manifests, the lease, the registry and the key
	 * store are what keep two nodes from losing one another's work.
	 */
	OBJECT;
}
