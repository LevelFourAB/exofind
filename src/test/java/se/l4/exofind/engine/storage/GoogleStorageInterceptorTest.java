package se.l4.exofind.engine.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * What {@link GoogleStorageInterceptor} makes of the conditions the engine
 * states, and of the versions the storage answers with.
 */
public class GoogleStorageInterceptorTest {
	private static final String IF_GENERATION_MATCH = "x-goog-if-generation-match";
	private static final String GENERATION = "x-goog-generation";

	private final GoogleStorageInterceptor interceptor = new GoogleStorageInterceptor();

	/**
	 * The first write of an object demands that no object is there, which
	 * Google Cloud Storage states as generation zero.
	 */
	@Test
	void testWriteOfAbsentObject() {
		var request = translate(write().putHeader("If-None-Match", "*"));

		assertThat(request.firstMatchingHeader(IF_GENERATION_MATCH), is(Optional.of("0")));
		assertThat(request.firstMatchingHeader("If-None-Match"), is(Optional.empty()));
	}

	/**
	 * A write on a version the node holds demands the generation that version
	 * carries.
	 */
	@Test
	void testWriteOnKnownVersion() {
		var request = translate(write().putHeader("If-Match", "\"d41d8cd98f00b204@1712345678901234\""));

		assertThat(
			request.firstMatchingHeader(IF_GENERATION_MATCH),
			is(Optional.of("1712345678901234"))
		);
		assertThat(request.firstMatchingHeader("If-Match"), is(Optional.empty()));
	}

	/**
	 * A version naming no generation has to be refused by the storage. The
	 * conditional write check states one to see a write refused, and a client
	 * can send one in the {@code If-Match} header of the API.
	 */
	@Test
	void testWriteOnVersionWithoutGeneration() {
		var request = translate(
			write().putHeader("If-Match", "\"00000000000000000000000000000000\"")
		);

		assertThat(request.firstMatchingHeader(IF_GENERATION_MATCH), is(Optional.of("1")));
	}

	/**
	 * The files of an index are written without a condition, and stay that
	 * way.
	 */
	@Test
	void testWriteWithoutCondition() {
		var request = translate(write());

		assertThat(request.firstMatchingHeader(IF_GENERATION_MATCH), is(Optional.empty()));
	}

	/**
	 * A poll asks for the ETag part of the version, which Google Cloud
	 * Storage compares on a read, so an unchanged object is still answered
	 * {@code 304} with no body.
	 */
	@Test
	void testReadComparesTheETag() {
		var request = translate(
			read().putHeader("If-None-Match", "\"d41d8cd98f00b204@1712345678901234\"")
		);

		assertThat(
			request.firstMatchingHeader("If-None-Match"),
			is(Optional.of("\"d41d8cd98f00b204\""))
		);
		assertThat(request.firstMatchingHeader(IF_GENERATION_MATCH), is(Optional.empty()));
	}

	/**
	 * The version of what a read answered carries both parts, so that the
	 * next read and the next write each have the part they state.
	 */
	@Test
	void testVersionOfARead() {
		var response = (GetObjectResponse) interceptor.modifyResponse(
			InterceptorContext.builder()
				.request(GetObjectRequest.builder().bucket("bucket").key("key").build())
				.response(GetObjectResponse.builder().eTag("\"d41d8cd98f00b204\"").build())
				.httpResponse(
					SdkHttpResponse.builder()
						.statusCode(200)
						.putHeader(GENERATION, "1712345678901234")
						.build()
				)
				.build(),
			new ExecutionAttributes()
		);

		assertThat(response.eTag(), is("\"d41d8cd98f00b204@1712345678901234\""));
	}

	/**
	 * The version of what a write left carries the new generation, which the
	 * next write of the same object states.
	 */
	@Test
	void testVersionOfAWrite() {
		var response = (PutObjectResponse) interceptor.modifyResponse(
			written(SdkHttpResponse.builder()
				.statusCode(200)
				.putHeader(GENERATION, "1712345678901234")),
			new ExecutionAttributes()
		);

		assertThat(response.eTag(), is("\"d41d8cd98f00b204@1712345678901234\""));
	}

	/**
	 * A write answered without a generation leaves nothing for the next write
	 * to state, and every later write of that object would be refused. Say so
	 * instead.
	 */
	@Test
	void testWriteAnsweredWithoutGeneration() {
		var e = assertThrows(IllegalStateException.class, () -> interceptor.modifyResponse(
			written(SdkHttpResponse.builder().statusCode(200)),
			new ExecutionAttributes()
		));

		assertThat(e.getMessage(), containsString(GENERATION));
	}

	/**
	 * The endpoint decides whether the conditions need translation, both when
	 * the bucket is in the path and when it is in the host name.
	 */
	@Test
	void testGoogleStorageIsFoundByEndpoint() {
		assertThat(
			GoogleStorageInterceptor.isGoogleStorage(
				Optional.of("https://storage.googleapis.com")
			),
			is(true)
		);
		assertThat(
			GoogleStorageInterceptor.isGoogleStorage(
				Optional.of("https://bucket.storage.googleapis.com")
			),
			is(true)
		);
	}

	/**
	 * Every other endpoint is left to state its conditions the way AWS S3
	 * does, including AWS S3 itself, which names no endpoint.
	 */
	@Test
	void testOtherEndpointsAreNotGoogleStorage() {
		assertThat(GoogleStorageInterceptor.isGoogleStorage(Optional.empty()), is(false));
		assertThat(GoogleStorageInterceptor.isGoogleStorage(Optional.of("")), is(false));
		assertThat(
			GoogleStorageInterceptor.isGoogleStorage(Optional.of("http://localhost:8333")),
			is(false)
		);
		assertThat(
			GoogleStorageInterceptor.isGoogleStorage(
				Optional.of("https://storage.googleapis.com.example.com")
			),
			is(false)
		);
	}

	private SdkHttpRequest translate(SdkHttpRequest.Builder request) {
		return interceptor.modifyHttpRequest(
			InterceptorContext.builder()
				.request(PutObjectRequest.builder().bucket("bucket").key("key").build())
				.httpRequest(request.build())
				.build(),
			new ExecutionAttributes()
		);
	}

	private static SdkHttpRequest.Builder write() {
		return SdkHttpRequest.builder()
			.method(SdkHttpMethod.PUT)
			.uri(URI.create("https://storage.googleapis.com/bucket/key"));
	}

	private static SdkHttpRequest.Builder read() {
		return SdkHttpRequest.builder()
			.method(SdkHttpMethod.GET)
			.uri(URI.create("https://storage.googleapis.com/bucket/key"));
	}

	private static InterceptorContext written(SdkHttpResponse.Builder httpResponse) {
		return InterceptorContext.builder()
			.request(PutObjectRequest.builder().bucket("bucket").key("key").build())
			.response(PutObjectResponse.builder().eTag("\"d41d8cd98f00b204\"").build())
			.httpResponse(httpResponse.build())
			.build();
	}
}
