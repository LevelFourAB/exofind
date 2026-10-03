package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.types.ObjectFieldType;

/**
 * That a definition whose objects nest as deep as the engine accepts is read
 * back after the index opens again, and that one nesting deeper is refused.
 *
 * <p>The definition is stored as Protocol Buffers, and every object level
 * adds four message levels to it. The default parser of Protocol Buffers
 * stops at 100 message levels, so a definition the engine accepted could
 * otherwise be written and then never read.
 */
public class IndexDefinitionNestingTest extends AbstractIndexTest {
	private static FieldDef.Builder leaf() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			)
			.setFilter(FilterConfig.getDefaultInstance());
	}

	private static FieldDef object(FieldDef inner) {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder().putFields("o", inner)
				)
			)
			.build();
	}

	/** A definition with objects nested {@code levels} deep, and a filter field at the bottom. */
	private static IndexDef.Builder nested(int levels) {
		var field = leaf().build();
		for(var i = 0; i < levels; i++) {
			field = object(field);
		}

		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
					)
					.setPrimaryKey(true)
					.build()
			)
			.putFields("o", field);
	}

	private static Document document(String id, int levels) {
		Object value = "deep";
		for(var i = 0; i < levels; i++) {
			value = new Document(new Document.Value("o", value));
		}

		return new Document(
			new Document.Value("id", id),
			new Document.Value("o", value)
		);
	}

	@Test
	public void testTheDeepestDefinitionAcceptedIsReadBackAfterTheIndexIsOpenedAgain()
		throws IOException
	{
		// Given an index whose objects nest as deep as the engine accepts
		var index = create("deep", nested(ObjectFieldType.MAX_DEPTH));
		var version = index.getDefinitionVersion();

		// And a document written and committed under that definition
		index.addDocument(document("1", ObjectFieldType.MAX_DEPTH));
		index.commit();

		// When the index is closed and opened again from the same directory
		close(index);
		var reopened = create("deep");

		// Then the definition and the document come back
		assertThat(reopened.getDefinitionVersion(), is(version));
		assertThat(reopened.getDocument("1"), is(notNullValue()));
	}

	@Test
	public void testTheDefinitionFileOfTheDeepestDefinitionParsesWithTheDefaultLimit()
		throws IOException
	{
		// Given an index whose objects nest as deep as the engine accepts
		var index = create("file", nested(ObjectFieldType.MAX_DEPTH));

		// When the definition file is parsed with the default nesting limit
		IndexDef parsed;
		try(var in = Files.newInputStream(indexRoot.resolve("file").resolve(Index.DEFINITION_FILE))) {
			parsed = IndexDef.parseFrom(in);
		}

		// Then it is the definition the index holds
		assertThat(parsed, is(index.getDefinition()));
	}

	@Test
	public void testADefinitionNestingDeeperIsRefused() {
		var e = assertThrows(
			ValidationException.class,
			() -> create("deeper", nested(ObjectFieldType.MAX_DEPTH + 1))
		);

		assertThat(
			e.getErrors().collect(error -> error.getCode()).toList(),
			hasItem("index:field:object:objects_too_deep")
		);
	}
}
