package se.l4.exofind.engine.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The shape of a credential, and how one is checked against what is stored.
 *
 * <p>A credential reads {@code exok_<id>_<secret>}. The prefix is there so that
 * a credential is recognizable wherever one is accidentally published, the id
 * so that a key can be looked up and named in a log without holding the secret,
 * and the secret is 256 bits of randomness.
 *
 * <p>Secrets are hashed with a single pass of SHA-256 rather than a password
 * hash. A secret is generated rather than chosen, so there is nothing to guess
 * at that a slow hash would make slower, and every request that presents one
 * pays for the hashing.
 *
 * <p>This class holds no state and is safe for concurrent use.
 */
public final class KeySecret {
	/**
	 * What every credential starts with.
	 */
	public static final String PREFIX = "exok_";

	/**
	 * Length of an id in characters. Ids are lowercase hex, which is what keeps
	 * the separator before the secret findable - the secret is base64url and
	 * may hold an underscore of its own.
	 */
	private static final int ID_LENGTH = 16;

	private static final int ID_BYTES = ID_LENGTH / 2;
	private static final int SECRET_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * A newly minted credential, which is the only point at which the secret
	 * and the hash of it exist together.
	 *
	 * @param id
	 * @param credential
	 *   what the caller presents, and the only copy of the secret there will
	 *   ever be
	 * @param secretHash
	 *   lowercase hex of the hash to store
	 */
	public record Generated(String id, String credential, String secretHash) {
	}

	/**
	 * The parts of a credential a caller presented.
	 */
	public record Presented(String id, String secret) {
	}

	private KeySecret() {
	}

	/**
	 * Mint a credential.
	 */
	public static Generated generate() {
		var idBytes = new byte[ID_BYTES];
		var secretBytes = new byte[SECRET_BYTES];

		RANDOM.nextBytes(idBytes);
		RANDOM.nextBytes(secretBytes);

		var id = HexFormat.of().formatHex(idBytes);
		var secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

		return new Generated(id, PREFIX + id + "_" + secret, hash(secret));
	}

	/**
	 * Take a presented credential apart.
	 *
	 * @param credential
	 * @return
	 *   empty when the value is not shaped like a credential, which is the same
	 *   answer as a credential naming a key that does not exist
	 */
	public static Optional<Presented> parse(String credential) {
		if(credential == null || !credential.startsWith(PREFIX)) {
			return Optional.empty();
		}

		var idEnd = PREFIX.length() + ID_LENGTH;
		if(credential.length() <= idEnd + 1 || credential.charAt(idEnd) != '_') {
			return Optional.empty();
		}

		var id = credential.substring(PREFIX.length(), idEnd);
		for(int i = 0; i < id.length(); i++) {
			var c = id.charAt(i);
			if((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
				return Optional.empty();
			}
		}

		return Optional.of(new Presented(id, credential.substring(idEnd + 1)));
	}

	/**
	 * Hash a value the way the key store holds it.
	 *
	 * @param value
	 * @return
	 *   lowercase hex of the SHA-256 of the UTF-8 bytes of {@code value}
	 */
	public static String hash(String value) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of()
				.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch(NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available in this runtime", e);
		}
	}

	/**
	 * Whether a presented secret is the one a stored hash was made from.
	 *
	 * <p>The comparison takes the same time whatever the values are, so a
	 * caller can not learn how much of a secret it guessed correctly from how
	 * long the answer took.
	 *
	 * @param secret
	 * @param storedHash
	 *   lowercase hex, as {@link #hash(String)} produces
	 * @return
	 */
	public static boolean matches(String secret, String storedHash) {
		return constantTimeEquals(hash(secret), storedHash);
	}

	/**
	 * Compare two values without giving away where they first differ.
	 *
	 * @param a
	 * @param b
	 * @return
	 */
	public static boolean constantTimeEquals(String a, String b) {
		if(a == null || b == null) {
			return false;
		}

		return MessageDigest.isEqual(
			a.getBytes(StandardCharsets.UTF_8),
			b.getBytes(StandardCharsets.UTF_8)
		);
	}
}
