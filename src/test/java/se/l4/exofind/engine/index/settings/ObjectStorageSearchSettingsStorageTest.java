package se.l4.exofind.engine.index.settings;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Tests for what the settings object makes of a write that a proxy in front
 * of the storage answers with a server error. The status does not say what
 * the storage decided, so the outcome is told by reading the settings back.
 */
public class ObjectStorageSearchSettingsStorageTest {
	ObjectStorage storage;
	ObjectStorageSearchSettingsStorage settingsStorage;

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

		settingsStorage = new ObjectStorageSearchSettingsStorage(storage);
	}

	private static SearchSettingsStore settingsOf(String... synonymSets) {
		var builder = SearchSettingsStore.newBuilder();
		for(var name : synonymSets) {
			builder.putSynonyms(name, QuerySynonyms.getDefaultInstance());
		}

		return builder.build();
	}

	/**
	 * A settings storage over a client that answers every write with a
	 * gateway timeout, optionally after letting the write itself through.
	 *
	 * @param landing
	 *   whether the write reaches the storage before the answer is lost
	 */
	private ObjectStorageSearchSettingsStorage timingOut(boolean landing) {
		var client = Mockito.spy(storage.client());

		Mockito.doAnswer(invocation -> {
			if(landing) {
				invocation.callRealMethod();
			}

			throw S3Exception.builder().statusCode(504).message("simulated gateway timeout").build();
		})
			.when(client)
			.putObject(
				ArgumentMatchers.any(PutObjectRequest.class),
				ArgumentMatchers.any(RequestBody.class)
			);

		var wrapped = Mockito.spy(storage);
		Mockito.doReturn(client).when(wrapped).client();

		return new ObjectStorageSearchSettingsStorage(wrapped);
	}

	private SearchSettingsStorage.Read.Loaded current() throws IOException {
		var read = settingsStorage.read("books", null);
		assertThat(read, instanceOf(SearchSettingsStorage.Read.Loaded.class));
		return (SearchSettingsStorage.Read.Loaded) read;
	}

	/**
	 * The storage finished the write after the proxy gave up on it. The
	 * write went through, and reporting a failure would send the caller back
	 * to make a change that is already in place.
	 */
	@Test
	void testWriteThatLandedBeforeAServerErrorCountsAsWritten() throws IOException {
		var version = settingsStorage.write("books", settingsOf("colors"), null);
		var wanted = settingsOf("colors", "sizes");

		var written = timingOut(true).write("books", wanted, version);

		assertThat(written, is(notNullValue()));
		assertThat(current().settings(), is(wanted));
		assertThat(current().version(), is(written));
	}

	/**
	 * A server error for a write that never arrived is still a failure.
	 */
	@Test
	void testWriteAnsweredWithAServerErrorThatNeverArrivedIsReportedAsAFailure()
		throws IOException
	{
		var version = settingsStorage.write("books", settingsOf("colors"), null);

		assertThrows(
			IOException.class,
			() -> timingOut(false).write("books", settingsOf("colors", "sizes"), version)
		);

		assertThat(current().settings(), is(settingsOf("colors")));
	}
}
