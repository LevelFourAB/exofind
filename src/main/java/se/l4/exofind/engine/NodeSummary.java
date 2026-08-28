package se.l4.exofind.engine;

import java.nio.file.Path;
import java.util.Locale;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.auth.AuthMode;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.storage.ObjectStorage;
import se.l4.exofind.engine.storage.StorageMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

/**
 * Says once, at startup, what this node is: where it keeps indexes, whether it
 * may write them, and what it checks before answering.
 *
 * <p>Each of those is named by a variable of its own, and a node that got one
 * of them wrong goes on looking like a working node until the thing it should
 * have done is refused instead. Naming them together means the answer to what
 * a node is comes out of its log rather than out of whatever started it - which
 * is the copy that is still around when the question is asked months later.
 *
 * <p>Written while the application starts, so it precedes the line reporting
 * the address the node listens on and the log reads as configuration first,
 * then readiness.
 */
@ApplicationScoped
public class NodeSummary {
	private static final Log logger = Log.of(NodeSummary.class);

	private final StorageMode storageMode;
	private final NodeState nodeState;
	private final AuthMode authMode;
	private final Path storageDirectory;
	private final Instance<ObjectStorage> objectStorage;

	NodeSummary(
		StorageMode storageMode,
		NodeState nodeState,
		Instance<ObjectStorage> objectStorage,
		@ConfigProperty(name = "exofind.auth.mode", defaultValue = "keys") AuthMode authMode,
		@ConfigProperty(name = "exofind.storage.local.directory") Path storageDirectory
	) {
		this.storageMode = storageMode;
		this.nodeState = nodeState;
		this.objectStorage = objectStorage;
		this.authMode = authMode;
		this.storageDirectory = storageDirectory;
	}

	void onStart(@Observes StartupEvent event) {
		var line = logger.atInfo()
			.addKeyValue("storage", name(storageMode))
			.addKeyValue("auth", name(authMode))
			.addKeyValue("indexer", nodeState.isIndexerCandidate());

		if(storageMode == StorageMode.OBJECT) {
			line = line.addKeyValue("bucket", objectStorage.get().bucket());
		}

		/*
		 * Named in both modes: a node sharing an object storage still works
		 * through this directory, so the disk it needs is as much a part of what
		 * it is as the bucket it syncs with.
		 */
		line.addKeyValue("directory", storageDirectory)
			.log(
				nodeState.isIndexerCandidate()
					? "Starting node, which competes to write indexes"
					: "Starting node, which only answers searches"
			);
	}

	private static String name(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}
}
