package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;

import se.l4.exofind.engine.index.state.ChangeLogStore;

public class ChangeLogTest {
	/**
	 * A field number that storage.proto does not use yet in a change log.
	 */
	private static final int FUTURE_FIELD = 99;

	@TempDir
	Path dir;

	@Test
	public void aKeyRecordedTwiceIsOneEntry() {
		var log = new ChangeLog();

		log.record(new BytesRef("a"));
		log.record(new BytesRef("a"));

		assertThat(log.size(), is(1));
	}

	@Test
	public void aSnapshotHoldsWhatWasRecordedBeforeIt() {
		var log = new ChangeLog();

		log.record(new BytesRef("a"));
		log.record(new BytesRef("b"));

		var snapshot = log.snapshot();
		log.record(new BytesRef("c"));

		assertThat(snapshot.keys(), containsInAnyOrder(new BytesRef("a"), new BytesRef("b")));
	}

	@Test
	public void forgettingASnapshotDropsItsKeys() {
		var log = new ChangeLog();

		log.record(new BytesRef("a"));
		log.record(new BytesRef("b"));

		log.forget(log.snapshot());

		assertTrue(log.isEmpty());
	}

	@Test
	public void aKeyRecordedAgainAfterTheSnapshotSurvivesTheForget() {
		var log = new ChangeLog();

		log.record(new BytesRef("a"));
		log.record(new BytesRef("b"));

		var snapshot = log.snapshot();
		log.record(new BytesRef("a"));
		log.forget(snapshot);

		assertThat(log.snapshot().keys(), containsInAnyOrder(new BytesRef("a")));
	}

	@Test
	public void aKeyRecordedOnlyAfterTheSnapshotSurvivesTheForget() {
		var log = new ChangeLog();

		log.record(new BytesRef("a"));

		var snapshot = log.snapshot();
		log.record(new BytesRef("b"));
		log.forget(snapshot);

		assertThat(log.snapshot().keys(), containsInAnyOrder(new BytesRef("b")));
	}

	@Test
	public void aSavedLogLoadsWithTheSameKeys() throws IOException {
		var log = new ChangeLog();
		log.record(new BytesRef("a"));
		log.record(new BytesRef("b"));

		var file = dir.resolve("changes.ef.bin");
		log.save(file);

		var loaded = ChangeLog.load(file);
		assertThat(loaded.snapshot().keys(), containsInAnyOrder(new BytesRef("a"), new BytesRef("b")));
	}

	@Test
	public void savingAgainLeavesNothingBesideTheLog() throws IOException {
		var log = new ChangeLog();
		log.record(new BytesRef("a"));

		var file = dir.resolve("changes.ef.bin");
		log.save(file);

		log.record(new BytesRef("b"));
		log.save(file);

		try(var files = Files.list(dir)) {
			assertThat(
				files.map(f -> f.getFileName().toString()).toList(),
				contains("changes.ef.bin")
			);
		}
	}

	@Test
	public void theSameKeysAlwaysSaveToTheSameBytes() throws IOException {
		var first = new ChangeLog();
		first.record(new BytesRef("a"));
		first.record(new BytesRef("b"));

		var second = new ChangeLog();
		second.record(new BytesRef("b"));
		second.record(new BytesRef("a"));

		var firstFile = dir.resolve("first");
		var secondFile = dir.resolve("second");
		first.save(firstFile);
		second.save(secondFile);

		assertThat(Files.readAllBytes(firstFile), is(Files.readAllBytes(secondFile)));
	}

	/**
	 * A log that a newer version wrote can hold fields this version does not
	 * know. Saving the log again, which every commit of the holder does, must
	 * keep them.
	 */
	@Test
	public void savingALogKeepsTheFieldsANewerVersionWrote() throws IOException {
		// Given
		var file = dir.resolve("changes.ef.bin");
		var newer = ChangeLogStore.newBuilder()
			.addKeys(ByteString.copyFromUtf8("a"))
			.addKeys(ByteString.copyFromUtf8("b"))
			.setUnknownFields(
				UnknownFieldSet.newBuilder()
					.addField(FUTURE_FIELD, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
					.build()
			)
			.build();
		Files.write(file, newer.toByteArray());

		// When
		var log = ChangeLog.load(file);
		log.record(new BytesRef("c"));
		log.save(file);

		// Then
		var saved = ChangeLogStore.parseFrom(Files.readAllBytes(file));
		assertThat(
			saved.getKeysList(),
			contains(
				ByteString.copyFromUtf8("a"),
				ByteString.copyFromUtf8("b"),
				ByteString.copyFromUtf8("c")
			)
		);
		assertThat(saved.getUnknownFields().hasField(FUTURE_FIELD), is(true));
	}
}
