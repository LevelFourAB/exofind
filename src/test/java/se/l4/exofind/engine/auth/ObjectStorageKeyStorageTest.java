package se.l4.exofind.engine.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import se.l4.exofind.engine.index.state.TestObjectStorage;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Tests for what the key store object makes of a write whose answer is a
 * server error. A proxy in front of the storage can answer that way after the
 * storage applied the write, so the status alone does not say whether the
 * keys changed.
 */
public class ObjectStorageKeyStorageTest {
	private static final String ROOT_KEY = "a-long-random-root-key";

	ObjectStorage storage;
	ObjectStorageKeyStorage keyStorage;

	private final List<Keys> started = new ArrayList<>();

	@BeforeEach
	void setup() throws IOException {
		storage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of("test" + RandomStringUtils.insecure().nextAlphabetic(10)),
			false
		);

		keyStorage = new ObjectStorageKeyStorage(storage);
	}

	@AfterEach
	void cleanup() {
		started.forEach(Keys::stop);
		started.clear();
	}

	@Test
	void aWriteThatLandedBeforeAServerErrorCountsAsWritten() throws IOException {
		// Given
		var version = keyStorage.write(KeyStore.getDefaultInstance(), null);
		var wanted = KeyStore.newBuilder()
			.addKeys(KeyDef.newBuilder().setId("k1").setDescription("the loader"))
			.build();

		// When
		var written = answeringWithServerErrors().write(wanted, version);

		// Then
		assertThat(written, is(notNullValue()));
		var stored = (KeyStorage.Read.Loaded) keyStorage.read(null);
		assertThat(stored.keys(), is(wanted));
		assertThat(stored.version(), is(written));
	}

	/**
	 * A rotate that the storage applied must either hand the caller the new
	 * credential or leave the old one working. Anything else leaves a key
	 * that nobody can use.
	 */
	@Test
	void aRotateThatLandedBeforeAServerErrorLeavesAWorkingCredential() {
		// Given
		var created = keys(keyStorage).create(
			"the loader",
			Lists.immutable.of(
				new Grant(Sets.immutable.of(Permission.SEARCH), Lists.immutable.of("books"))
			),
			null
		);
		var rotating = keys(answeringWithServerErrors());

		// When
		String working;
		try {
			working = rotating.rotate(created.key().id()).credential();
		} catch(KeyStorageException e) {
			working = created.credential();
		}

		// Then
		var fresh = keys(keyStorage);
		assertThat(
			"the credential the caller holds after the rotate still resolves",
			resolves(fresh, working),
			is(true)
		);
	}

	private Keys keys(KeyStorage storage) {
		var instance = new Keys(
			storage,
			AuthMode.KEYS,
			Optional.of(ROOT_KEY),
			Optional.empty(),
			Duration.ofSeconds(30)
		);

		started.add(instance);
		return instance;
	}

	private static boolean resolves(Keys keys, String credential) {
		try {
			keys.resolve("Bearer " + credential);
			return true;
		} catch(UnauthenticatedException e) {
			return false;
		}
	}

	/**
	 * Key storage over a client that lets every put through and then answers
	 * it with a gateway timeout.
	 */
	private ObjectStorageKeyStorage answeringWithServerErrors() {
		var client = Mockito.spy(storage.client());

		Mockito.doAnswer(invocation -> {
			invocation.callRealMethod();
			throw S3Exception.builder().statusCode(504).message("simulated gateway timeout").build();
		}).when(client).putObject(
			ArgumentMatchers.any(PutObjectRequest.class),
			ArgumentMatchers.any(RequestBody.class)
		);

		var wrapped = Mockito.spy(storage);
		Mockito.doReturn(client).when(wrapped).client();

		return new ObjectStorageKeyStorage(wrapped);
	}
}
