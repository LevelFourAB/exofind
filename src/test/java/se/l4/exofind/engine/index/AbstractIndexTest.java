package se.l4.exofind.engine.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.NoopSync;
import se.l4.exofind.engine.metrics.RequestMetrics;

public abstract class AbstractIndexTest {
	@TempDir
	Path indexRoot;

	private List<Index> indexes = new ArrayList<>();

	@AfterEach
	void cleanup() throws IOException {
		for(var idx : indexes) {
			idx.close();
		}
	}

	protected Index create(String name) throws IOException {
		return create(name, SearchThreads.inline());
	}

	/**
	 * Open an index whose searches spread over the given threads, for a test
	 * of what a search answers when it runs on more than one thread.
	 */
	protected Index create(String name, SearchThreads searchThreads) throws IOException {
		return create(name, searchThreads, FacetWarmer.none());
	}

	/**
	 * Open an index whose readers are prepared for facets by the given
	 * warmer after every commit, for a test of what a warm leaves for a
	 * search to do.
	 */
	protected Index create(String name, SearchThreads searchThreads, FacetWarmer facetWarmer)
		throws IOException
	{
		var path = indexRoot.resolve(name);
		Files.createDirectories(path);

		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);
		var index = new Index(
			nodeState,
			name,
			path,
			new NoopSync(),
			CommitPolicy.disabled(),
			DocumentCache.disabled(),
			RequestMetrics.none(),
			OptionalLong.empty(),
			searchThreads,
			facetWarmer
		);
		index.pull();
		indexes.add(index);
		return index;
	}

	protected Index create(String name, IndexDef.Builder def) throws IOException {
		var index = create(name);
		index.updateDefinition(def.build());
		return index;
	}

	protected Index create(IndexDef.Builder def) throws IOException {
		return create("test", def);
	}
}
