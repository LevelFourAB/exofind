package se.l4.exofind.engine.auth;

import java.time.Instant;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

/**
 * One credential and what it may do, as the key store holds it.
 *
 * <p>The secret is never part of a key. What is stored is a hash of it, so a
 * credential that is lost is replaced rather than recovered, and reading the
 * key store gives no one a working credential.
 *
 * @param id
 *   what a presented credential names, and what a log records in place of the
 *   secret
 * @param secretHash
 *   lowercase hex of the hash of the secret, as {@link KeySecret} produces it
 * @param description
 *   what the key is for, in whatever words its creator chose; empty when none
 *   was given
 * @param grants
 *   what the key may do, evaluated as a union - empty allows nothing
 * @param createdAt
 * @param expiresAt
 *   when the key stops being accepted, or {@code null} when it never does
 */
public record Key(
	String id,
	String secretHash,
	String description,
	ListIterable<Grant> grants,
	Instant createdAt,
	Instant expiresAt
) {
	public Key {
		grants = grants == null ? Lists.immutable.empty() : Lists.immutable.ofAll(grants);
	}

	/**
	 * Whether this key has lapsed as of a point in time.
	 *
	 * @param now
	 *   the reading node's clock, so skew between nodes moves the moment a key
	 *   stops working
	 * @return
	 */
	public boolean isExpired(Instant now) {
		return expiresAt != null && !now.isBefore(expiresAt);
	}
}
