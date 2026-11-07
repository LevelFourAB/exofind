package se.l4.exofind.engine.index.registry;

import java.time.Instant;

import se.l4.exofind.engine.index.IndexName;

/**
 * Thrown when the removal of an index or a generation from the registry may
 * have landed, but neither the answer to the write nor the registry read
 * after it tells. The cause is a {@link RegistryWriteUnknownException}.
 *
 * <p>The caller is told that the removal failed, and the registry may no
 * longer name what was removed. A removal that landed must still leave its
 * removal mark, so the exception names what was removed and when that was
 * created. A later read of the registry then tells whether the removal
 * landed: the registry no longer names the registration made at that time.
 */
public class RegistryRemovalUnknownException extends RegistryException {
	private static final long serialVersionUID = 1L;

	private final IndexName target;
	private final Instant createdAt;

	public RegistryRemovalUnknownException(
		IndexName target,
		Instant createdAt,
		RegistryWriteUnknownException cause
	) {
		super(cause);

		this.target = target;
		this.createdAt = createdAt;
	}

	/**
	 * The index, or the generation as {@code index@generation}, that the
	 * removal took out of the registry.
	 */
	public IndexName target() {
		return target;
	}

	/**
	 * When the removed index or generation was created, as the registry
	 * stated it, or {@code null} when the registry did not state it.
	 */
	public Instant createdAt() {
		return createdAt;
	}
}
