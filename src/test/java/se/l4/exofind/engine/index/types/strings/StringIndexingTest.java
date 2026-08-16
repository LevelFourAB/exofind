package se.l4.exofind.engine.index.types.strings;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.AbstractIndexTest;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;

public class StringIndexingTest extends AbstractIndexTest {
	@Test
	public void testStoredButNotIndexed() throws IOException {
		var index = create(
			"test",
			IndexDef.newBuilder().putFields(
				"field1",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.newBuilder().build())
							.build()
					)
					.setStored(true)
					.build()
			)
		);

		var doc = new Document(
			new Document.Value("field1", "value1")
		);
		index.addDocument(doc);
	}
}
