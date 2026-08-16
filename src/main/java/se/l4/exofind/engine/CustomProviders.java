package se.l4.exofind.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.eclipsecollections.EclipseCollectionsModule;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class CustomProviders {
	@Produces
	public ObjectMapper objectMapper() {
		return new ObjectMapper()
			.registerModule(new EclipseCollectionsModule());
	}
}
