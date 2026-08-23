package se.l4.exofind.engine.index.state;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Keep {@link ObjectStorageSync} quiet about commits it cannot read a Lucene
 * version out of, for as long as the annotated class runs.
 *
 * <p>Tests about the transfer stand in for a commit with files of random
 * bytes, because what the push does with them does not depend on what Lucene
 * would have written. The push still tries to read the version out of the
 * segments file to record it in the manifest, and warns once per push when it
 * cannot - which for these files is every time, and says nothing about the
 * run.
 *
 * <p>Only the warning is suppressed; the push behaves as it does in
 * production, and the tests that care about the recorded version write a real
 * commit and assert on the manifest. Errors from the same category still come
 * through.
 */
public final class QuietLuceneVersionWarnings
	implements BeforeAllCallback, AfterAllCallback {

	private static final Logger LOGGER = Logger.getLogger(
		ObjectStorageSync.class.getName()
	);

	private Level previous;

	@Override
	public void beforeAll(ExtensionContext context) {
		previous = LOGGER.getLevel();
		LOGGER.setLevel(Level.SEVERE);
	}

	@Override
	public void afterAll(ExtensionContext context) {
		LOGGER.setLevel(previous);
	}
}
