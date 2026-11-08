package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;

/**
 * Tests for a definition update whose file cannot be written: the index must
 * go on indexing under the definition it has stored, so that no document is
 * indexed under a definition that a restart does not read back.
 */
public class IndexDefinitionWriteFailureTest extends AbstractIndexTest {
	@Test
	public void aDefinitionUpdateThatCannotBeStoredChangesNothing() throws IOException {
		// Given
		var index = create("books", definition());
		index.addDocument(document("k0"));
		index.commit();

		// When
		failDefinitionUpdate(index, indexRoot.resolve("books"));

		// Then
		assertThat(index.getDefinition().containsFields("extra"), is(false));
		assertThat(index.getField("extra").isPresent(), is(false));

		ValidationException refused = null;
		try {
			index.addDocument(documentWithExtra("e1"));
		} catch(ValidationException e) {
			refused = e;
		}

		assertThat("a document giving `extra` is refused", refused != null, is(true));
		assertThat(
			refused.getErrors().collect(error -> error.getCode()).toList(),
			contains("document:field_unknown")
		);
	}

	@Test
	public void everyDocumentCanBePatchedAfterARestartThatFollowsAFailedUpdate()
		throws IOException {
		// Given
		var index = create("books", definition());
		index.addDocument(document("k0"));
		index.commit();
		failDefinitionUpdate(index, indexRoot.resolve("books"));

		try {
			index.addDocument(documentWithExtra("e1"));
		} catch(ValidationException e) {
			// Refusing it is what keeps the stored definition true
		}
		index.commit();

		// When
		close(index);
		var reopened = create("books");

		// Then
		var keys = new ArrayList<String>();
		reopened.scanDocuments(null, 10, document -> keys.add((String) document.get("id")));
		for(var key : keys) {
			var patch = DocumentPatch.replacing(
				Sets.immutable.of("id", "name"),
				Lists.immutable.of(
					new Document.Value("id", key),
					new Document.Value("name", "patched")
				)
			);

			reopened.updateDocument(patch);
		}

		reopened.commit();
		for(var key : keys) {
			assertThat(reopened.getDocument(key).get("name"), is("patched"));
		}
	}

	/**
	 * Add the field {@code extra} while the directory of the index refuses new
	 * files, and then make it accept files again.
	 */
	private static void failDefinitionUpdate(Index index, Path path) throws IOException {
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			index.updateDefinition(
				definition().putFields("extra", string().setStored(true).build()).build()
			);
		} catch(IOException | RuntimeException e) {
			// The disk refused the definition file
		} finally {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	private static Document document(String key) {
		return new Document(
			new Document.Value("id", key),
			new Document.Value("name", "name of " + key)
		);
	}

	private static Document documentWithExtra(String key) {
		return new Document(
			new Document.Value("id", key),
			new Document.Value("name", "name of " + key),
			new Document.Value("extra", "a value only the new definition knows")
		);
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields("name", string().setStored(true).build());
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.getDefaultInstance())
			);
	}
}
