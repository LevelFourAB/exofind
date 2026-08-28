package se.l4.exofind.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.eclipsecollections.EclipseCollectionsModule;

import se.l4.exofind.engine.api.v1alpha1.search.model.DocumentSerializer;
import se.l4.exofind.engine.index.Document;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class CustomProviders {
	@Produces
	public ObjectMapper objectMapper() {
		return new ObjectMapper()
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
