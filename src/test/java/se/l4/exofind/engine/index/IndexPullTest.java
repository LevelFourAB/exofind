package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.index.state.SyncConflictException;

/**
 * Tests for what a pull does to the local copy: it brings the files another
 * node wrote, and what was open over the previous ones has to let go of them
 * before they arrive.
 */
public class IndexPullTest {
	@TempDir
	Path indexRoot;

	private final List<Index> indexes = new ArrayList<>();

	@AfterEach
	void cleanup() throws IOException {
		for(var index : indexes) {
			index.close(false);
		}
	}

	/**
	 * A node holding an index is told to give up what it has and take over the
	 * copy another node pushed. The files the pull downloads are the ones a
	 * rollback of the open writer removes, so the writer has to be let go of
	 * before they land - a node that keeps it ends up with a directory holding
	 * no commit at all.
	 */
	@Test
	public void testPullAfterALostPushKeepsTheDownloadedFiles() throws IOException {
		var sync = new CopyingSync();
		var index = create("held", sync);

		index.addDocument(document("1"));
		index.commit();

		// What the node that took the index over pushed while this one paused
		var successor = create("successor", new CopyingSync());
		successor.addDocument(document("2"));
		successor.commit();

		/*
		 * The push is refused because the remote has moved on, which is what
		 * puts the index in need of a pull while its writer is still open.
		 */
		sync.refusePush = true;
		index.addDocument(document("3"));

		assertThrows(SyncConflictException.class, index::commit);
		assertThat(index.getState(), is(IndexState.NEEDS_PULL));

		sync.download = indexRoot.resolve("successor");
		index.pull();

		assertThat(index.getState(), is(IndexState.USABLE));
		assertThat(index.getDocumentCount(), is(1L));
		assertThat(index.getDocument("2"), is(notNullValue()));
		assertThat(index.getDocument("1"), is(nullValue()));
	}

	/**
	 * A local copy that has to hold a commit and does not is a copy that lost
	 * files, and the remote it is already in step with sends nothing to replace
	 * them. Opening it as a new and empty index would let the next push write
	 * that emptiness over the remote, so the pull leaves the index waiting for
	 * another one instead.
	 */
	@Test
	public void testPullRefusesToOpenAnEmptyIndexOverAMissingCommit() throws IOException {
		var sync = new CopyingSync();
		var path = indexRoot.resolve("held");

		var index = create("held", sync);
		index.addDocument(document("1"));
		index.commit();
		index.close(false);

		// The Lucene files go missing while the definition stays behind
		try(var files = Files.list(path)) {
			for(var file : files.toList()) {
				if(!file.getFileName().toString().equals(Index.DEFINITION_FILE)) {
					Files.delete(file);
				}
			}
		}

		sync.hasSyncedCommit = true;

		var reopened = new Index(nodeState(), "held", path, sync);
		indexes.add(reopened);
		reopened.pull();

		assertThat(reopened.getState(), is(IndexState.NEEDS_PULL));

		try(var directory = FSDirectory.open(path)) {
			assertThat(DirectoryReader.indexExists(directory), is(false));
		}
	}

	/**
	 * A node that gains an index answers for it before the reopen that opens
	 * the writer has run, so the index is still open the read-only way. A
	 * definition update arriving in that window has no commit to name, and
	 * storing it would push a manifest holding the definition alone - which
	 * takes every segment of the index with it. The update is refused instead,
	 * for the caller to send again once the reopen is done.
	 */
	@Test
	public void testDefinitionUpdateIsRefusedBeforeTheWriterOfAGainedIndexIsOpen()
		throws IOException
	{
		var sync = new CopyingSync();
		var path = indexRoot.resolve("gained");

		var index = create("gained", sync);
		index.addDocument(document("1"));
		index.commit();
		index.close(false);

		/*
		 * Opened while another node holds the index, which is what leaves it
		 * with a reader over the commit and no writer.
		 */
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(false);

		var reopenedSync = new CopyingSync();
		reopenedSync.localPath = path;

		var reopened = new Index(nodeState, "gained", path, reopenedSync);
		indexes.add(reopened);
		reopened.pull();

		assertThat(reopened.getState(), is(IndexState.USABLE));

		// The index is gained, and the reopen has not run yet
		nodeState.updateOwnership(true);

		assertThrows(
			IndexOutOfDateException.class,
			() -> reopened.updateDefinition(IndexDef.getDefaultInstance())
		);

		assertThat(reopenedSync.pushed, is(nullValue()));
	}

	/**
	 * A node whose claim on an index was taken away pushes nothing more,
	 * whatever the push is for. A successor is writing the index and answering
	 * for it, so a flush of what is only here would replace documents that
	 * successor has already acknowledged - they are given up instead.
	 */
	@Test
	public void testAnIndexTakenAwayPushesNothing() throws IOException {
		var sync = new CopyingSync();
		var index = create("held", sync);

		index.addDocument(document("1"));
		index.commit();

		sync.pushed = null;

		// The claim lapsed, so another node may already be writing the index
		index.revokeWriting();

		index.addDocument(document("2"));

		assertThrows(IndexReadonlyException.class, index::commit);
		assertThat(sync.pushed, is(nullValue()));

		// Nor does the instance push what it holds on its way out
		index.close(true);
		assertThat(sync.pushed, is(nullValue()));
	}

	/**
	 * Node state as it looks on a node that has been granted the indexer role.
	 */
	private static NodeState nodeState() {
		var state = new NodeState(true);
		state.updateOwnership(true);
		return state;
	}

	/**
	 * Open a writable index of its own directory, with a definition holding one
	 * string field that names its documents.
	 */
	private Index create(String name, CopyingSync sync) throws IOException {
		var path = indexRoot.resolve(name);
		Files.createDirectories(path);
		sync.localPath = path;

		var index = new Index(nodeState(), name, path, sync);
		indexes.add(index);

		index.pull();
		index.updateDefinition(
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setPrimaryKey(true)
						.build()
				)
				.build()
		);

		return index;
	}

	private static Document document(String id) {
		return new Document(new Document.Value("id", id));
	}

	/**
	 * Synchronization that stands in for a remote another node has written:
	 * a pull copies a directory over the local copy, the way a real one
	 * replaces the files the manifest no longer names, and a push can be
	 * refused the way a remote that has moved on refuses one.
	 */
	private static final class CopyingSync implements StateSync {
		/**
		 * Directory a pull copies over the local copy, or {@code null} for a
		 * remote holding nothing new.
		 */
		Path download;

		boolean refusePush;

		boolean hasSyncedCommit;

		/**
		 * Directory the index this synchronization belongs to is held in, which
		 * a pull writes into.
		 */
		Path localPath;

		/**
		 * Files of the newest push, or {@code null} where nothing was pushed.
		 */
		Set<String> pushed;

		@Override
		public boolean pull() throws IOException {
			if(download == null || localPath == null) {
				return false;
			}

			/*
			 * Lock files are left alone, as a real pull does - everything else
			 * is what the copied directory holds and nothing more.
			 */
			try(var files = Files.list(localPath)) {
				for(var path : files.toList()) {
					if(!isLock(path)) {
						Files.delete(path);
					}
				}
			}

			try(var files = Files.list(download)) {
				for(var path : files.toList()) {
					if(isLock(path)) {
						continue;
					}

					Files.copy(
						path,
						localPath.resolve(path.getFileName().toString()),
						StandardCopyOption.REPLACE_EXISTING
					);
				}
			}

			return true;
		}

		private static boolean isLock(Path path) {
			return path.getFileName().toString().endsWith(".lock");
		}

		@Override
		public void push(Set<String> files) throws IOException {
			if(refusePush) {
				throw new SyncConflictException("simulated conflict");
			}

			pushed = files;
		}

		@Override
		public void claimWriter() throws IOException {
		}

		@Override
		public boolean hasSyncedCommit() {
			return hasSyncedCommit;
		}

		@Override
		public OptionalLong syncedVersion() {
			return OptionalLong.empty();
		}

		@Override
		public OptionalInt luceneCreatedMajor() {
			return OptionalInt.empty();
		}
	}
}
