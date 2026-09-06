package se.l4.exofind.engine.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/**
 * What {@link GoogleCredentialsInterceptor} leaves on a request by the time it
 * is sent.
 */
public class GoogleCredentialsInterceptorTest {
	/**
	 * The token has to survive to the point the request is sent. The SDK signs
	 * a request after the interceptors have changed it, and a signature would
	 * replace the token, so this checks the header of the request the client
	 * is about to transmit.
	 */
	@Test
	void testTokenIsSent() throws IOException {
		var captured = new AtomicReference<SdkHttpRequest>();
		var credentials = GoogleCredentials.create(new AccessToken(
			"test-token",
			Date.from(Instant.now().plus(Duration.ofHours(1)))
		));

		var client = S3Client.builder()
			.credentialsProvider(AnonymousCredentialsProvider.create())
			.region(Region.US_EAST_1)
			.endpointOverride(URI.create("http://localhost:1"))
			.forcePathStyle(true)
			.overrideConfiguration(o -> {
				o.retryStrategy(AwsRetryStrategy.doNotRetry());
				o.addExecutionInterceptor(interceptorOf(credentials));
				o.addExecutionInterceptor(new ExecutionInterceptor() {
					@Override
					public void beforeTransmission(
						Context.BeforeTransmission context,
						ExecutionAttributes attributes
					) {
						captured.set(context.httpRequest());
					}
				});
			})
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.build();

		try {
			client.headObject(
				HeadObjectRequest.builder().bucket("bucket").key("key").build()
			);
		} catch(Exception e) {
			// Nothing answers on the endpoint. The request is what this checks.
		}

		assertThat(captured.get(), is(notNullValue()));
		assertThat(
			captured.get().firstMatchingHeader("Authorization"),
			is(Optional.of("Bearer test-token"))
		);
	}

	private static GoogleCredentialsInterceptor interceptorOf(GoogleCredentials credentials) {
		try {
			return new GoogleCredentialsInterceptor(credentials);
		} catch(IOException e) {
			throw new AssertionError(e);
		}
	}
}
