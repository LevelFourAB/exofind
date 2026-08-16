package se.l4.exofind.engine.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.NoopSync;

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
		var path = indexRoot.resolve(name);
		Files.createDirectories(path);

		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);
		var index = new Index(nodeState, name, path, new NoopSync());
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
