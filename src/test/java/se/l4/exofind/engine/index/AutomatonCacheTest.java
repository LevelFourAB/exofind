package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;

/**
 * Tests for the automaton cache - that a word asked for again is answered
 * with the automaton compiled the first time, that a prefix handed over as a
 * window onto a buffer is kept as its own copy, and that the mistakes a word
 * may forgive are bounded.
 */
public class AutomatonCacheTest {
	@Test
	public void testTheSameWordIsCompiledOnce() {
		var cache = AutomatonCache.sized(16, 16);

		var first = cache.typo("silent", 1, 1, false);
		var second = cache.typo("silent", 1, 1, false);

		assertThat(second, sameInstance(first));
		assertThat(cache.typoStats().missCount(), is(1L));
		assertThat(cache.typoStats().hitCount(), is(1L));
	}

	@Test
	public void testADifferentReadingOfTheWordIsItsOwnAutomaton() {
		var cache = AutomatonCache.sized(16, 16);

		var whole = cache.typo("silent", 1, 1, false);
		var halfTyped = cache.typo("silent", 1, 1, true);
		var twoMistakes = cache.typo("silent", 2, 1, false);

		assertThat(halfTyped, not(sameInstance(whole)));
		assertThat(twoMistakes, not(sameInstance(whole)));
		assertThat(cache.typoStats().missCount(), is(3L));
	}

	@Test
	public void testTheSamePrefixIsCompiledOnce() {
		var cache = AutomatonCache.sized(16, 16);

		var first = cache.prefix(new BytesRef("sil"));
		var second = cache.prefix(new BytesRef("sil"));

		assertThat(second, sameInstance(first));
		assertThat(cache.prefixStats().missCount(), is(1L));
		assertThat(cache.prefixStats().hitCount(), is(1L));
	}

	@Test
	public void testAPrefixHandedOverAsAWindowIsKeptAsACopy() {
		var cache = AutomatonCache.sized(16, 16);

		var buffer = "xxsilxx".getBytes(StandardCharsets.UTF_8);
		var window = new BytesRef(buffer, 2, 3);
		var compiled = cache.prefix(window);

		// The buffer the window looked onto is written over, as a term dictionary's is
		buffer[2] = 'z';

		assertThat(cache.prefix(new BytesRef("sil")), sameInstance(compiled));
	}

	@Test
	public void testMoreMistakesThanAWordMayForgiveAreRefused() {
		var cache = AutomatonCache.sized(16, 16);

		assertThrows(
			IllegalArgumentException.class,
			() -> cache.typo("silent", AutomatonCache.MAX_EDITS + 1, 1, false)
		);
		assertThrows(IllegalArgumentException.class, () -> cache.typo("silent", -1, 1, false));
	}

	@Test
	public void testANegativeSizeIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> AutomatonCache.sized(-1, 16));
		assertThrows(IllegalArgumentException.class, () -> AutomatonCache.sized(16, -1));
	}
}
