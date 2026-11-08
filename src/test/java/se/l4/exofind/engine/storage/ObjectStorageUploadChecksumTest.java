package se.l4.exofind.engine.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.registry.IndexEntry;
import se.l4.exofind.engine.index.registry.IndexRegistryStore;
import se.l4.exofind.engine.index.registry.ObjectStorageRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryStorage;
import se.l4.exofind.engine.index.state.TestObjectStorage;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Tests that the storage refuses an upload whose body changed on its way
 * there, for example through a faulty proxy on a connection without TLS.
 */
public class ObjectStorageUploadChecksumTest {
	private String prefix;
	private ObjectStorage damaging;

	@BeforeEach
	void setup() throws IOException {
		prefix = "test" + RandomStringUtils.insecure().nextAlphabetic(10);
		damaging = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(prefix),
			false,
			new DamagingInterceptor()
		);
	}

	/**
	 * A put that states no checksum of its own is sent with one, so the
	 * storage can refuse a body that does not match it.
	 */
	@Test
	void aPutChangedInTransitIsRefused() {
		// Given
		var key = prefix + "/object";

		// When
		var e = assertThrows(
			S3Exception.class,
			() -> damaging.client().putObject(
				PutObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(key)
					.build(),
				RequestBody.fromString("contents", StandardCharsets.UTF_8)
			)
		);

		// Then
		assertThat(e.statusCode(), is(400));
		assertThat(exists(key), is(false));
	}

	/**
	 * A put that states its own checksum is sent with that checksum, and not
	 * with one computed from the body.
	 */
	@Test
	void aPutWithItsOwnChecksumKeepsIt() throws IOException {
		// Given
		var key = prefix + "/object";
		var wrong = Base64.getEncoder().encodeToString(new byte[16]);
		var plain = new ObjectStorage(
			Optional.of(TestObjectStorage.url()),
			TestObjectStorage.auth(),
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of(prefix),
			false
		);

		// When
		var e = assertThrows(
			S3Exception.class,
			() -> plain.client().putObject(
				PutObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(key)
					.contentMD5(wrong)
					.build(),
				RequestBody.fromString("contents", StandardCharsets.UTF_8)
			)
		);

		// Then
		assertThat(e.statusCode(), is(400));
		assertThat(exists(key), is(false));
	}

	/**
	 * A registry write whose body changed on the way is reported as a failure
	 * and leaves the registry as it was. A registry that does not parse, or
	 * parses into other indexes, would hide every index of the deployment.
	 */
	@Test
	void aRegistryWriteChangedInTransitFails() throws IOException {
		// Given
		var registry = new ObjectStorageRegistryStorage(damaging);
		var indexes = IndexRegistryStore.newBuilder()
			.addIndexes(IndexEntry.newBuilder().setName("books"))
			.build();

		// When
		assertThrows(IOException.class, () -> registry.write(indexes, null));

		// Then
		assertThat(registry.read(null), instanceOf(RegistryStorage.Read.Absent.class));
	}

	private boolean exists(String key) {
		try {
			TestObjectStorage.client().headObject(
				HeadObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(key)
					.build()
			);
			return true;
		} catch(S3Exception e) {
			if(e.statusCode() == 404) {
				return false;
			}

			throw e;
		}
	}

	/**
	 * Changes one byte of every upload body after the client has prepared
	 * the request.
	 */
	private static class DamagingInterceptor implements ExecutionInterceptor {
		@Override
		public Optional<RequestBody> modifyHttpContent(
			Context.ModifyHttpRequest context,
			ExecutionAttributes attributes
		) {
			if(
				!(context.request() instanceof PutObjectRequest)
					|| context.requestBody().isEmpty()
			) {
				return context.requestBody();
			}

			try(var in = context.requestBody().get().contentStreamProvider().newStream()) {
				var bytes = in.readAllBytes();
				if(bytes.length == 0) {
					return context.requestBody();
				}

				bytes[bytes.length / 2] ^= 0x01;
				return Optional.of(RequestBody.fromBytes(bytes));
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
