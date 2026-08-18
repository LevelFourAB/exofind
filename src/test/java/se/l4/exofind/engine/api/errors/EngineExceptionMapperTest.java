package se.l4.exofind.engine.api.errors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Optional;

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
import se.l4.exofind.engine.index.IndexSourceRequiredException;
import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.index.IndexUnsupportedException;
import se.l4.exofind.engine.index.IndexVersionMismatchException;
import se.l4.exofind.engine.index.registry.RegistryException;
import se.l4.exofind.engine.index.state.IndexerOwnership;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import jakarta.ws.rs.core.UriInfo;

public class EngineExceptionMapperTest {
	private final EngineExceptionMapper mapper =
		new EngineExceptionMapper(new LocalIndexerOwnership());

	/**
	 * Ownership as it looks on a read-only node that knows where the indexer
	 * is.
	 */
	private static IndexerOwnership indexerAt(String address) {
		return new IndexerOwnership() {
			@Override
			public void start(Listener listener) {
			}

			@Override
			public void stop() {
			}

			@Override
			public Optional<String> indexerAddress() {
				return Optional.ofNullable(address);
			}
		};
	}

	/**
	 * A mapper answering a request for the given URI, on a node that knows
	 * the indexer under the given address.
	 */
	private static EngineExceptionMapper mapperFor(String requestUri, String indexerAddress) {
		var mapper = new EngineExceptionMapper(indexerAt(indexerAddress));

		var uriInfo = mock(UriInfo.class);
		when(uriInfo.getRequestUri()).thenReturn(URI.create(requestUri));
		mapper.uriInfo = uriInfo;

		return mapper;
	}

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
	 * A write reaching a node that cannot serve it is pointed at the node
	 * that can: same request, on the indexer's scheme, host and port.
	 */
	@Test
	public void testReadonlyRedirectsToKnownIndexer() {
		var mapper = mapperFor(
			"http://localhost:8080/v1alpha1/admin/indexes/books/documents?wait=true",
			"http://indexer-node:9042"
		);

		var response = mapper.toResponse(new IndexReadonlyException("books"));

		assertThat(response.getStatus(), is(307));
		assertThat(
			response.getLocation(),
			is(URI.create("http://indexer-node:9042/v1alpha1/admin/indexes/books/documents?wait=true"))
		);
	}

	/**
	 * An address without a port sends the caller to the default port of its
	 * scheme, not to the port this node happens to serve on.
	 */
	@Test
	public void testRedirectDropsPortWhenAddressHasNone() {
		var mapper = mapperFor(
			"http://localhost:8080/v1alpha1/admin/indexes/books",
			"https://indexer.example.com"
		);

		var response = mapper.toResponse(new IndexReadonlyException("books"));

		assertThat(response.getStatus(), is(307));
		assertThat(
			response.getLocation(),
			is(URI.create("https://indexer.example.com/v1alpha1/admin/indexes/books"))
		);
	}

	/**
	 * An address that cannot be turned into a redirect leaves the refusal as
	 * it was, rather than sending the caller somewhere broken.
	 */
	@Test
	public void testReadonlyWithUnusableIndexerAddressIsConflict() {
		var mapper = mapperFor(
			"http://localhost:8080/v1alpha1/admin/indexes/books",
			"not a usable address"
		);

		var response = mapper.toResponse(new IndexReadonlyException("books"));

		assertThat(response.getStatus(), is(409));
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
