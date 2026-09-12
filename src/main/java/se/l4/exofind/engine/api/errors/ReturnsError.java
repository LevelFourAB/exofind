package se.l4.exofind.engine.api.errors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One error code an endpoint answers with, and what makes it answer that way.
 *
 * <p>A resource method carries one of these for every code a caller can act on.
 * {@code ErrorCodeFilter} writes them into the OpenAPI document as
 * {@code x-error-codes} on the answer that carries them, so the code is stated
 * once, beside the endpoint that returns it, rather than in the prose of a
 * description where nothing can read it.
 *
 * <p>The {@link #status()} has to be an answer the endpoint declares with
 * {@code @APIResponse}, or the build fails. A code is stable for the life of an
 * API version; {@code docs/reference/errors.md} explains every code a client
 * handles.
 *
 * <p>Two tests keep the annotations and the engine in step.
 * {@code ErrorCodeFilterTest} holds every code named here to one the engine
 * declares, and {@code ErrorCodeCoverageTest} holds every endpoint to naming
 * the codes its own resource and mappers can answer with.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ReturnsErrors.class)
public @interface ReturnsError {
	/**
	 * The code, as the {@code code} field of the error response carries it.
	 */
	String value();

	/**
	 * The status the code is answered with.
	 */
	int status();

	/**
	 * What makes the endpoint answer with the code, as one sentence.
	 *
	 * <p>Written for a caller deciding what to do about it, so it names the
	 * condition and, where there is one, the way out of it.
	 */
	String when();
}
