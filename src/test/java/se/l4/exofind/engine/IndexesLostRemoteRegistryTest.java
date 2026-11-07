package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.IndexStorageHeldException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.ObjectStorageRegistryAudit;
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
 * What the nodes of an object storage deployment do when the registry object
 * is lost from the bucket while the indexes it named are still there.
 *
 * <p>A lost registry is repaired from the generations in the bucket. A node
 * that never read the registry must not write a new one from scratch over
 * them, because every other node takes the registry it reads for the truth
 * and drops the indexes it does not name.
 */
public class IndexesLostRemoteRegistryTest {
	@TempDir
	Path directory;

	String storagePrefix;
	ObjectStorage storage;

	@BeforeEach
	void setup() throws IOException {
		storagePrefix = "lostregistry" + RandomStringUtils.insecure().nextAlphabetic(10).toLowerCase();
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

	private Indexes node(String name, boolean holder) throws IOException {
		var nodeState = new NodeState(holder);
		nodeState.updateOwnership(holder);

		var registry = new IndexRegistry(new ObjectStorageRegistryStorage(storage), Duration.ofMinutes(5));
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

	private void loseTheRegistryObject() {
		var client = TestObjectStorage.client();
		var objects = client.listObjectsV2Paginator(
			b -> b.bucket(TestObjectStorage.BUCKET).prefix(storagePrefix)
		).contents().stream()
			.filter(object -> object.key().endsWith("registry/indexes.ef.bin"))
			.toList();

		assertThat(objects.size(), is(1));
		client.deleteObject(
			DeleteObjectRequest.builder()
				.bucket(TestObjectStorage.BUCKET)
				.key(objects.getFirst().key())
				.build()
		);
	}

	@Test
	public void aCreateOnAFreshNodeAfterTheRegistryWasLostDoesNotDropTheOtherIndexes()
		throws IOException
	{
		// Given books and films with one pushed document each
		var seeder = node("seeder", true);
		try {
			for(var name : new String[] { "books", "films" }) {
				seeder.create(name, definition())
					.addDocument(new Document(new Document.Value("id", name + "-1")));
			}
		} finally {
			seeder.close();
		}

		// And node 1 holding copies of both
		var node1 = node("node1", false);
		Indexes node2 = null;
		try {
			node1.refresh();
			assertThat(node1.getOrThrow("books").getDocumentCount(), is(1L));
			assertThat(node1.getOrThrow("films").getDocumentCount(), is(1L));

			// And the registry object is lost from the bucket
			loseTheRegistryObject();

			// When node 2, started after the loss, creates another index
			node2 = node("node2", true);
			RuntimeException failure = null;
			try {
				node2.create("bar", definition());
			} catch(RuntimeException e) {
				failure = e;
			}

			// Then node 1 still names books and films and keeps their copies
			node1.refresh();
			assertThat(
				"node 1 dropped books after the create on node 2 answered " + failure,
				node1.getRegistered("books").isPresent(),
				is(true)
			);
			assertThat(node1.getRegistered("films").isPresent(), is(true));
			assertThat(
				Files.isDirectory(directory.resolve("node1").resolve("indexes").resolve("books@1")),
				is(true)
			);

			// And the create was refused
			assertThat("create over a lost registry was accepted", failure != null, is(true));
		} finally {
			node1.close();
			if(node2 != null) {
				node2.close();
			}
		}
	}

	@Test
	public void aCreateAfterTheRegistryWasLostWorksOnceARepairRebuiltIt()
		throws IOException
	{
		// Given books with one pushed document
		var seeder = node("seeder", true);
		try {
			seeder.create("books", definition())
				.addDocument(new Document(new Document.Value("id", "books-1")));
		} finally {
			seeder.close();
		}

		// And the registry object is lost from the bucket
		loseTheRegistryObject();

		var node = node("node", true);
		try {
			// And a create on a node that never read the registry was refused
			var refused = assertThrows(
				IndexStorageHeldException.class,
				() -> node.create("bar", definition())
			);
			assertThat(refused.getCode(), is("index:generation:storage_held"));

			// When a repair rebuilds the registry and the create is sent again
			new ObjectStorageRegistryAudit(
				storage,
				new ObjectStorageRegistryStorage(storage),
				new ObjectStorageIndexRemovals(storage)
			).repair(true, Lists.immutable.empty());

			node.create("bar", definition());

			// Then the registry names both indexes
			node.refresh();
			assertThat(node.getRegistered("books").isPresent(), is(true));
			assertThat(node.getRegistered("bar").isPresent(), is(true));
			assertThat(node.getOrThrow("books").getDocumentCount(), is(1L));
		} finally {
			node.close();
		}
	}
}
