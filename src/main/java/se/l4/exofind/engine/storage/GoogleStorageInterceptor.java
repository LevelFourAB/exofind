package se.l4.exofind.engine.storage;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Translates conditional writes into the form Google Cloud Storage takes.
 *
 * <p>Google Cloud Storage accepts {@code If-Match} and {@code If-None-Match}
 * on reads only. A write states its condition on the generation of the object,
 * in {@code x-goog-if-generation-match}, where {@code 0} demands that the
 * object does not exist. A response carries the generation of the object in
 * {@code x-goog-generation}.
 *
 * <p>This rewrites the headers of a request on its way out and the version of
 * a response on its way back, so no caller carries a second spelling. A
 * version holds both parts, as {@code "<etag>@<generation>"}. A read compares
 * the ETag part, so a poll of an unchanged object is still answered
 * {@code 304} and carries no body. A write states the generation part. No
 * caller reads either part.
 *
 * <p>{@link ObjectStorage} registers this when the endpoint is Google Cloud
 * Storage, see {@link #isGoogleStorage}. A deployment that reaches the same
 * storage under another host name gets no translation, and its writes then
 * carry a condition the storage does not apply to a write.
 * {@link ObjectStorage#verifyConditionalWrites} stops such a node at startup.
 *
 * <p>Safe for concurrent use.
 */
public class GoogleStorageInterceptor implements ExecutionInterceptor {
	/**
	 * Host the XML API is served under. A bucket can also be named in front
	 * of it, see {@link #isGoogleStorage}.
	 */
	public static final String HOST = "storage.googleapis.com";

	/**
	 * Condition of a write: the generation the object has to carry, or
	 * {@code 0} for an object that has to be absent.
	 */
	private static final String IF_GENERATION_MATCH = "x-goog-if-generation-match";

	/**
	 * Generation of the object a request was answered from.
	 */
	private static final String GENERATION = "x-goog-generation";

	private static final String IF_MATCH = "If-Match";
	private static final String IF_NONE_MATCH = "If-None-Match";

	/**
	 * Value {@code If-None-Match} carries on a write that demands the object
	 * is absent.
	 */
	private static final String ANY = "*";

	/**
	 * Generation stated for a version that names none, so that the write is
	 * refused instead of taken unconditionally.
	 *
	 * <p>Google Cloud Storage derives a generation from the time of the write,
	 * so no object carries this value. Two versions name no generation: the
	 * one the conditional write check makes up to be refused, and one a client
	 * invents in an {@code If-Match} header of the API.
	 */
	private static final String UNMATCHABLE_GENERATION = "1";

	/**
	 * Separates the two parts of a version.
	 */
	private static final char SEPARATOR = '@';

	/**
	 * Whether an endpoint is Google Cloud Storage, and its conditional writes
	 * therefore need translation. True for the host itself and for a bucket
	 * named in front of it.
	 *
	 * @param url
	 *   value of {@code EXOFIND_STORAGE_REMOTE_URL}, empty for AWS S3
	 * @return
	 */
	public static boolean isGoogleStorage(Optional<String> url) {
		return url
			.filter(value -> !value.isBlank())
			.map(GoogleStorageInterceptor::hostOf)
			.filter(host -> host.equals(HOST) || host.endsWith("." + HOST))
			.isPresent();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws IllegalStateException
	 *   if the request carries a condition Google Cloud Storage has no
	 *   equivalent for
	 */
	@Override
	public SdkHttpRequest modifyHttpRequest(
		Context.ModifyHttpRequest context,
		ExecutionAttributes attributes
	) {
		var request = context.httpRequest();

		return switch(request.method()) {
			case PUT, POST, DELETE -> translateWrite(request);
			case GET, HEAD -> translateRead(request);
			default -> request;
		};
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws IllegalStateException
	 *   if a write was answered without a generation, leaving no version for
	 *   a later write of the same object to state its condition on
	 */
	@Override
	public SdkResponse modifyResponse(
		Context.ModifyResponse context,
		ExecutionAttributes attributes
	) {
		var response = context.response();
		var generation = context.httpResponse().firstMatchingHeader(GENERATION);

		if(response instanceof PutObjectResponse put) {
			/*
			 * Reported here because every later write of this object would
			 * otherwise be refused, and a caller cannot tell that apart from
			 * losing a race with another node.
			 */
			if(generation.isEmpty()) {
				throw new IllegalStateException(
					"Google Cloud Storage answered a write without an "
						+ GENERATION + " header, so the version of the object"
						+ " that was written is not known"
				);
			}

			return put.toBuilder()
				.eTag(version(put.eTag(), generation.get()))
				.build();
		}

		if(generation.isEmpty()) {
			return response;
		}

		if(response instanceof GetObjectResponse get) {
			return get.toBuilder().eTag(version(get.eTag(), generation.get())).build();
		}

		if(response instanceof HeadObjectResponse head) {
			return head.toBuilder().eTag(version(head.eTag(), generation.get())).build();
		}

		return response;
	}

	/**
	 * State the condition of a write on the generation of the object.
	 */
	private static SdkHttpRequest translateWrite(SdkHttpRequest request) {
		var ifMatch = request.firstMatchingHeader(IF_MATCH);
		var ifNoneMatch = request.firstMatchingHeader(IF_NONE_MATCH);

		if(ifMatch.isEmpty() && ifNoneMatch.isEmpty()) {
			return request;
		}

		var builder = request.toBuilder();
		removeHeader(builder, request, IF_MATCH);
		removeHeader(builder, request, IF_NONE_MATCH);

		if(ifMatch.isPresent()) {
			builder.putHeader(IF_GENERATION_MATCH, generationOf(ifMatch.get()));
		} else if(ANY.equals(ifNoneMatch.get().trim())) {
			builder.putHeader(IF_GENERATION_MATCH, "0");
		} else {
			/*
			 * No generation says the same as a named tag, and dropping the
			 * condition would take the write unconditionally.
			 */
			throw new IllegalStateException(
				"Google Cloud Storage has no condition matching " + IF_NONE_MATCH
					+ ": " + ifNoneMatch.get()
			);
		}

		return builder.build();
	}

	/**
	 * Ask a read for the ETag part of the version, which the storage compares
	 * the object against.
	 */
	private static SdkHttpRequest translateRead(SdkHttpRequest request) {
		var ifNoneMatch = request.firstMatchingHeader(IF_NONE_MATCH);
		if(ifNoneMatch.isEmpty() || ANY.equals(ifNoneMatch.get().trim())) {
			return request;
		}

		var builder = request.toBuilder();
		removeHeader(builder, request, IF_NONE_MATCH);
		builder.putHeader(IF_NONE_MATCH, etagOf(ifNoneMatch.get()));

		return builder.build();
	}

	/**
	 * Build the version of an object from the two parts a response carries.
	 *
	 * @param etag
	 *   value of the {@code ETag} header, quoted or not
	 * @param generation
	 *   value of the {@code x-goog-generation} header
	 * @return
	 *   version, quoted
	 */
	static String version(String etag, String generation) {
		return '"' + unquote(etag) + SEPARATOR + generation + '"';
	}

	/**
	 * The ETag a version holds, for a read to compare the object against.
	 *
	 * @param version
	 * @return
	 *   ETag, quoted
	 */
	static String etagOf(String version) {
		var value = unquote(version);
		var separator = separatorIn(value);

		return '"' + (separator < 0 ? value : value.substring(0, separator)) + '"';
	}

	/**
	 * The generation a version holds, for a write to state its condition on.
	 * A version naming no generation answers {@code 1}, which no object
	 * carries, so that the write is refused.
	 *
	 * @param version
	 * @return
	 */
	static String generationOf(String version) {
		var value = unquote(version);
		var separator = separatorIn(value);

		return separator < 0 ? UNMATCHABLE_GENERATION : value.substring(separator + 1);
	}

	/**
	 * Where the generation of a version starts, or {@code -1} when the value
	 * holds none. Only digits after the separator count as a generation, so an
	 * ETag containing the character stays whole.
	 */
	private static int separatorIn(String value) {
		var separator = value.lastIndexOf(SEPARATOR);
		if(separator < 0 || separator == value.length() - 1) {
			return -1;
		}

		for(var i = separator + 1; i < value.length(); i++) {
			if(!Character.isDigit(value.charAt(i))) {
				return -1;
			}
		}

		return separator;
	}

	private static String unquote(String value) {
		if(value == null) {
			return "";
		}

		var trimmed = value.trim();
		if(trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1);
		}

		return trimmed;
	}

	/**
	 * Take a header out of a request however it is spelled. A header is
	 * removed by its exact name, but found without regard to case.
	 */
	private static void removeHeader(
		SdkHttpRequest.Builder builder,
		SdkHttpRequest request,
		String header
	) {
		for(var name : request.headers().keySet()) {
			if(name.equalsIgnoreCase(header)) {
				builder.removeHeader(name);
			}
		}
	}

	/**
	 * The host an endpoint names, or an empty string when it names none.
	 */
	private static String hostOf(String url) {
		try {
			var host = URI.create(url.trim()).getHost();

			return host == null ? "" : host.toLowerCase(Locale.ROOT);
		} catch(IllegalArgumentException e) {
			return "";
		}
	}
}
