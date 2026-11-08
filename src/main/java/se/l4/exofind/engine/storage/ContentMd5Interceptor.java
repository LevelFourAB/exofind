package se.l4.exofind.engine.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Adds a {@code Content-MD5} header to every upload that does not state one,
 * so the storage refuses a body that changed on its way there.
 *
 * <p>The client sends no request checksum of its own, see
 * {@link ObjectStorage}. Without this header, a faulty proxy on a connection
 * without TLS can change a body, and the storage stores the changed bytes.
 * The registry, the keys, the leadership table and the other small objects
 * are parsed as a whole, so a changed byte makes them unreadable or gives
 * them another meaning. All S3-compatible storages accept
 * {@code Content-MD5}.
 *
 * <p>Only bodies of at most {@link #MAX_BODY} bytes are read for the
 * checksum, because the body is read once more to send it. An upload of a
 * larger body states its own checksum, as {@code ObjectStorageSync} does for
 * the files of an index. An upload that states its own checksum keeps it.
 *
 * <p>{@link ObjectStorage} registers this before every other interceptor, so
 * the checksum is of the body as the caller gave it.
 *
 * <p>Safe for concurrent use.
 */
public class ContentMd5Interceptor implements ExecutionInterceptor {
	/**
	 * The largest body this reads to compute a checksum.
	 */
	static final long MAX_BODY = 8 * 1024 * 1024;

	private static final String HEADER = "Content-MD5";

	@Override
	public SdkHttpRequest modifyHttpRequest(
		Context.ModifyHttpRequest context,
		ExecutionAttributes attributes
	) {
		var request = context.httpRequest();
		if(
			!(context.request() instanceof PutObjectRequest)
				|| request.firstMatchingHeader(HEADER).isPresent()
				|| context.requestBody().isEmpty()
		) {
			return request;
		}

		var body = context.requestBody().get();
		var length = body.optionalContentLength();
		if(length.isEmpty() || length.get() > MAX_BODY) {
			return request;
		}

		var digest = newMd5();
		try(var in = body.contentStreamProvider().newStream()) {
			var buffer = new byte[8192];
			int read;
			while((read = in.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}

		return request.toBuilder()
			.putHeader(HEADER, Base64.getEncoder().encodeToString(digest.digest()))
			.build();
	}

	private static MessageDigest newMd5() {
		try {
			return MessageDigest.getInstance("MD5");
		} catch(NoSuchAlgorithmException e) {
			// Every Java platform must support MD5
			throw new IllegalStateException(e);
		}
	}
}
