package se.l4.exofind.engine.storage;

import java.io.IOException;

import com.google.auth.oauth2.GoogleCredentials;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;

/**
 * Authorizes requests to Google Cloud Storage with an access token in place of
 * a signature.
 *
 * <p>The XML API accepts either a signature made with an interoperability key
 * pair, which {@link StorageAuth.Static} sends, or an OAuth 2.0 token in the
 * {@code Authorization} header. A token comes from the identity the node
 * already runs as, so a deployment on Google Cloud holds no secret of its own.
 *
 * <p>{@link StorageAuth.Gcp} hands the client anonymous credentials, which
 * leaves the request unsigned, and this puts the token on it.
 *
 * <p>The token comes from the application default credentials of the
 * environment: the identity a workload runs as, a service account key file
 * named by {@code GOOGLE_APPLICATION_CREDENTIALS}, a federated identity, or
 * the credentials of a developer signed in with {@code gcloud}. The library
 * renews a token before it expires, so the engine holds no renewal of its own.
 *
 * <p>Safe for concurrent use.
 */
public class GoogleCredentialsInterceptor implements ExecutionInterceptor {
	/**
	 * Scope the token is asked for. Reading and writing objects covers
	 * everything the engine does: it creates no bucket and changes no policy.
	 */
	private static final String SCOPE = "https://www.googleapis.com/auth/devstorage.read_write";

	private final GoogleCredentials credentials;

	/**
	 * Take the credentials of the environment and mint a token from them. The
	 * token is asked for here so that a node without credentials stops at
	 * startup instead of at its first request.
	 *
	 * @throws IOException
	 *   if the environment holds no credentials, or no token could be minted
	 *   from them
	 */
	public GoogleCredentialsInterceptor() throws IOException {
		this(GoogleCredentials.getApplicationDefault());
	}

	/**
	 * Take named credentials, for a test that holds its own.
	 *
	 * @param credentials
	 * @throws IOException
	 *   if no token could be minted from them
	 */
	GoogleCredentialsInterceptor(GoogleCredentials credentials) throws IOException {
		this.credentials = credentials.createScopedRequired()
			? credentials.createScoped(SCOPE)
			: credentials;

		this.credentials.getRequestMetadata();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws SdkClientException
	 *   if no current token could be got, such as when a renewal fails
	 */
	@Override
	public SdkHttpRequest modifyHttpRequest(
		Context.ModifyHttpRequest context,
		ExecutionAttributes attributes
	) {
		var builder = context.httpRequest().toBuilder();

		try {
			/*
			 * Answers the headers that carry the identity: the token, and the
			 * project to bill where the credentials name one. The library
			 * renews the token as it nears expiry.
			 */
			for(var header : credentials.getRequestMetadata().entrySet()) {
				builder.putHeader(header.getKey(), header.getValue());
			}
		} catch(IOException e) {
			throw SdkClientException.create(
				"Unable to get a Google Cloud access token; " + e.getMessage(), e
			);
		}

		return builder.build();
	}
}
