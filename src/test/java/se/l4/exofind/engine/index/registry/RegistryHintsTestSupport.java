package se.l4.exofind.engine.index.registry;

/**
 * Reaches the package-private flush of {@link RegistryHints} for tests in
 * other packages, standing in for the schedule that drives it in production.
 */
public final class RegistryHintsTestSupport {
	private RegistryHintsTestSupport() {
	}

	public static void flush(RegistryHints hints) {
		hints.flush();
	}
}
