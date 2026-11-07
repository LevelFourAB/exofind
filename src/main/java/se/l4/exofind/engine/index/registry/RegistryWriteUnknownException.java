package se.l4.exofind.engine.index.registry;

import java.io.IOException;

/**
 * Thrown by {@link RegistryStorage#write} when the storage did not answer the
 * write and the registry read back afterwards does not tell whether the write
 * landed.
 *
 * <p>That happens when another node wrote the registry after the write, so
 * the stored registry is neither what was written nor the version the write
 * was conditioned on, and when the registry could not be read back at all.
 * The write may have landed, so the caller must not take it as refused. The
 * caller reads the registry again and looks for its own change in it.
 */
public class RegistryWriteUnknownException extends IOException {
	private static final long serialVersionUID = 1L;

	public RegistryWriteUnknownException(String message, Throwable cause) {
		super(message, cause);
	}
}
