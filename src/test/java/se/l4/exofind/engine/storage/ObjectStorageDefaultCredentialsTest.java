package se.l4.exofind.engine.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import se.l4.exofind.engine.index.state.TestObjectStorage;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * The default credential chain driving a real storage: credentials the chain
 * resolves sign requests the storage accepts.
 *
 * <p>The chain reads the {@code aws.accessKeyId} and {@code aws.secretAccessKey}
 * system properties before any other source, so publishing the test storage's
 * keys there proves chain resolution and signing end to end without depending
 * on what the machine running the tests holds in its environment. Isolated
 * because the properties are visible to the whole JVM while test classes run
 * concurrently.
 */
@Isolated
public class ObjectStorageDefaultCredentialsTest {
	private static String previousAccessKey;
	private static String previousSecretKey;

	@BeforeAll
	public static void publishKeysToTheChain() {
		// Resolved first so the bucket exists; url() alone does not create it
		TestObjectStorage.client();

		previousAccessKey = System.setProperty("aws.accessKeyId", TestObjectStorage.ACCESS_KEY);
		previousSecretKey = System.setProperty("aws.secretAccessKey", TestObjectStorage.SECRET_KEY);
	}

	@AfterAll
	public static void restoreProperties() {
		restore("aws.accessKeyId", previousAccessKey);
		restore("aws.secretAccessKey", previousSecretKey);
	}

	private static void restore(String property, String previous) {
		if(previous == null) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, previous);
		}
	}

	@Test
	public void chainResolvedCredentialsSignRequests() throws IOException {
		var provider = StorageProviders.remoteCredentialsProvider(
			"default",
			Optional.empty(),
			Optional.empty()
		);

		var prefix = "test" + RandomStringUtils.insecure().nextAlphabetic(10);

		/*
		 * indexer=true makes the constructor probe conditional writes with
		 * real signed puts and deletes, so a wrongly signed request fails the
		 * open instead of an assertion further down.
		 */
		try(var storage = new ObjectStorage(
			TestObjectStorage.url(),
			provider,
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(prefix),
			true
		)) {
			var key = prefix + "/chain-probe";

			storage.client().putObject(
				PutObjectRequest.builder().bucket(TestObjectStorage.BUCKET).key(key).build(),
				RequestBody.fromString("signed with chain credentials", StandardCharsets.UTF_8)
			);

			var read = storage.client().getObjectAsBytes(
				GetObjectRequest.builder().bucket(TestObjectStorage.BUCKET).key(key).build()
			).asUtf8String();

			assertThat(read, is("signed with chain credentials"));

			storage.client().deleteObject(
				DeleteObjectRequest.builder().bucket(TestObjectStorage.BUCKET).key(key).build()
			);
		}
	}

	@Test
	public void closingTheStorageClosesTheProviderItWasOpenedWith() throws IOException {
		var closed = new AtomicBoolean(false);

		class ClosableProvider implements AwsCredentialsProvider, SdkAutoCloseable {
			@Override
			public AwsCredentials resolveCredentials() {
				return AwsBasicCredentials.create(
					TestObjectStorage.ACCESS_KEY,
					TestObjectStorage.SECRET_KEY
				);
			}

			@Override
			public void close() {
				closed.set(true);
			}
		}

		var storage = new ObjectStorage(
			TestObjectStorage.url(),
			new ClosableProvider(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of("test" + RandomStringUtils.insecure().nextAlphabetic(10)),
			false
		);

		storage.close();

		assertThat(closed.get(), is(true));
	}
}
