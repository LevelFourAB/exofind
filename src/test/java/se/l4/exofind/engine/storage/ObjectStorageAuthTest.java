package se.l4.exofind.engine.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.state.TestObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Opening {@link ObjectStorage} with each {@link StorageAuth} source against
 * a running storage: what the source hands the client has to sign requests
 * the storage accepts, and a source that cannot must say so before the
 * storage is handed to anything.
 */
public class ObjectStorageAuthTest {
	private static final String PREFIX = "object-storage-auth-test";

	/**
	 * A credentials file in the format the AWS tools write, read through the
	 * file source, signs the way the key pair in it would.
	 */
	@Test
	void testFileSourceSignsRequests(@TempDir Path dir) throws IOException {
		var file = dir.resolve("credentials");
		Files.writeString(file, """
			[default]
			aws_access_key_id = %s
			aws_secret_access_key = %s
			""".formatted(TestObjectStorage.ACCESS_KEY, TestObjectStorage.SECRET_KEY));

		var storage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			new StorageAuth.File(file, StorageAuth.File.DEFAULT_PROFILE),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(PREFIX + "/file"),
			true
		);

		assertRoundTrip(storage);
	}

	/**
	 * The profile named in the configuration is the one read, so a file
	 * holding several is not read from the top.
	 */
	@Test
	void testFileSourceReadsNamedProfile(@TempDir Path dir) throws IOException {
		var file = dir.resolve("credentials");
		Files.writeString(file, """
			[default]
			aws_access_key_id = someone-else
			aws_secret_access_key = not-accepted

			[exofind]
			aws_access_key_id = %s
			aws_secret_access_key = %s
			""".formatted(TestObjectStorage.ACCESS_KEY, TestObjectStorage.SECRET_KEY));

		var storage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			new StorageAuth.File(file, "exofind"),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(PREFIX + "/profile"),
			true
		);

		assertRoundTrip(storage);
	}

	/**
	 * A file without the profile is found out when the storage opens, with
	 * the source named, rather than by the first request through it.
	 */
	@Test
	void testFileSourceWithoutProfileRefusesToOpen(@TempDir Path dir) throws IOException {
		var file = dir.resolve("credentials");
		Files.writeString(file, """
			[other]
			aws_access_key_id = someone-else
			aws_secret_access_key = not-accepted
			""");

		var e = assertThrows(IOException.class, () -> new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			new StorageAuth.File(file, StorageAuth.File.DEFAULT_PROFILE),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(PREFIX + "/missing-profile"),
			false
		));

		assertThat(e.getMessage(), containsString("'file'"));
	}

	/**
	 * The static source with a key pair the storage knows is what every
	 * deployment used before there were other sources, and has to keep
	 * opening the same way.
	 */
	@Test
	void testStaticSourceSignsRequests() throws IOException {
		var storage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			new StorageAuth.Static(
				TestObjectStorage.ACCESS_KEY,
				TestObjectStorage.SECRET_KEY,
				Optional.empty()
			),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(PREFIX + "/static"),
			true
		);

		assertRoundTrip(storage);
	}

	/**
	 * The Google Cloud source authorizes with a token only Google Cloud
	 * Storage accepts. A node pointed at another storage says so at startup,
	 * before it asks Google Cloud for a token it cannot use.
	 */
	@Test
	void testGcpSourceDemandsGoogleEndpoint() {
		var e = assertThrows(IOException.class, () -> new ObjectStorage(
			Optional.of("http://storage.example.com:8333"),
			new StorageAuth.Gcp(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(PREFIX + "/gcp"),
			false
		));

		assertThat(e.getMessage(), containsString("'gcp'"));
		assertThat(e.getMessage(), containsString(GoogleStorageInterceptor.HOST));
	}

	private static void assertRoundTrip(ObjectStorage storage) {
		var key = storage.rootObject("round-trip");
		var client = storage.client();

		client.putObject(
			PutObjectRequest.builder().bucket(storage.bucket()).key(key).build(),
			RequestBody.fromString("signed")
		);

		var read = client.getObjectAsBytes(
			GetObjectRequest.builder().bucket(storage.bucket()).key(key).build()
		).asString(StandardCharsets.UTF_8);

		assertThat(read, is("signed"));
	}
}
