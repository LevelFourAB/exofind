package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.Index;

import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexNotFoundException;
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
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * What a create or a delete leaves behind when the storage takes the write of
 * the registry but answers it with a server error, the way a proxy in front
 * of the storage answers {@code 504 Gateway Timeout} for a request the storage
 * finished after the proxy stopped waiting.
 *
 * <p>The registry write then did happen, and the node must act on the
 * registry it wrote: a created name starts empty, and a deleted name is
 * marked so that its objects go and a repair does not bring it back.
 */
public class IndexesRegistryWriteWithServerErrorTest {
	@TempDir
	Path directory;

	String storagePrefix;

	/**
	 * Whether the next write of the registry is taken by the storage and
	 * then answered with a gateway error.
	 */
	final AtomicBoolean failNextRegistryWrite = new AtomicBoolean();

	/**
	 * Whether the lost answer arrives as a dropped connection rather than as
	 * a gateway error.
	 */
	final AtomicBoolean dropConnectionInstead = new AtomicBoolean();

	/**
	 * What happens between the storage taking the registry write and the
	 * node getting the lost answer, such as another node writing the
	 * registry.
	 */
	volatile Runnable beforeLostAnswer = () -> {};

	/**
	 * Whether the next read of the registry fails with a dropped connection.
	 */
	final AtomicBoolean failNextRegistryRead = new AtomicBoolean();

	ObjectStorage storage;
	ObjectStorage faultyStorage;

	@BeforeEach
	void setup() throws IOException {
		storagePrefix = "gateway" + RandomStringUtils.insecure().nextAlphabetic(10).toLowerCase();
		storage = storage();

		var real = storage.client();
		var faulty = (S3Client) Proxy.newProxyInstance(
			S3Client.class.getClassLoader(),
			new Class<?>[] { S3Client.class },
			(proxy, method, args) -> {
				if(
					method.getName().equals("getObject")
						&& args != null
						&& args[0] instanceof GetObjectRequest request
						&& request.key().endsWith("registry/indexes.ef.bin")
						&& failNextRegistryRead.compareAndSet(true, false)
				) {
					throw SdkClientException.create("Connection reset");
				}

				Object result;
				try {
					result = method.invoke(real, args);
				} catch(InvocationTargetException e) {
					throw e.getCause();
				}

				if(
					method.getName().equals("putObject")
						&& args[0] instanceof PutObjectRequest request
						&& request.key().endsWith("registry/indexes.ef.bin")
						&& failNextRegistryWrite.compareAndSet(true, false)
				) {
					// The storage took the write; only the answer is lost
					beforeLostAnswer.run();

					if(dropConnectionInstead.get()) {
						throw SdkClientException.create("Connection reset");
					}

					throw S3Exception.builder()
						.statusCode(504)
						.message("Gateway Timeout")
						.build();
				}

				return result;
			}
		);

		faultyStorage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(storagePrefix),
			true
		) {
			@Override
			public S3Client client() {
				return faulty;
			}
		};
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

	private ObjectStorage storage() throws IOException {
		return new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(storagePrefix),
			true
		);
	}

	/**
	 * A node that writes the registry through the faulty client and
	 * everything else through the plain one.
	 */
	private Indexes node(String name, IndexRegistry registry) throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

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

	private IndexRegistry registry() {
		return new IndexRegistry(
			new ObjectStorageRegistryStorage(faultyStorage),
			Duration.ofMinutes(5)
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
	 * Create an index holding one document on a node of its own, and shut
	 * that node down so the document is pushed.
	 */
	private void createWithOneDocument(String index) throws IOException {
		var first = node("first", registry());
		try {
			var created = first.create(index, definition());
			created.addDocument(new Document(new Document.Value("id", "deleted-doc")));
		} finally {
			first.close();
		}
	}

	@Test
	public void createAnsweredWithGatewayErrorDoesNotServeTheDocumentsOfADeletedIndex()
		throws IOException
	{
		// Given an index holding one document that was deleted a moment ago
		createWithOneDocument("books");

		var registry = registry();
		var second = node("second", registry);
		try {
			second.refresh();
			second.delete("books");

			// When the index is created again and the registry write is answered with a gateway error
			failNextRegistryWrite.set(true);
			second.create("books", definition());

			// Then the name is registered and starts empty
			registry.refresh();
			assertThat(registry.get("books").isPresent(), is(true));
			assertThat(
				"documents of the deleted index served under the created name",
				second.getOrThrow("books").getDocumentCount(),
				is(0L)
			);
		} finally {
			second.close();
		}
	}

	@Test
	public void deleteAnsweredWithGatewayErrorLeavesARemovalMark() throws IOException {
		// Given an index holding one document
		createWithOneDocument("books");

		var registry = registry();
		var second = node("second", registry);
		try {
			second.refresh();

			// When it is deleted and the registry write is answered with a gateway error
			failNextRegistryWrite.set(true);
			second.delete("books");

			// Then the name is gone and carries a removal mark
			registry.refresh();
			assertThat(registry.get("books").isEmpty(), is(true));
			assertThat(
				"deleted index left without a removal mark",
				new ObjectStorageIndexRemovals(storage).markedAt(IndexName.of("books")).isPresent(),
				is(true)
			);
		} finally {
			second.close();
		}
	}

	@Test
	public void repairAfterADeleteAnsweredWithGatewayErrorDoesNotBringTheIndexBack()
		throws IOException
	{
		// Given an index holding one document
		createWithOneDocument("books");

		var registry = registry();
		var second = node("second", registry);
		try {
			second.refresh();

			// And a delete of it whose registry write was answered with a gateway error
			failNextRegistryWrite.set(true);
			second.delete("books");

			// And a delete sent again, which finds the name gone
			assertThrows(IndexNotFoundException.class, () -> second.delete("books"));

			// When an operator repairs the registry without restoring anything
			var audit = new ObjectStorageRegistryAudit(
				storage,
				new ObjectStorageRegistryStorage(storage),
				new ObjectStorageIndexRemovals(storage)
			);
			var result = audit.repair(false, Lists.immutable.empty());

			// Then the deleted index is not registered again
			assertThat(
				"repair registered the deleted index again: " + result.createdIndexes(),
				result.createdIndexes().contains("books"),
				is(false)
			);
		} finally {
			second.close();
		}
	}

	/**
	 * The same lost answer arriving as a dropped connection is read back and
	 * taken as the write it was.
	 */
	@Test
	public void deleteAnsweredWithDroppedConnectionLeavesARemovalMark() throws IOException {
		// Given an index holding one document
		createWithOneDocument("books");

		var registry = registry();
		var second = node("second", registry);
		try {
			second.refresh();

			// When it is deleted and the answer to the registry write is a dropped connection
			dropConnectionInstead.set(true);
			failNextRegistryWrite.set(true);
			second.delete("books");

			// Then the name is gone and carries a removal mark
			registry.refresh();
			assertThat(registry.get("books").isEmpty(), is(true));
			assertThat(
				new ObjectStorageIndexRemovals(storage).markedAt(IndexName.of("books")).isPresent(),
				is(true)
			);
		} finally {
			second.close();
		}
	}

	/**
	 * A registry of another node, which reads and writes the registry through
	 * the plain client.
	 */
	private IndexRegistry otherNodeRegistry() {
		return new IndexRegistry(
			new ObjectStorageRegistryStorage(storage),
			Duration.ofMinutes(5)
		);
	}

	/**
	 * Make another node register an index after the storage took the next
	 * registry write and before the node that sent it reads the registry
	 * back.
	 */
	private void otherNodeWritesBeforeTheReadBack() {
		var other = otherNodeRegistry();
		beforeLostAnswer = () -> {
			beforeLostAnswer = () -> {};
			other.refresh();
			other.create("films", "1");
		};
	}

	@Test
	public void createAnsweredWithGatewayErrorWhileAnotherNodeWritesStartsEmpty()
		throws IOException
	{
		// Given an index holding one document that was deleted a moment ago
		createWithOneDocument("books");

		var registry = registry();
		var second = node("second", registry);
		try {
			second.refresh();
			second.delete("books");

			// And another node writes the registry while the answer to the next write is lost
			otherNodeWritesBeforeTheReadBack();
			failNextRegistryWrite.set(true);

			// When the index is created again
			RuntimeException failure = null;
			try {
				second.create("books", definition());
			} catch(RuntimeException e) {
				failure = e;
			}

			// Then the name is registered and starts empty
			registry.refresh();
			second.refresh();
			assertThat(registry.get("books").isPresent(), is(true));
			assertThat(
				"documents of the deleted index served under the created name, create answered " + failure,
				second.getOrThrow("books").getDocumentCount(),
				is(0L)
			);
			assertThat("create of the name failed: " + failure, failure == null, is(true));
		} finally {
			second.close();
		}
	}

	@Test
	public void createWhoseReadBackFailsDoesNotServeTheDocumentsOfADeletedIndex()
		throws IOException
	{
		// Given an index holding one document that was deleted a moment ago
		createWithOneDocument("books");

		var registry = registry();
		var second = node("second", registry);
		try {
			second.refresh();
			second.delete("books");

			// And the answer to the next registry write is lost and the read-back fails
			beforeLostAnswer = () -> {
				beforeLostAnswer = () -> {};
				failNextRegistryRead.set(true);
			};
			failNextRegistryWrite.set(true);

			// When the index is created, and the client sends the create again
			RuntimeException failure = null;
			try {
				second.create("books", definition());
			} catch(RuntimeException e) {
				failure = e;
			}

			try {
				second.create("books", definition());
			} catch(RuntimeException e) {
				// The name is taken, so the client uses the index it finds
			}

			// Then the index under the name does not hold the deleted document
			registry.refresh();
			second.refresh();
			Index index = second.getOrThrow("books");
			assertThat(
				"documents of the deleted index served under the created name, first create answered " + failure,
				index.getDocumentCount(),
				is(0L)
			);
		} finally {
			second.close();
		}
	}

	@Test
	public void deleteAnsweredWithGatewayErrorWhileAnotherNodeWritesLeavesARemovalMark()
		throws IOException
	{
		// Given an index holding one document
		createWithOneDocument("books");

		var registry = registry();
		var second = node("second", registry);
		try {
			second.refresh();

			// And another node writes the registry while the answer to the next write is lost
			otherNodeWritesBeforeTheReadBack();
			failNextRegistryWrite.set(true);

			// When the index is deleted
			RuntimeException failure = null;
			try {
				second.delete("books");
			} catch(RuntimeException e) {
				failure = e;
			}

			// Then the name is gone and carries a removal mark
			registry.refresh();
			assertThat(registry.get("books").isEmpty(), is(true));
			assertThat(
				"deleted index left without a removal mark, delete answered " + failure,
				new ObjectStorageIndexRemovals(storage).markedAt(IndexName.of("books")).isPresent(),
				is(true)
			);

			// And the name can be created again and starts empty
			second.refresh();
			var created = second.create("books", definition());
			assertThat(created.getDocumentCount(), is(0L));
		} finally {
			second.close();
		}
	}

	@Test
	public void promoteAnsweredWithGatewayErrorWhileAnotherNodeWritesIsTakenAsDone() {
		// Given an index with a second generation that is not live
		var registry = registry();
		registry.create("books", "1");
		registry.addGeneration("books", "2");

		// And another node writes the registry while the answer to the next write is lost
		otherNodeWritesBeforeTheReadBack();
		failNextRegistryWrite.set(true);

		// When the second generation is promoted over the first
		RuntimeException failure = null;
		try {
			registry.promote("books", "2", "1");
		} catch(RuntimeException e) {
			failure = e;
		}

		// Then the promote is reported as done and the second generation is live
		registry.refresh();
		assertThat(registry.get("books").orElseThrow().live(), is("2"));
		assertThat("promote that landed was reported as failed: " + failure, failure == null, is(true));
	}
}
