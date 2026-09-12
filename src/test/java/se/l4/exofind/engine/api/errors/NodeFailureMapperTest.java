package se.l4.exofind.engine.api.errors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.metrics.RequestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * That a failure no other mapper names still answers with the error body of the
 * API.
 *
 * <p>The mappers beside this one each name a class of failure. This one
 * answers what is left, so a fault in the node reaches a client as a body it
 * can read instead of as an empty response.
 */
public class NodeFailureMapperTest {
	private final NodeFailureMapper mapper = new NodeFailureMapper(
		new RequestMetrics(new SimpleMeterRegistry(), false)
	);

	@Test
	public void testAFailureWithNoCodeOfItsOwnIsAnsweredAsTheNodeFailing() {
		var response = mapper.toResponse(new IllegalStateException("Something broke"));
		assertThat(response.getStatus(), is(500));

		var body = (ErrorResponse) response.getEntity();
		assertThat(body.code(), is("node:error"));
		assertThat(body.errors().size(), is(1));

		var detail = body.errors().get(0);
		assertThat(detail.code(), is("node:error"));
		assertThat(detail.path(), is(nullValue()));
	}

	/**
	 * The message of the failure stays in the log of the node. A client is told
	 * the node failed and nothing about what it was doing.
	 */
	@Test
	public void testTheAnswerCarriesNothingOfWhatWentWrong() {
		var response = mapper.toResponse(new IllegalStateException("/etc/secrets is missing"));
		var body = (ErrorResponse) response.getEntity();

		assertThat(body.message().contains("/etc/secrets"), is(false));
	}
}
