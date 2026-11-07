package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexStorageHeldException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.ObjectStorageRegistryStorage;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Tests of a writer that pushes to the prefix of a generation after the
 * index was deleted and its prefix cleared for a new index of the same name.
 *
 * <p>The first generation of every index has the same name, so a deleted
 * index and the index created again under its name share one prefix. A
 * writer of the deleted index that pushes there must not make the deleted
 * documents the contents of the new index.
 */
@ExtendWith(QuietLuceneVersionWarnings.class)
public class ObjectStorageSyncAfterRemovalTest {
	static final IndexName GENERATION = IndexName.of("books", "1");

	@TempDir
	Path writerPath;

	@TempDir
	Path readerPath;

	ObjectStorage storage;

	/**
	 * Runs once after the next delete of several objects returned, standing
	 * in for a writer that pushes while a clear removes the prefix.
	 */
	final AtomicReference<Runnable> afterNextBatchDelete = new AtomicReference<>();

	ObjectStorageIndexRemovals removals;
	ObjectStorageIndexRemovals hookedRemovals;

	@BeforeEach
	void setup() throws IOException {
		var prefix = Optional.of("test" + RandomStringUtils.insecure().nextAlphabetic(10));
		storage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			prefix,
			false
		);

		var real = storage.client();
		var hooked = (S3Client) Proxy.newProxyInstance(
			S3Client.class.getClassLoader(),
			new Class<?>[] { S3Client.class },
			(proxy, method, args) -> {
				Object result;
				try {
					result = method.invoke(real, args);
				} catch(InvocationTargetException e) {
					throw e.getCause();
				}

				if(method.getName().equals("deleteObjects")) {
					var run = afterNextBatchDelete.getAndSet(null);
					if(run != null) {
						run.run();
					}
				}

				return result;
			}
		);

		var hookedStorage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			prefix,
			false
		) {
			@Override
			public S3Client client() {
				return hooked;
			}
		};

		removals = new ObjectStorageIndexRemovals(storage);
		hookedRemovals = new ObjectStorageIndexRemovals(hookedStorage);
	}

	@AfterEach
	void cleanup() {
		var client = storage.client();
		var objects = client.listObjectsV2Paginator(
			b -> b.bucket(storage.bucket()).prefix(storage.indexesPath())
		).contents().stream().toList();

		for(var object : objects) {
			client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(storage.bucket())
					.key(object.key())
					.build()
			);
		}
	}

	private ObjectStorageSync sync(Path path) {
		return (ObjectStorageSync) new ObjectStorageSyncProvider(storage).createSync(GENERATION, path);
	}

	private static String createFile(Path root, String name, int size) throws IOException {
		var bytes = new byte[size];
		new Random().nextBytes(bytes);
		Files.write(root.resolve(name), bytes);
		return name;
	}

	private static void push(ObjectStorageSync sync, String... files) throws IOException {
		sync.push(new LinkedHashSet<>(java.util.List.of(files)));
	}

	private boolean remoteManifestExists() {
		try {
			storage.client().headObject(
				HeadObjectRequest.builder()
					.bucket(storage.bucket())
					.key(storage.indexPath(GENERATION) + "/" + LocalCopy.MANIFEST_FILE)
					.build()
			);
			return true;
		} catch(NoSuchKeyException e) {
			return false;
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return false;
			}

			throw e;
		}
	}

	/**
	 * Delete the index and create it again on another node: mark the name,
	 * then clear the marked prefix the way a create does before it opens the
	 * first generation.
	 */
	private void deleteAndCreateAgain(ObjectStorageIndexRemovals removals) throws IOException {
		removals.mark(IndexName.of("books"));
		removals.prepareForIndex("books");
		removals.prepareForGeneration(GENERATION);
	}

	/**
	 * A manifest removed by hand, with the generation still registered, is
	 * written again from the local copy, which is the only data there is.
	 */
	@Test
	public void aPushRecreatesAManifestRemovedWhileTheRegistryNamesTheGeneration()
		throws IOException
	{
		// Given books@1 registered, and a writer of it that pushed a commit
		var registry = new IndexRegistry(
			new ObjectStorageRegistryStorage(storage),
			Duration.ofMinutes(5)
		);
		var registered = registry.create("books", "1");
		var createdAt = registered.generation("1").orElseThrow().createdAt();

		var writer = (ObjectStorageSync) new ObjectStorageSyncProvider(storage)
			.createSync(GENERATION, writerPath, createdAt);
		var segment = createFile(writerPath, "segments_1", 64);
		push(writer, segment);

		// And the manifest is removed by hand
		storage.client().deleteObject(
			DeleteObjectRequest.builder()
				.bucket(storage.bucket())
				.key(storage.indexPath(GENERATION) + "/" + LocalCopy.MANIFEST_FILE)
				.build()
		);

		// When the writer pushes again
		var next = createFile(writerPath, "segments_2", 64);
		push(writer, segment, next);

		// Then the manifest is back, and a reader can pull it
		assertThat(remoteManifestExists(), is(true));
		assertThat(sync(readerPath).pull(), is(true));
	}

	@Test
	public void aPushOfADeletedIndexAfterItsNameWasCreatedAgainIsRefused() throws IOException {
		// Given a writer of books@1 that pushed a commit
		var writer = sync(writerPath);
		var segment = createFile(writerPath, "segments_1", 64);
		push(writer, segment);

		// And books is deleted and created again on another node, which clears the prefix
		deleteAndCreateAgain(removals);

		// When the writer of the deleted index commits and pushes again
		var next = createFile(writerPath, "segments_2", 64);
		Exception failure = null;
		try {
			push(writer, segment, next);
		} catch(Exception e) {
			failure = e;
		}

		// Then the push is refused and the new index does not take the deleted index's files
		assertThat(
			"push of the deleted index rebuilt the manifest of the new index",
			remoteManifestExists(),
			is(false)
		);
		assertThat("push of the deleted index was accepted", failure != null, is(true));
		assertThat(
			"a reader of the new index found a manifest to pull",
			sync(readerPath).pull(),
			is(false)
		);
	}

	@Test
	public void aPushDuringTheClearOfADeletedIndexLeavesNoManifestNamingRemovedObjects()
		throws IOException
	{
		// Given a writer of books@1 that pushed a commit
		var writer = sync(writerPath);
		var segment = createFile(writerPath, "segments_1", 64);
		push(writer, segment);

		// And the writer pushes again once the clear removed the manifest and before it removes the rest
		var next = createFile(writerPath, "segments_2", 64);
		var pushFailure = new AtomicReference<Exception>();
		afterNextBatchDelete.set(() -> {
			try {
				push(writer, segment, next);
			} catch(Exception e) {
				pushFailure.set(e);
			}
		});

		// When books is deleted on another node and a create of it clears the marked prefix
		removals.mark(IndexName.of("books"));
		hookedRemovals.prepareForIndex("books");

		// Then no manifest is left that names objects the clear removed
		assertThat(afterNextBatchDelete.get() == null, is(true));
		Exception pullFailure = null;
		try {
			sync(readerPath).pull();
		} catch(IOException e) {
			pullFailure = e;
		}
		assertThat(
			"manifest left naming removed objects, stale push answered " + pushFailure + ": " + pullFailure,
			pullFailure == null,
			is(true)
		);

		// And a later create of the name is not refused as held
		IndexStorageHeldException held = null;
		try {
			removals.prepareForGeneration(GENERATION);
		} catch(IndexStorageHeldException e) {
			held = e;
		}
		assertThat("create of the name refused: " + held, held == null, is(true));
	}

	/**
	 * A sync of the generation that was created at the given time, whose
	 * registry check always answers that the copy is registered. Stands in
	 * for a writer that read the registry just before the delete landed.
	 */
	private ObjectStorageSync staleSync(Path path, Instant createdAt) {
		return new ObjectStorageSync(
			storage.client(),
			GENERATION.toString(),
			path,
			storage.bucket(),
			storage.indexPath(GENERATION),
			ObjectStorageSync.DEFAULT_GRACE,
			createdAt,
			copyCreatedAt -> true
		);
	}

	private Manifest remoteManifest() throws IOException {
		try(var response = storage.client().getObject(
			GetObjectRequest.builder()
				.bucket(storage.bucket())
				.key(storage.indexPath(GENERATION) + "/" + LocalCopy.MANIFEST_FILE)
				.build()
		)) {
			return Manifest.parseFrom(response.readAllBytes());
		}
	}

	/**
	 * Delete books and create it again through the registry, clearing the
	 * prefix the way the create does.
	 *
	 * @return
	 *   when the new first generation was created
	 */
	private Instant deleteAndCreateAgain(IndexRegistry registry) throws Exception {
		registry.remove("books");
		removals.mark(IndexName.of("books"));

		// Times are stored to the millisecond
		Thread.sleep(2);

		var created = registry.create("books", "1").generation("1").orElseThrow().createdAt();
		removals.prepareForIndex("books");
		removals.prepareForGeneration(GENERATION);
		return created;
	}

	/**
	 * A writer of the deleted index can read the registry just before the
	 * delete lands, and then push into the prefix the create cleared. The
	 * manifest it leaves records the time its generation was created, which
	 * is not the time of the new generation. The new index does not take it
	 * over, and its writer replaces it.
	 */
	@Test
	public void theNewIndexReplacesAManifestAStaleWriterPushedAfterTheClear() throws Exception {
		// Given books@1 registered, and a writer of it that pushed a commit
		var registry = new IndexRegistry(
			new ObjectStorageRegistryStorage(storage),
			Duration.ofMinutes(5)
		);
		var oldCreatedAt = registry.create("books", "1").generation("1").orElseThrow().createdAt();
		var stale = staleSync(writerPath, oldCreatedAt);
		var segment = createFile(writerPath, "segments_1", 64);
		push(stale, segment);

		// And books deleted and created again, and the stale writer pushes after the clear
		var newCreatedAt = deleteAndCreateAgain(registry);
		var next = createFile(writerPath, "segments_2", 64);
		push(stale, segment, next);

		// When the creator opens the new generation, claims it and pushes its first commit
		var creatorPath = Files.createDirectories(readerPath.resolve("creator"));
		var creator = (ObjectStorageSync) new ObjectStorageSyncProvider(storage)
			.createSync(GENERATION, creatorPath, newCreatedAt);
		var adopted = creator.pull();
		creator.claimWriter();
		var first = createFile(creatorPath, "segments_3", 64);
		push(creator, first);

		// Then the creator did not take over the stale manifest
		assertThat("the new index pulled the manifest of the deleted index", adopted, is(false));

		// And the remote holds the manifest of the new generation, naming only its files
		var manifest = remoteManifest();
		assertThat(manifest.getGenerationCreatedAt(), is(newCreatedAt.toEpochMilli()));
		assertThat(
			manifest.getFilesList().stream().map(ManifestFile::getName).toList(),
			is(java.util.List.of("segments_3"))
		);
	}

	/**
	 * A writer of the deleted index that only finds the manifest of the new
	 * generation, with a registry that names the new one, must not replace
	 * it.
	 */
	@Test
	public void aStaleWriterDoesNotReplaceTheManifestOfTheNewIndex() throws Exception {
		// Given books@1 registered, and a writer of it that pushed a commit
		var registry = new IndexRegistry(
			new ObjectStorageRegistryStorage(storage),
			Duration.ofMinutes(5)
		);
		var oldCreatedAt = registry.create("books", "1").generation("1").orElseThrow().createdAt();
		var writer = (ObjectStorageSync) new ObjectStorageSyncProvider(storage)
			.createSync(GENERATION, writerPath, oldCreatedAt);
		var segment = createFile(writerPath, "segments_1", 64);
		push(writer, segment);

		// And books deleted and created again, and the new index pushed its first commit
		var newCreatedAt = deleteAndCreateAgain(registry);
		var creatorPath = Files.createDirectories(readerPath.resolve("creator"));
		var creator = (ObjectStorageSync) new ObjectStorageSyncProvider(storage)
			.createSync(GENERATION, creatorPath, newCreatedAt);
		creator.claimWriter();
		push(creator, createFile(creatorPath, "segments_3", 64));

		// When the writer of the deleted index starts again on its copy and pushes
		var restarted = (ObjectStorageSync) new ObjectStorageSyncProvider(storage)
			.createSync(GENERATION, writerPath, oldCreatedAt);
		var next = createFile(writerPath, "segments_2", 64);
		Exception failure = null;
		try {
			push(restarted, segment, next);
		} catch(Exception e) {
			failure = e;
		}

		// Then the push is refused and the remote holds the manifest of the new generation
		assertThat("push of the deleted index was accepted", failure != null, is(true));
		assertThat(remoteManifest().getGenerationCreatedAt(), is(newCreatedAt.toEpochMilli()));
	}
}
