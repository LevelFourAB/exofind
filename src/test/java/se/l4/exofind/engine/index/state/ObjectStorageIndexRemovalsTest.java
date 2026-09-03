package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexStorageHeldException;
import se.l4.exofind.engine.storage.ObjectStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Tests for how a deleted index leaves the bucket: the mark a delete writes,
 * what a sweep removes by it, and what a creation clears.
 */
public class ObjectStorageIndexRemovalsTest {
	ObjectStorage storage;
	ObjectStorageIndexRemovals removals;

	@BeforeEach
	void setup() throws IOException {
		storage = new ObjectStorage(
			TestObjectStorage.url(),
			TestObjectStorage.ACCESS_KEY,
			TestObjectStorage.SECRET_KEY,
			Optional.empty(),
			TestObjectStorage.BUCKET,
			Optional.of("test" + RandomStringUtils.insecure().nextAlphabetic(10)),
			false
		);

		removals = new ObjectStorageIndexRemovals(storage);
	}

	@AfterEach
	void cleanup() {
		for(var key : keysUnder("")) {
			storage.client().deleteObject(
				DeleteObjectRequest.builder()
					.bucket(storage.bucket())
					.key(key)
					.build()
			);
		}
	}

	/**
	 * Put an object under the path the indexes live in, standing in for what
	 * a push or a settings write leaves behind.
	 */
	private void put(String path) {
		storage.client().putObject(
			PutObjectRequest.builder()
				.bucket(storage.bucket())
				.key(storage.indexesPath() + "/" + path)
				.build(),
			RequestBody.fromString("contents of " + path)
		);
	}

	private void putGeneration(String index, String generation) {
		put(index + "/" + generation + "/" + LocalCopy.MANIFEST_FILE);
		put(index + "/" + generation + "/1/_0.cfs");
	}

	/**
	 * Every key under a path relative to where the indexes live, as paths
	 * relative to it.
	 */
	private ListIterable<String> keysUnder(String path) {
		var prefix = storage.indexesPath() + "/" + path;
		var keys = Lists.mutable.<String>empty();

		var objects = storage.client().listObjectsV2Paginator(
			b -> b.bucket(storage.bucket()).prefix(prefix)
		).contents();

		for(var object : objects) {
			keys.add(object.key().substring(storage.indexesPath().length() + 1));
		}

		return keys;
	}

	@Test
	public void testMarkIsReadBackAndTakenAway() throws IOException {
		var before = Instant.now().minus(Duration.ofMinutes(1));
		removals.mark(IndexName.of("books"));

		var markedAt = removals.markedAt(IndexName.of("books"));
		assertThat(markedAt.isPresent(), is(true));
		assertThat(markedAt.get(), is(greaterThan(before)));
		assertThat(markedAt.get(), is(lessThan(Instant.now().plus(Duration.ofMinutes(1)))));

		assertThat(keysUnder("books/"), contains("books/" + IndexRemovals.MARK_FILE));

		assertThat(removals.unmark(IndexName.of("books")), is(true));
		assertThat(removals.markedAt(IndexName.of("books")), is(Optional.empty()));
		assertThat(removals.unmark(IndexName.of("books")), is(false));
	}

	/**
	 * A generation's mark sits beside its manifest, an index's beside its
	 * generations, and neither is mistaken for the other.
	 */
	@Test
	public void testMarksOverIndexAndGenerationAreApart() throws IOException {
		putGeneration("books", "1");

		removals.mark(IndexName.of("books", "1"));

		assertThat(removals.markedAt(IndexName.of("books")), is(Optional.empty()));
		assertThat(removals.markedAt(IndexName.of("books", "1")).isPresent(), is(true));
		assertThat(keysUnder("books/"), containsInAnyOrder(
			"books/1/" + LocalCopy.MANIFEST_FILE,
			"books/1/1/_0.cfs",
			"books/1/" + IndexRemovals.MARK_FILE
		));
	}

	@Test
	public void testListMarksReportsWhatIsWanted() throws IOException {
		putGeneration("books", "1");
		putGeneration("books", "2");
		putGeneration("movies", "1");
		putGeneration("movies", "2");
		putGeneration("shows", "1");

		removals.mark(IndexName.of("books"));
		removals.mark(IndexName.of("books", "2"));
		removals.mark(IndexName.of("movies", "2"));

		// A marked index is reported once, without its generations
		var all = removals.listMarks(target -> true);
		assertThat(
			all.collect(IndexRemovals.Mark::target),
			contains(IndexName.of("books"), IndexName.of("movies", "2"))
		);

		// What is not wanted is not read
		var withoutBooks = removals.listMarks(target -> !target.index().equals("books"));
		assertThat(
			withoutBooks.collect(IndexRemovals.Mark::target),
			contains(IndexName.of("movies", "2"))
		);
	}

	/**
	 * Removing an index takes everything under its prefix: every generation,
	 * the settings, and last the mark.
	 */
	@Test
	public void testRemovingAnIndexTakesEverythingUnderIt() throws IOException {
		putGeneration("books", "1");
		putGeneration("books", "2");
		put("books/settings.ef.bin");
		putGeneration("movies", "1");
		removals.mark(IndexName.of("books"));

		assertThat(removals.remove(IndexName.of("books")), is(true));

		assertThat(keysUnder("books/"), emptyIterable());
		assertThat(keysUnder("movies/"), containsInAnyOrder(
			"movies/1/" + LocalCopy.MANIFEST_FILE,
			"movies/1/1/_0.cfs"
		));
	}

	@Test
	public void testRemovingAGenerationLeavesTheRestOfItsIndex() throws IOException {
		putGeneration("books", "1");
		putGeneration("books", "2");
		put("books/settings.ef.bin");
		removals.mark(IndexName.of("books", "1"));

		assertThat(removals.remove(IndexName.of("books", "1")), is(true));

		assertThat(keysUnder("books/"), containsInAnyOrder(
			"books/2/" + LocalCopy.MANIFEST_FILE,
			"books/2/1/_0.cfs",
			"books/settings.ef.bin"
		));
	}

	/**
	 * Without its mark a removal has nothing to go on - a creation may have
	 * taken the prefix back - so it stops before touching anything.
	 */
	@Test
	public void testRemovalWithoutAMarkStops() throws IOException {
		putGeneration("books", "1");

		assertThat(removals.remove(IndexName.of("books")), is(false));

		assertThat(keysUnder("books/"), containsInAnyOrder(
			"books/1/" + LocalCopy.MANIFEST_FILE,
			"books/1/1/_0.cfs"
		));
	}

	/**
	 * Creating an index under a deleted name clears everything the delete
	 * left, settings included, so the new index starts out empty and with
	 * the ranking its definition gives.
	 */
	@Test
	public void testPreparingForAnIndexClearsAMarkedPrefix() throws IOException {
		putGeneration("books", "1");
		put("books/settings.ef.bin");
		removals.mark(IndexName.of("books"));

		removals.prepareForIndex("books");
		removals.prepareForGeneration(IndexName.of("books", "1"));

		assertThat(keysUnder("books/"), emptyIterable());
	}

	/**
	 * A prefix nothing marked is not the creation's to clear. Old settings
	 * beside no generation are left, the way they always were.
	 */
	@Test
	public void testPreparingForAnIndexLeavesAnUnmarkedPrefix() throws IOException {
		put("books/settings.ef.bin");

		removals.prepareForIndex("books");
		removals.prepareForGeneration(IndexName.of("books", "1"));

		assertThat(keysUnder("books/"), contains("books/settings.ef.bin"));
	}

	@Test
	public void testPreparingForAGenerationClearsOnlyItsOwnPrefix() throws IOException {
		putGeneration("books", "1");
		putGeneration("books", "2");
		put("books/settings.ef.bin");
		removals.mark(IndexName.of("books", "2"));

		removals.prepareForGeneration(IndexName.of("books", "2"));

		assertThat(keysUnder("books/"), containsInAnyOrder(
			"books/1/" + LocalCopy.MANIFEST_FILE,
			"books/1/1/_0.cfs",
			"books/settings.ef.bin"
		));
	}

	/**
	 * A manifest nothing marked is data nothing said was deleted, and a
	 * generation created over it would open holding its documents. Refused,
	 * with the storage left as it is for a repair or a hand to sort out.
	 */
	@Test
	public void testPreparingForAGenerationRefusesAnUnmarkedManifest() throws IOException {
		putGeneration("books", "1");

		assertThrows(
			IndexStorageHeldException.class,
			() -> removals.prepareForGeneration(IndexName.of("books", "1"))
		);

		assertThat(keysUnder("books/"), containsInAnyOrder(
			"books/1/" + LocalCopy.MANIFEST_FILE,
			"books/1/1/_0.cfs"
		));
	}

	/**
	 * Files without a manifest over them are an interrupted push, which a
	 * generation created there never reads - its writer's sweep removes them
	 * in time.
	 */
	@Test
	public void testPreparingForAGenerationPassesFilesWithoutAManifest() throws IOException {
		put("books/1/1/_0.cfs");

		removals.prepareForGeneration(IndexName.of("books", "1"));

		assertThat(keysUnder("books/"), contains("books/1/1/_0.cfs"));
	}
}
