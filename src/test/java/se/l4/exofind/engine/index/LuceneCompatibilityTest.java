package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.OptionalInt;

import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;

/**
 * Tests for how a creating Lucene version is judged. Written against
 * {@link Version#MIN_SUPPORTED_MAJOR} rather than a fixed number, so that
 * upgrading Lucene does not need the expectations rewritten - the point is that
 * the window moves.
 */
public class LuceneCompatibilityTest {
	@Test
	public void testCurrentMajorIsCurrent() {
		assertThat(
			LuceneCompatibility.of(Version.LATEST.major),
			is(LuceneCompatibility.CURRENT)
		);
	}

	@Test
	public void testPreviousMajorIsEnding() {
		assertThat(
			LuceneCompatibility.of(Version.MIN_SUPPORTED_MAJOR),
			is(LuceneCompatibility.ENDING)
		);
	}

	@Test
	public void testOlderMajorIsUnreadable() {
		assertThat(
			LuceneCompatibility.of(Version.MIN_SUPPORTED_MAJOR - 1),
			is(LuceneCompatibility.UNREADABLE)
		);
	}

	/**
	 * An index Lucene refuses is exactly the one this refuses, as answering
	 * anything else would either turn a readable index away or let an
	 * unreadable one through to fail when it is opened.
	 */
	@Test
	public void testOnlyLuceneRefusalIsUnreadable() {
		for(var major = 1; major <= Version.LATEST.major + 1; major++) {
			assertThat(
				"created with Lucene " + major + ".x",
				LuceneCompatibility.of(major).isReadable(),
				is(major >= Version.MIN_SUPPORTED_MAJOR)
			);
		}
	}

	@Test
	public void testUnrecordedVersionIsUnknown() {
		assertThat(
			LuceneCompatibility.of(OptionalInt.empty()),
			is(LuceneCompatibility.UNKNOWN)
		);
	}

	/**
	 * Nothing having recorded a version is not a reason to refuse an index, as
	 * every index written before the version was recorded looks like that.
	 */
	@Test
	public void testUnknownIsReadable() {
		assertThat(LuceneCompatibility.UNKNOWN.isReadable(), is(true));
	}
}
