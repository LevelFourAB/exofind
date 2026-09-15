package se.l4.exofind.engine.api.errors;

import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.api.ExofindApi;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * How large a request body this node accepts.
 *
 * <p>Two limits, because the node reads a body in one of two ways. A body it
 * holds in memory to parse as a whole is bounded by what the heap can carry,
 * so {@code exofind.api.max-body-size} states a size every node has room for.
 * A body in {@link ExofindApi#NDJSON} is read and acted on as it arrives, so
 * {@code exofind.api.max-stream-body-size} bounds it by nothing unless an
 * operator asks for a bound. The media type is what says which one applies,
 * because it is the only part of a request that says how the body is read
 * before the body arrives.
 *
 * <p>The node states both limits itself rather than inheriting
 * {@code quarkus.http.limits.max-body-size}, which counts every byte of every
 * request the same way. See {@code application.properties}, which says why the
 * framework setting is left empty.
 *
 * <p>Both limits are enforced twice, because a request says its size in two
 * ways. {@link RefusedRequestRoute} refuses a stated {@code Content-Length}
 * before the body arrives, and {@link RequestBodyLimitFilter} counts the bytes
 * of a body that states no length.
 */
@ApplicationScoped
public class RequestBodyLimits {
	/** What {@link #forContentType(String)} answers where nothing is limited. */
	public static final long NONE = -1;

	private final long buffered;
	private final long streamed;

	public RequestBodyLimits(
		@ConfigProperty(
			name = "exofind.api.max-body-size",
			defaultValue = "10M"
		) Optional<MemorySize> buffered,
		@ConfigProperty(name = "exofind.api.max-stream-body-size") Optional<MemorySize> streamed
	) {
		this.buffered = toBytes(buffered);
		this.streamed = toBytes(streamed);
	}

	/**
	 * How many bytes a body in a media type may carry.
	 *
	 * @param contentType
	 *   the {@code Content-Type} of the request, as it arrived, or {@code null}
	 *   for a request that states none
	 * @return
	 *   the number of bytes the body may carry, or {@link #NONE} where the body
	 *   is not bounded
	 */
	public long forContentType(String contentType) {
		return isStreamed(contentType) ? streamed : buffered;
	}

	/**
	 * Whether a body in a media type is read as it arrives. Read from the media
	 * type alone, so the parameters after it - a {@code charset}, say - do not
	 * change the answer.
	 */
	private static boolean isStreamed(String contentType) {
		if(contentType == null) {
			return false;
		}

		var end = contentType.indexOf(';');
		var type = end < 0 ? contentType : contentType.substring(0, end);

		return ExofindApi.NDJSON.equalsIgnoreCase(type.trim());
	}

	private static long toBytes(Optional<MemorySize> size) {
		return size.map(MemorySize::asLongValue).orElse(NONE);
	}
}
