package se.l4.exofind.engine.index.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import se.l4.exofind.engine.index.state.TestObjectStorage;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
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
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
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

	/**
	 * A poll on the version the node holds is answered without a body, and
	 * the node keeps what it has.
	 */
	@Test
	void testReadOnCurrentVersionIsUnchanged() throws IOException {
		var version = registryStorage.write(registryOf("books"), null);

		assertThat(
			registryStorage.read(version),
			is(instanceOf(RegistryStorage.Read.Unchanged.class))
		);
	}

	/**
	 * A storage that names versions by more than the entity tag can answer a
	 * poll {@code 304} while the object has moved to a version it refuses the
	 * one the node holds against. Google Cloud Storage does so after a
	 * rewrite to the same bytes. The registry is then read again, so the node
	 * ends up holding the version the storage does instead of one it can not
	 * write on.
	 */
	@Test
	void testReadAnsweredWithAnotherVersionIsReadAgain() throws IOException {
		var reads = new AtomicInteger();
		var storage = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of("test" + RandomStringUtils.insecure().nextAlphabetic(10)),
			false,
			new ExecutionInterceptor() {
				@Override
				public SdkHttpResponse modifyHttpResponse(
					Context.ModifyHttpResponse context,
					ExecutionAttributes attributes
				) {
					if(context.request() instanceof GetObjectRequest) {
						reads.incrementAndGet();
					}

					var response = context.httpResponse();
					if(response.statusCode() != 304) {
						return response;
					}

					// The same bytes, at a version the node has not seen
					var etag = response.firstMatchingHeader("ETag").orElseThrow();
					return response.toBuilder()
						.removeHeader("ETag")
						.putHeader("ETag", etag.replaceAll("\"$", "@2\""))
						.build();
				}
			}
		);
		var registryStorage = new ObjectStorageRegistryStorage(storage);

		var wanted = registryOf("books");
		var version = registryStorage.write(wanted, null);
		reads.set(0);

		var read = registryStorage.read(version);

		assertThat(read, is(instanceOf(RegistryStorage.Read.Loaded.class)));
		assertThat(((RegistryStorage.Read.Loaded) read).indexes(), is(wanted));
		assertThat(((RegistryStorage.Read.Loaded) read).version(), is(version));
		assertThat(reads.get(), is(2));
	}

	private String currentVersion() throws IOException {
		return ((RegistryStorage.Read.Loaded) registryStorage.read(null)).version();
	}

	private IndexRegistryStore currentIndexes() throws IOException {
		return ((RegistryStorage.Read.Loaded) registryStorage.read(null)).indexes();
	}
}
