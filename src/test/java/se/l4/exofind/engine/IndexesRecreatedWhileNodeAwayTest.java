package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.ObjectStorageRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.ObjectStorageIndexRemovals;
import se.l4.exofind.engine.index.state.ObjectStorageSyncProvider;
import se.l4.exofind.engine.index.state.TestObjectStorage;
import se.l4.exofind.engine.storage.ObjectStorage;
import se.l4.exofind.engine.storage.StorageMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * What a node that was away while an index was deleted and created again
 * under the same name does with the copy it kept of the deleted index.
 *
 * <p>The first generation of every index has the same name, so the copy
 * names a generation the registry names again. It is a copy of another
 * index all the same, and must not be served or pushed as the new one.
 */
public class IndexesRecreatedWhileNodeAwayTest {
	@TempDir
	Path directory;

	String storagePrefix;
	ObjectStorage storage;

	@BeforeEach
	void setup() throws IOException {
		storagePrefix = "recreated" + RandomStringUtils.insecure().nextAlphabetic(10).toLowerCase();
		storage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(storagePrefix),
			true
		);
	}

	@AfterEach
	void cleanup() {
		var client = TestObjectStorage.client();
		var objects = client.listObjectsV2Paginator(
			b -> b.bucket(TestObjectStorage.BUCKET).prefix(storagePrefix)
		).contents().stream().toList();

		for(var object : objects) {
			client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(object.key())
					.build()
			);
		}
	}

	private IndexRegistry registry() {
		return new IndexRegistry(new ObjectStorageRegistryStorage(storage), Duration.ofMinutes(5));
	}

	private Indexes node(String name, boolean holder) throws IOException {
		var nodeState = new NodeState(holder);
		nodeState.updateOwnership(holder);

		var registry = registry();
		return new Indexes(
			nodeState,
			new ObjectStorageSyncProvider(storage),
			registry,
			new RegistryHints(registry, StorageMode.OBJECT),
			new ObjectStorageIndexRemovals(storage),
			directory.resolve(name),
			OptionalInt.empty(),
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			4,
			Duration.ofSeconds(10),
			10000,
			Duration.ofSeconds(1),
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);
	}

	private static IndexDef definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
					)
					.setPrimaryKey(true)
					.build()
			)
			.build();
	}

	/**
	 * Delete the index and create it again on another node, up to the point
	 * where that node has registered the name and cleared what the delete
	 * left, and has not pushed the first manifest of the new index yet.
	 */
	private void deleteAndCreateAgainElsewhere() throws IOException {
		var registry = registry();
		var removals = new ObjectStorageIndexRemovals(storage);

		registry.remove("books");
		removals.mark(IndexName.of("books"));

		registry.create("books", "1");
		removals.prepareForIndex("books");
		removals.prepareForGeneration(IndexName.of("books", "1"));
	}

	/**
	 * A node that holds a copy of books@1 with one document, closed and
	 * pushed.
	 */
	private void copyWithOneDocument(String name) throws IOException {
		var node = node(name, true);
		try {
			var created = node.create("books", definition());
			created.addDocument(new Document(new Document.Value("id", "old")));
		} finally {
			node.close();
		}
	}

	@Test
	public void aReaderBackAfterTheNameWasCreatedAgainDoesNotServeItsCopyOfTheDeletedIndex()
		throws IOException
	{
		// Given a reader that kept a copy of books@1 holding the document old, and is stopped
		copyWithOneDocument("r");

		// And books is deleted and created again on another node, which has not pushed yet
		deleteAndCreateAgainElsewhere();

		// When the reader starts again and a request opens books
		var reader = node("r", false);
		try {
			reader.refresh();

			// Then the reader does not answer with the deleted document
			assertThat(
				"copy of the deleted index served under the new name",
				reader.getOrThrow("books").getDocumentCount(),
				is(0L)
			);
		} finally {
			reader.close();
		}
	}

	@Test
	public void aHolderBackAfterTheNameWasCreatedAgainDoesNotPushItsCopyOfTheDeletedIndex()
		throws IOException
	{
		// Given a node that kept a copy of books@1 holding the document old, and is stopped
		copyWithOneDocument("r");

		// And books is deleted and created again on another node, which has not pushed yet
		deleteAndCreateAgainElsewhere();

		// When the node starts again holding books, writes a document to it and closes, which pushes
		var holder = node("r", true);
		var written = false;
		try {
			holder.refresh();
			try {
				holder.getOrThrow("books").addDocument(new Document(new Document.Value("id", "new")));
				written = true;
			} catch(ValidationException e) {
				// The new index has no definition here yet, and the one of the deleted index is not used
			}
		} finally {
			holder.close();
		}

		// Then a node that opens books afterwards finds the new document, if any, and not the deleted one
		var other = node("other", false);
		try {
			other.refresh();
			assertThat(
				"copy of the deleted index pushed as the new index",
				other.getOrThrow("books").getDocumentCount(),
				is(written ? 1L : 0L)
			);
		} finally {
			other.close();
		}
	}
}
