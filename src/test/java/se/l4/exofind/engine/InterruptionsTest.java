package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import software.amazon.awssdk.core.exception.AbortedException;

/**
 * Tests for telling a failure that stopped work apart from one the work
 * caused, which is what decides how loudly a background loop reports it.
 */
public class InterruptionsTest {
	@Test
	public void testAFailureOfItsOwnIsNotAnInterruption() {
		assertThat(Interruptions.isInterrupted(new IOException("no route")), is(false));
		assertThat(Interruptions.levelOf(new IOException("no route")), is(Level.WARN));
	}

	@Test
	public void testNothingIsNotAnInterruption() {
		assertThat(Interruptions.isInterrupted(null), is(false));
		assertThat(Interruptions.levelOf(null), is(Level.WARN));
	}

	@Test
	public void testAnInterruptionIsFoundThroughTheCauses() {
		var aborted = AbortedException.create("stopped");
		var wrapped = new IOException("Unable to read the search settings", aborted);

		assertThat(Interruptions.isInterrupted(wrapped), is(true));
		assertThat(Interruptions.levelOf(wrapped), is(Level.DEBUG));
	}

	@Test
	public void testEveryKindOfInterruptionIsFound() {
		assertThat(Interruptions.isInterrupted(new InterruptedException()), is(true));
		assertThat(Interruptions.isInterrupted(new InterruptedIOException()), is(true));
		assertThat(Interruptions.isInterrupted(new ClosedByInterruptException()), is(true));
		assertThat(Interruptions.isInterrupted(AbortedException.create("stopped")), is(true));
	}

	@Test
	public void testACycleOfCausesEnds() {
		var first = new IOException("first");
		var second = new IOException("second", first);
		first.initCause(second);

		assertThat(Interruptions.isInterrupted(second), is(false));
	}
}
