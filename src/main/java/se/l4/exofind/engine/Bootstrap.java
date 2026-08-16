package se.l4.exofind.engine;

import java.io.IOException;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class Bootstrap {
	private final Indexes indexes;

	public Bootstrap(Indexes indexes) {
		this.indexes = indexes;
	}

	@Startup
	void onStart() throws IOException {
		System.out.println("Bootstrap.onStart");

		// var index = indexes.get("test").orElseThrow();

		// index.addDocument(new Document(new Document.Value("name", "test")));
		// index.commit();

		System.out.println("Done");
		// index.close();
	}
}
