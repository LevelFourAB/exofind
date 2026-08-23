package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyIterable;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@ExtendWith(QuietLuceneVersionWarnings.class)
public class ObjectStorageSyncProviderTest {
	S3Client s3Client;

	@TempDir
	Path localPath;

	String storagePrefix;

	ObjectStorageSyncProvider provider;

	@BeforeEach
	void setup() throws Exception {
		s3Client = TestObjectStorage.client();

		storagePrefix = "test" + RandomStringUtils.insecure().nextAlphabetic(10);
		provider = newProvider(false);
	}

	private ObjectStorageSyncProvider newProvider(boolean indexer) throws IOException {
		return new ObjectStorageSyncProvider(newStorage(indexer));
	}

	private ObjectStorage newStorage(boolean indexer) throws IOException {
		return new ObjectStorage(
			TestObjectStorage.url(),
			TestObjectStorage.ACCESS_KEY,
			TestObjectStorage.SECRET_KEY,
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(storagePrefix),
			indexer
		);
	}

	@AfterEach
	void cleanup() throws Exception {
		var objects = s3Client.listObjectsV2Paginator(
			b -> b.bucket(TestObjectStorage.BUCKET).prefix(storagePrefix)
		).contents().stream().toList();

		for(var object : objects) {
			s3Client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(object.key())
					.build()
			);
		}
	}

	/**
	 * Push one generation holding a single committed file, as a node that
	 * indexes would leave behind for the others to pull.
	 */
	private void pushGeneration(String name) throws Exception {
		var generation = IndexName.parse(name);
		var path = Files.createDirectories(localPath.resolve(name));
		Files.writeString(path.resolve("segments_1"), "contents of " + name);

		provider.createSync(generation, path).push(Set.of("segments_1"));
	}

	/**
	 * A generation is stored under the index it belongs to, so its files can
	 * never collide with those of another generation of the same index.
	 */
	@Test
	void testGenerationIsStoredBeneathItsIndex() throws Exception {
		pushGeneration("books@1");

		var keys = s3Client.listObjectsV2Paginator(
			b -> b.bucket(TestObjectStorage.BUCKET).prefix(storagePrefix)
		).contents().stream()
			.map(object -> object.key())
			.toList();

		assertThat(
			keys,
			containsInAnyOrder(
				storagePrefix + "/indexes/books/1/manifest.ef.bin",
				storagePrefix + "/indexes/books/1/e1/segments_1"
			)
		);
	}

	/**
	 * Nesting the generations under their index is what keeps the shared prefix
	 * naming indexes: however many generations stand under one - which is what a
	 * rollout leaves for a while - the storage reports the index once.
	 */
	@Test
	void testIndexIsOneEntryWhateverGenerationsItHolds() throws Exception {
		pushGeneration("books@1");
		pushGeneration("books@2");
		pushGeneration("authors@1");

		var prefix = storagePrefix + "/indexes/";
		var found = s3Client.listObjectsV2Paginator(
			b -> b.bucket(TestObjectStorage.BUCKET).prefix(prefix).delimiter("/")
		).commonPrefixes().stream()
			.map(commonPrefix -> commonPrefix.prefix())
			.toList();

		assertThat(found, containsInAnyOrder(prefix + "books/", prefix + "authors/"));
	}

	/**
	 * A node that indexes checks at startup that the storage enforces
	 * conditional writes, and leaves nothing behind when it does.
	 */
	@Test
	void testIndexerStartsAgainstEnforcingStorage() throws Exception {
		newProvider(true);

		var leftovers = s3Client.listObjectsV2Paginator(
			b -> b.bucket(TestObjectStorage.BUCKET).prefix(storagePrefix)
		).contents().stream().toList();

		assertThat(leftovers, emptyIterable());
	}

	/**
	 * A storage from before conditional writes ignores the condition instead
	 * of refusing the request, which would leave an indexer running unfenced
	 * without anyone noticing. Accepting a write whose condition does not hold
	 * is what gives such a storage away.
	 */
	@Test
	void testIndexerIsRefusedWhenConditionsAreIgnored() throws Exception {
		var ignoringClient = Mockito.mock(S3Client.class);

		var e = assertThrows(
			IOException.class,
			() -> ObjectStorage.verifyConditionalWrites(
				ignoringClient,
				TestObjectStorage.BUCKET,
				storagePrefix + "/.conditional-write-probe"
			)
		);

		assertThat(e.getMessage(), containsString("conditional writes"));
	}
}
