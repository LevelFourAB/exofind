package se.l4.exofind.engine.index.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Optional;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import se.l4.exofind.engine.index.state.TestObjectStorage;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Tests for what the registry object makes of a write whose answer never came
 * back. The storage can take a write and then drop the connection, which
 * arrives as a failure and says nothing about what was decided - so the three
 * outcomes have to be told apart by reading the registry back.
 */
public class ObjectStorageRegistryStorageTest {
	ObjectStorage storage;
	ObjectStorageRegistryStorage registryStorage;

	@BeforeEach
	void setup() throws IOException {
		storage = new ObjectStorage(
			TestObjectStorage.url(),
			TestObjectStorage.ACCESS_KEY,
			TestObjectStorage.SECRET_KEY,
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of("test" + RandomStringUtils.insecure().nextAlphabetic(10)),
			false
		);

		registryStorage = new ObjectStorageRegistryStorage(storage);
	}

	private static IndexRegistryStore registryOf(String... names) {
		var builder = IndexRegistryStore.newBuilder();
		for(var name : names) {
			builder.addIndexes(IndexEntry.newBuilder().setName(name));
		}

		return builder.build();
	}

	/**
	 * A registry storage over a client that fails every write, optionally
	 * after letting the write itself through.
	 *
	 * @param landing
	 *   whether the write reaches the storage before the connection is dropped
	 */
	private ObjectStorageRegistryStorage droppingAnswers(boolean landing) {
		var client = Mockito.spy(storage.client());

		var failure = Mockito.doAnswer(invocation -> {
			if(landing) {
				invocation.callRealMethod();
			}

			throw SdkClientException.create("simulated connection reset");
		});

		failure.when(client)
			.putObject(
				ArgumentMatchers.any(PutObjectRequest.class),
				ArgumentMatchers.any(RequestBody.class)
			);

		var wrapped = Mockito.spy(storage);
		Mockito.doReturn(client).when(wrapped).client();

		return new ObjectStorageRegistryStorage(wrapped);
	}

	/**
	 * The write went through and only the answer was lost. Reporting a failure
	 * would send whoever asked for the change back to make it again, where a
	 * create is answered with the name already existing and a removal with the
	 * generation being gone.
	 */
	@Test
	void testWriteThatLandedBeforeTheAnswerWasLostCountsAsWritten() throws IOException {
		var wanted = registryOf("books");

		var version = droppingAnswers(true).write(wanted, null);

		assertThat(version, is(notNullValue()));
		assertThat(currentIndexes(), is(wanted));
		assertThat(currentVersion(), is(version));
	}

	/**
	 * The write never arrived, so the registry still stands where it did and
	 * the caller may make the change again. Anything else would leave a change
	 * silently dropped.
	 */
	@Test
	void testWriteThatNeverArrivedIsReportedAsAFailure() throws IOException {
		registryStorage.write(registryOf("books"), null);

		assertThrows(
			IOException.class,
			() -> droppingAnswers(false).write(registryOf("books", "films"), currentVersion())
		);

		assertThat(currentIndexes(), is(registryOf("books")));
	}

	/**
	 * Another node wrote the registry first, which the version the write was
	 * conditioned on no longer standing is what says. That is a lost race
	 * rather than a failure, so the caller rereads and builds its change on
	 * what is there now.
	 */
	@Test
	void testWriteLostToAnotherNodeIsReportedAsRefused() throws IOException {
		registryStorage.write(registryOf("books"), null);
		var staleVersion = currentVersion();

		// Another node gets there first, moving the registry off that version
		registryStorage.write(registryOf("books", "records"), staleVersion);

		var version = droppingAnswers(false)
			.write(registryOf("books", "films"), staleVersion);

		assertThat(version, is(nullValue()));
		assertThat(currentIndexes(), is(registryOf("books", "records")));
	}

	private String currentVersion() throws IOException {
		return ((RegistryStorage.Read.Loaded) registryStorage.read(null)).version();
	}

	private IndexRegistryStore currentIndexes() throws IOException {
		return ((RegistryStorage.Read.Loaded) registryStorage.read(null)).indexes();
	}
}
