package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.jboss.logmanager.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;

/**
 * Tests for what an index does after a write fails inside Lucene, such as a
 * commit on a full disk: the index must take writes again once the disk
 * accepts files, and the changes the failure dropped must be reported.
 */
public class IndexWriteFailureTest extends AbstractIndexTest {
	private org.jboss.logmanager.Logger backing;
	private Capture capture;

	@BeforeEach
	void captureLogs() {
		backing = org.jboss.logmanager.Logger.getLogger("se.l4.exofind.engine.index");
		capture = new Capture();
		backing.addHandler(capture);
	}

	@AfterEach
	void releaseLogs() {
		backing.removeHandler(capture);
	}

	@Test
	public void anIndexTakesWritesAgainOnceTheDiskAcceptsFiles() throws IOException {
		// Given
		var index = create("books", definition());
		var path = indexRoot.resolve("books");
		addDocuments(index, "k", 3);
		index.commit();
		addDocuments(index, "u", 2);

		// When
		failCommit(index, path);

		// Then
		addAfterRecovery(index, new Document(
			new Document.Value("id", "after"),
			new Document.Value("name", "written after the disk recovered")
		));
		index.commit();
		assertThat(index.getDocument("after"), is(notNullValue()));
	}

	@Test
	public void changesDroppedByAFailedCommitAreReportedAsAnError() throws IOException {
		// Given
		var index = create("books", definition());
		var path = indexRoot.resolve("books");
		addDocuments(index, "k", 3);
		index.commit();
		addDocuments(index, "u", 2);

		// When
		failCommit(index, path);
		try {
			index.addDocument(new Document(new Document.Value("id", "after")));
			index.commit();
		} catch(IOException | RuntimeException e) {
			// What the write answers is checked by the test above
		}

		// Then
		var errors = capture.records.stream()
			.filter(record -> record.getLevel().intValue() >= Level.ERROR.intValue())
			.toList();
		assertThat(
			"An error names the index whose acknowledged changes were dropped",
			errors.stream().anyMatch(record -> describe(record).contains("books")),
			is(true)
		);
	}

	/**
	 * Run a commit while the directory of the index refuses new files, and
	 * then make it accept files again.
	 */
	private static void failCommit(Index index, Path path) throws IOException {
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			index.commit();
		} catch(IOException | RuntimeException e) {
			// The disk refused the files of the commit
		} finally {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	/**
	 * Write a document once the disk accepts files again. An index that
	 * answers that it must be pulled first is pulled, and the write is made
	 * again.
	 */
	private static void addAfterRecovery(Index index, Document document) throws IOException {
		try {
			index.addDocument(document);
		} catch(IndexOutOfDateException e) {
			index.pull();
			index.addDocument(document);
		}
	}

	private static void addDocuments(Index index, String prefix, int count) throws IOException {
		for(var i = 0; i < count; i++) {
			index.addDocument(new Document(
				new Document.Value("id", prefix + i),
				new Document.Value("name", "name " + i)
			));
		}
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

	private static String describe(LogRecord record) {
		var parameters = record.getParameters() == null
			? List.of()
			: List.of(record.getParameters());
		return record.getMessage() + " " + parameters;
	}

	private static class Capture extends Handler {
		private final List<LogRecord> records = new ArrayList<>();

		@Override
		public synchronized void publish(LogRecord record) {
			records.add(record);
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	}
}
