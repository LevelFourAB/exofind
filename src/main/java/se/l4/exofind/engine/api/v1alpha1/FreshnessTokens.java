package se.l4.exofind.engine.api.v1alpha1;

import java.util.Base64;

import com.google.protobuf.InvalidProtocolBufferException;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.Location;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.freshness.Freshness;
import se.l4.exofind.engine.index.IndexName;

/**
 * The freshness token as the API carries it: an opaque string a client gets
 * back from every change and every read, and hands back unchanged to demand
 * an answer that holds the change.
 *
 * <p>A token is one byte that gives the format version followed by a
 * {@link FreshnessToken} message, in base64url without padding, so it is safe
 * in a JSON string, a header and a query parameter. The format is what
 * {@code freshness.proto} describes, and the rules stated there are what keep
 * a token issued by one release readable by the next.
 *
 * <p>A token travels in the body where the request or the response has one,
 * and in the {@link #HEADER} where it does not.
 */
public final class FreshnessTokens {
	/**
	 * The header a token travels in where a request or a response has no body
	 * to carry it: a response that answers {@code 204 No Content}, and a
	 * request that reads documents with {@code GET}.
	 */
	public static final String HEADER = "X-Exofind-Freshness";

	/**
	 * The path a token given in the body of a read sits at, which is where an
	 * error about it is placed.
	 */
	public static final String BODY_PATH = "freshness.atLeast";

	/**
	 * The format version this build writes, and the highest it reads.
	 */
	static final byte VERSION = 1;

	static final ErrorType INVALID = ErrorType.withCode("search:freshness:invalid")
		.withStatus(400)
		.withMessage(
			"The freshness token is not one the engine issued; pass a token back unchanged"
		);

	static final ErrorType VERSION_UNSUPPORTED =
		ErrorType.withCode("search:freshness:version_unsupported")
			.withStatus(400)
			.withArguments("version")
			.withMessage(
				"The freshness token was issued in format version {{version}}, which this"
					+ " node does not read; send the request to a node of the release"
					+ " that issued it"
			);

	static final ErrorType INDEX_MISMATCH =
		ErrorType.withCode("search:freshness:index_mismatch")
			.withStatus(400)
			.withArguments("index", "expected")
			.withMessage(
				"The freshness token is of `{{index}}`, and says nothing about"
					+ " `{{expected}}`"
			);

	private FreshnessTokens() {
	}

	/**
	 * Encode a state as a token.
	 *
	 * @param freshness
	 * @return
	 */
	public static String encode(Freshness freshness) {
		var message = FreshnessToken.newBuilder()
			.setIndex(freshness.index());

		if(freshness.hasGeneration()) {
			message.setGeneration(freshness.generation());
		}

		if(freshness.hasCommit()) {
			message.setCommit(freshness.commit());
		}

		if(freshness.hasSettingsVersion()) {
			message.setSettingsVersion(freshness.settingsVersion());
		}

		var bytes = message.build().toByteArray();
		var framed = new byte[bytes.length + 1];
		framed[0] = VERSION;
		System.arraycopy(bytes, 0, framed, 1, bytes.length);

		return Base64.getUrlEncoder().withoutPadding().encodeToString(framed);
	}

	/**
	 * Read the token a read carries, from its body or from its header. A
	 * token in the body is the one read when both are given.
	 *
	 * @param inBody
	 *   the token the body carries, or {@code null} for none
	 * @param inHeader
	 *   the token the {@link #HEADER} carries, or {@code null} for none
	 * @param name
	 *   the name the read is of, which the token has to be of as well
	 * @return
	 *   the state demanded, or {@code null} when no token was given
	 * @throws ValidationException
	 *   if the token cannot be read, was written by a format this node does
	 *   not read, or is of another index
	 */
	public static Freshness decode(String inBody, String inHeader, String name) {
		if(inBody != null && !inBody.isBlank()) {
			return decodeAt(inBody, Location.create(BODY_PATH), name);
		}

		if(inHeader != null && !inHeader.isBlank()) {
			return decodeAt(inHeader.trim(), Location.create(HEADER), name);
		}

		return null;
	}

	/**
	 * Read one token.
	 *
	 * @param token
	 *   the token as the client sent it
	 * @param location
	 *   where the token sat in the request, for an error to point at
	 * @param name
	 *   the name the read is of, which the token has to be of as well
	 * @return
	 * @throws ValidationException
	 *   if the token cannot be read, was written by a format this node does
	 *   not read, or is of another index
	 */
	public static Freshness decodeAt(String token, Location location, String name) {
		byte[] framed;
		try {
			framed = Base64.getUrlDecoder().decode(token);
		} catch(IllegalArgumentException e) {
			throw new ValidationException(INVALID.toMessage(location));
		}

		if(framed.length == 0 || framed[0] < 1) {
			throw new ValidationException(INVALID.toMessage(location));
		}

		if(framed[0] > VERSION) {
			throw new ValidationException(
				VERSION_UNSUPPORTED.toMessage(location, "version", (int) framed[0])
			);
		}

		FreshnessToken message;
		try {
			message = FreshnessToken.parseFrom(
				java.util.Arrays.copyOfRange(framed, 1, framed.length)
			);
		} catch(InvalidProtocolBufferException e) {
			throw new ValidationException(INVALID.toMessage(location));
		}

		if(!message.hasIndex() || message.getIndex().isBlank() || message.getCommit() < 0) {
			throw new ValidationException(INVALID.toMessage(location));
		}

		var generation = message.hasGeneration() && !message.getGeneration().isBlank()
			? message.getGeneration()
			: null;

		if(generation == null && message.getCommit() > 0) {
			throw new ValidationException(INVALID.toMessage(location));
		}

		var expected = IndexName.parse(name).index();
		if(!message.getIndex().equals(expected)) {
			throw new ValidationException(
				INDEX_MISMATCH.toMessage(
					location,
					"index", message.getIndex(),
					"expected", expected
				)
			);
		}

		return new Freshness(
			message.getIndex(),
			generation,
			message.getCommit(),
			message.hasSettingsVersion() ? message.getSettingsVersion() : null
		);
	}
}
