package se.l4.exofind.engine.api.errors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.IndexFieldNotFoundException;
import se.l4.exofind.engine.index.IndexFieldUsageException;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.IndexNoLiveGenerationException;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.IndexOutOfDateException;
import se.l4.exofind.engine.index.IndexReadonlyException;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.index.IndexSourceRequiredException;
import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.index.IndexUnsupportedException;
import se.l4.exofind.engine.index.IndexVersionMismatchException;
import se.l4.exofind.engine.index.registry.RegistryException;
import se.l4.exofind.engine.index.state.IndexerUnavailableException;
import se.l4.exofind.engine.index.state.IndexerUnreachableException;
import se.l4.exofind.engine.metrics.RequestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class EngineExceptionMapperTest {
	private final EngineExceptionMapper mapper = new EngineExceptionMapper(
		new RequestMetrics(new SimpleMeterRegistry(), false)
	);

	private static final ErrorType INVALID = ErrorType.withCode("test:invalid")
		.withArguments("name")
		.withMessage("Field `{{name}}` is invalid");

	@Test
	public void testValidationErrorsAreListed() {
		var exception = new ValidationException(
			INVALID.toMessage(ObjectLocation.root().forField("title"), "name", "title"),
			INVALID.toMessage(ObjectLocation.root().forField("author"), "name", "author")
		);

		var response = mapper.toResponse(exception);
		assertThat(response.getStatus(), is(400));

		var body = (ErrorResponse) response.getEntity();
		assertThat(body.code(), is("validation"));
		assertThat(body.message(), is("Request contains 2 errors"));
		assertThat(body.errors().size(), is(2));

		var first = body.errors().get(0);
		assertThat(first.code(), is("test:invalid"));
		assertThat(first.message(), is("Field `title` is invalid"));
		assertThat(first.path(), is("title"));
		assertThat(first.arguments(), is(java.util.Map.of("name", "title")));
	}

	/**
	 * The node is too old for the index, or the index answers for none of its
	 * generations. Neither is fixed by changing the request, so both are a
	 * conflict rather than a bad request - and neither is a fault of this node
	 * to report as one.
	 */
	@Test
	public void testIndexThatCannotBeResolvedIsAConflict() {
		var unsupported = mapper.toResponse(
			new IndexUnsupportedException("books", "generations.written-to")
		);
		assertThat(unsupported.getStatus(), is(409));
		assertThat(((ErrorResponse) unsupported.getEntity()).code(), is("index:unsupported"));

		var noLive = mapper.toResponse(new IndexNoLiveGenerationException("books"));
		assertThat(noLive.getStatus(), is(409));
		assertThat(
			((ErrorResponse) noLive.getEntity()).code(),
			is("index:no_live_generation")
		);
	}

	/**
	 * A change to which indexes exist that could not be stored leaves them as
	 * they were, so the caller is told the change did not happen rather than
	 * that something broke.
	 */
	@Test
	public void testRegistryThatCouldNotBeWrittenIsAConflict() {
		var response = mapper.toResponse(RegistryException.conflict());

		assertThat(response.getStatus(), is(409));
		assertThat(
			((ErrorResponse) response.getEntity()).code(),
			is("index:registry:conflict")
		);
	}

	@Test
	public void testErrorWithoutLocationHasNoPath() {
		var response = mapper.toResponse(new IndexReadonlyException("books"));
		assertThat(response.getStatus(), is(409));

		var body = (ErrorResponse) response.getEntity();
		assertThat(body.code(), is("index:readonly"));
		assertThat(body.errors().get(0).path(), is(nullValue()));
		assertThat(body.errors().get(0).arguments(), is(java.util.Map.of("index", "books")));
	}

	/**
	 * A request that needed the indexer with none to pass it to is a
	 * conflict, the same family as an index that cannot be modified right
	 * now - sent again once an indexer is up, it is served.
	 */
	@Test
	public void testNoIndexerToForwardToIsAConflict() {
		var response = mapper.toResponse(new IndexerUnavailableException());

		assertThat(response.getStatus(), is(409));
		assertThat(
			((ErrorResponse) response.getEntity()).code(),
			is("indexer:unavailable")
		);
	}

	/**
	 * A forward that could not be delivered is this node reporting on the
	 * node behind it, which is what 502 says - not on the request, and not on
	 * itself.
	 */
	@Test
	public void testIndexerThatDidNotAnswerIsABadGateway() {
		var response = mapper.toResponse(
			new IndexerUnreachableException(new java.io.IOException("Connection refused"))
		);

		assertThat(response.getStatus(), is(502));
		assertThat(
			((ErrorResponse) response.getEntity()).code(),
			is("indexer:unreachable")
		);
	}

	@Test
	public void testNotFoundIsNotFound() {
		assertThat(mapper.toResponse(new IndexNotFoundException("books")).getStatus(), is(404));
	}

	@Test
	public void testOutOfDateIsConflict() {
		assertThat(
			mapper.toResponse(new IndexOutOfDateException("books", IndexState.NEEDS_PULL))
				.getStatus(),
			is(409)
		);
	}

	@Test
	public void testVersionMismatchIsPreconditionFailed() {
		assertThat(
			mapper.toResponse(new IndexVersionMismatchException("books", "a", "b")).getStatus(),
			is(412)
		);
	}

	@Test
	public void testQueryProblemsAreBadRequests() {
		assertThat(
			mapper.toResponse(new IndexFieldNotFoundException("missing")).getStatus(),
			is(400)
		);
		assertThat(
			mapper.toResponse(new IndexFieldUsageException("name", "sort")).getStatus(),
			is(400)
		);
		assertThat(
			mapper.toResponse(new IndexSourceRequiredException("variants.price")).getStatus(),
			is(400)
		);
		assertThat(
			mapper.toResponse(new IndexSourceNotKeptException("products")).getStatus(),
			is(400)
		);
		assertThat(
			mapper.toResponse(new IndexInvalidQueryTypeException("boolean", "prefix"))
				.getStatus(),
			is(400)
		);
		assertThat(
			mapper.toResponse(new IndexInvalidQueryValueException("published", "boolean"))
				.getStatus(),
			is(400)
		);
	}
}
