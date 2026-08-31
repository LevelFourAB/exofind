package se.l4.exofind.engine.reindex;

import java.nio.file.Path;
import java.time.Duration;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;

/**
 * ReindexJobs the way a test node stands one up: local record storage under
 * the node's directory, uncontested ownership, no post-promote grace so a
 * test does not wait it out.
 */
public final class TestReindexJobs {
	private TestReindexJobs() {
	}

	public static ReindexJobs create(
		NodeState nodeState,
		Indexes indexes,
		IndexRegistry registry,
		Path storageDirectory
	) {
		var ownership = new LocalIndexerOwnership();
		ownership.start((index, owner) -> {
		});

		return new ReindexJobs(
			nodeState,
			indexes,
			registry,
			new LocalReindexJobStorage(
				storageDirectory.resolve("jobs").resolve("reindex").resolve("records")
			),
			ownership,
			2,
			Duration.ofMinutes(5),
			Duration.ofMinutes(5),
			Duration.ZERO
		);
	}
}
