package se.l4.exofind.engine;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.eclipsecollections.EclipseCollectionsModule;

import se.l4.exofind.engine.api.v1alpha1.search.model.DocumentSerializer;
import se.l4.exofind.engine.index.Document;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class CustomProviders {
	/**
	 * The mapper that reads every request body and writes every answer.
	 *
	 * <p>A body that holds more than one JSON value is refused rather than
	 * read up to the end of the first one. Without that, a client that sends
	 * two batches back to back as one body gets {@code 200} for the first and
	 * loses the second without a word. Newline delimited JSON is read value by
	 * value, which this does not change.
	 *
	 * <p>An object that gives one property twice is refused as well. A body
	 * read as a map, such as a document, would otherwise keep the last value
	 * and drop the first without a word.
	 */
	@Produces
	public ObjectMapper objectMapper() {
		return new ObjectMapper()
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.registerModule(new EclipseCollectionsModule())
			.registerModule(documents());
	}

	/**
	 * How a {@link Document} is written wherever one is answered on its own,
	 * rather than as part of a response that names the serializer itself. What
	 * a document is as JSON is a property of the document, so a response
	 * carrying one plainly - a line of newline delimited JSON - writes it the
	 * same way a search result does.
	 */
	private static SimpleModule documents() {
		var module = new SimpleModule();
		module.addSerializer(Document.class, new DocumentSerializer());
		return module;
	}
}
