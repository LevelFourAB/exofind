package se.l4.exofind.engine;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;

import org.slf4j.event.Level;

import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.SdkInterruptedException;

/**
 * Tells whether work failed because the thread doing it was interrupted, which
 * on this node means the work was stopped rather than that it went wrong.
 *
 * <p>Every background loop runs on a pool of its own, and a node that stops
 * shuts those pools down with the threads still working in them. The work each
 * of them was doing then fails, most often in the middle of a request to the
 * storage, and the loop reports a failure that nothing can be done about: a
 * stack trace per index, at a level that asks an operator to look, from a node
 * that is doing exactly what it was told. A loop asks here what level to say it
 * at instead, and says the same sentence at {@link Level#DEBUG} where the
 * failure is the stop itself.
 *
 * <p>An interruption reaches a caller as one of several types, and which one
 * says nothing about the work: the AWS SDK reports it as an
 * {@link AbortedException} carrying a {@link SdkInterruptedException}, the file
 * and channel APIs as an {@link InterruptedIOException} or a
 * {@link ClosedByInterruptException}, and anything that waits as an
 * {@link InterruptedException}. All of them are looked for anywhere in the
 * chain of causes, since the loop that logs sees only what the layer above the
 * failure wrapped it in.
 *
 * <p>Being interrupted is not on its own a reason to stop: a loop that keeps
 * running decides that for itself, from {@link Thread#isInterrupted()} or from
 * the pool it runs on. This says only how loudly to report it.
 */
public final class Interruptions {
	/**
	 * How deep the chain of causes is followed. A chain longer than this is
	 * cyclic or built by something that wraps without bound, and either way
	 * nothing is learned by following it further.
	 */
	private static final int MAX_DEPTH = 32;

	private Interruptions() {
	}

	/**
	 * Whether a failure was an interruption of the thread that did the work.
	 *
	 * @param t
	 *   the failure, or {@code null}
	 * @return
	 *   whether it, or anything it was caused by, is an interruption
	 */
	public static boolean isInterrupted(Throwable t) {
		var current = t;
		for(var depth = 0; current != null && depth < MAX_DEPTH; depth++) {
			if(current instanceof InterruptedException
				|| current instanceof InterruptedIOException
				|| current instanceof ClosedByInterruptException
				|| current instanceof SdkInterruptedException
				|| current instanceof AbortedException) {
				return true;
			}

			var cause = current.getCause();
			if(cause == current) {
				return false;
			}

			current = cause;
		}

		return false;
	}

	/**
	 * Level a background failure is reported at: {@link Level#DEBUG} where the
	 * work was interrupted, and {@link Level#WARN} where it failed on its own.
	 *
	 * @param t
	 *   the failure, or {@code null}
	 * @return
	 *   the level, for {@link se.l4.exofind.engine.logging.Log#atLevel}
	 */
	public static Level levelOf(Throwable t) {
		return isInterrupted(t) ? Level.DEBUG : Level.WARN;
	}
}
