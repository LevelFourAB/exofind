package se.l4.exofind.engine.index.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexNoLiveGenerationException;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.IndexUnsupportedException;

public class IndexRegistryTest {
	InMemoryRegistryStorage storage;
	IndexRegistry registry;

	@BeforeEach
	void setup() {
		storage = new InMemoryRegistryStorage();
		registry = new IndexRegistry(storage, Duration.ofMinutes(5));
	}

	@Test
	public void testCreatedIndexAnswersFromItsFirstGeneration() {
		registry.create("books", "1");

		assertThat(registry.names(), contains("books"));
		assertThat(registry.resolve(IndexName.parse("books")).toString(), is("books@1"));
	}

	@Test
	public void testCreatingTheSameNameTwiceIsRefused() {
		registry.create("books", "1");

		assertThrows(ValidationException.class, () -> registry.create("books", "1"));
	}

	/**
	 * Creating an index is a conditional write, so a node that lost the race
	 * rebuilds its change on what the other wrote - and finds the name taken.
	 */
	@Test
	public void testCreateRacingAnotherNodeSeesWhatItWrote() {
		var other = new IndexRegistry(storage, Duration.ofMinutes(5));
		other.create("books", "1");

		assertThrows(ValidationException.class, () -> registry.create("books", "1"));
		assertThat(registry.names(), contains("books"));
	}

	/**
	 * Two nodes creating different indexes both get theirs, as the one that
	 * lost the race writes again on top of what the other left.
	 */
	@Test
	public void testConcurrentCreatesBothSurvive() {
		var other = new IndexRegistry(storage, Duration.ofMinutes(5));

		registry.create("books", "1");
		storage.refuseNextWrite = true;
		other.create("movies", "1");

		assertThat(other.names(), containsInAnyOrder("books", "movies"));
	}

	/**
	 * A change that keeps losing the race is given up on rather than retried
	 * forever, and leaves the registry as it was.
	 */
	@Test
	public void testChangeThatKeepsLosingIsRefused() {
		storage.refuseEveryWrite = true;

		assertThrows(RegistryException.class, () -> registry.create("books", "1"));
		assertThat(registry.names(), emptyIterable());
	}

	@Test
	public void testStorageThatCannotBeReachedIsAnError() {
		storage.unreachable = true;

		assertThrows(RegistryException.class, () -> registry.create("books", "1"));
	}

	@Test
	public void testAddedGenerationDoesNotBecomeLive() {
		registry.create("books", "1");
		registry.addGeneration("books", "2");

		assertThat(registry.resolve(IndexName.parse("books")).toString(), is("books@1"));
		assertThat(registry.resolve(IndexName.parse("books@2")).toString(), is("books@2"));
	}

	@Test
	public void testPromotedGenerationBecomesWhatTheNameAnswers() {
		registry.create("books", "1");
		registry.addGeneration("books", "2");
		registry.promote("books", "2");

		assertThat(registry.resolve(IndexName.parse("books")).toString(), is("books@2"));
	}

	@Test
	public void testPromotingAGenerationThatIsNotThereIsRefused() {
		registry.create("books", "1");

		assertThrows(IndexNotFoundException.class, () -> registry.promote("books", "7"));
	}

	@Test
	public void testLiveGenerationCannotBeRemoved() {
		registry.create("books", "1");

		assertThrows(ValidationException.class, () -> registry.removeGeneration("books", "1"));
	}

	@Test
	public void testRemovedGenerationIsNoLongerResolved() {
		registry.create("books", "1");
		registry.addGeneration("books", "2");
		registry.removeGeneration("books", "2");

		assertThrows(
			IndexNotFoundException.class,
			() -> registry.resolve(IndexName.parse("books@2"))
		);
	}

	/**
	 * An index answering for none of its generations is told apart from one
	 * that is not there, because promoting a generation is what fixes it.
	 */
	@Test
	public void testIndexAnsweringForNoGenerationSaysSo() {
		storage.set(
			IndexRegistryStore.newBuilder()
				.addIndexes(
					IndexEntry.newBuilder()
						.setName("books")
						.addGenerations(GenerationEntry.newBuilder().setName("1"))
				)
				.build()
		);

		registry.refresh();

		assertThrows(
			IndexNoLiveGenerationException.class,
			() -> registry.resolve(IndexName.parse("books"))
		);
	}

	/**
	 * An entry naming something this build does not have is refused rather than
	 * resolved, as resolving it would answer from a generation the entry did
	 * not name.
	 */
	@Test
	public void testIndexNeedingUnknownFeaturesIsRefused() {
		storage.set(
			IndexRegistryStore.newBuilder()
				.addIndexes(
					IndexEntry.newBuilder()
						.setName("books")
						.addGenerations(GenerationEntry.newBuilder().setName("1"))
						.setLive("1")
						.addRequiredFeatures("generations.written-to")
				)
				.build()
		);

		registry.refresh();

		assertThat(registry.names(), contains("books"));
		assertThrows(
			IndexUnsupportedException.class,
			() -> registry.resolve(IndexName.parse("books"))
		);
	}

	/**
	 * A feature a node does not know is carried through a change that node
	 * makes, so an entry never comes back looking like one every node can
	 * resolve.
	 */
	@Test
	public void testUnknownFeatureSurvivesAChangeMadeElsewhere() {
		storage.set(
			IndexRegistryStore.newBuilder()
				.addIndexes(
					IndexEntry.newBuilder()
						.setName("books")
						.addGenerations(GenerationEntry.newBuilder().setName("1"))
						.setLive("1")
						.addRequiredFeatures("generations.written-to")
				)
				.build()
		);

		registry.refresh();
		registry.create("movies", "1");

		assertThat(
			registry.get("books").orElseThrow().requiredFeatures(),
			contains("generations.written-to")
		);
	}

	/**
	 * A name that could not have been created here becomes a directory if it is
	 * used, so it is passed over instead.
	 */
	@Test
	public void testEntryWithAnUnusableNameIsIgnored() {
		storage.set(
			IndexRegistryStore.newBuilder()
				.addIndexes(IndexEntry.newBuilder().setName("../escaped").setLive("1"))
				.addIndexes(
					IndexEntry.newBuilder()
						.setName("books")
						.addGenerations(GenerationEntry.newBuilder().setName("1"))
						.addGenerations(GenerationEntry.newBuilder().setName("../escaped"))
						.setLive("1")
				)
				.build()
		);

		registry.refresh();

		assertThat(registry.names(), contains("books"));
		assertThat(
			registry.get("books").orElseThrow().generations()
				.collect(RegisteredIndex.Generation::name),
			contains("1")
		);
	}

	/**
	 * A name this node has not seen is looked up right away, so an index
	 * created a moment ago somewhere else can be used without waiting for the
	 * next refresh - but a run of names that are not there costs one read
	 * rather than one each.
	 */
	@Test
	public void testUnknownNameIsLookedUpAtMostOncePerInterval() {
		var other = new IndexRegistry(storage, Duration.ofMinutes(5));
		other.create("books", "1");

		storage.reads = 0;

		assertThat(registry.get("books").isPresent(), is(true));
		assertThat(storage.reads, is(1));

		registry.get("movies");
		registry.get("magazines");

		assertThat(storage.reads, is(1));
	}

	@Test
	public void testGenerationsAreNamedByCountingUp() {
		registry.create("books", IndexRegistry.nextGeneration(null));

		var books = registry.get("books").orElseThrow();
		assertThat(IndexRegistry.nextGeneration(books), is("2"));

		registry.addGeneration("books", "2");
		assertThat(IndexRegistry.nextGeneration(registry.get("books").orElseThrow()), is("3"));
	}

	/**
	 * A generation named by hand says nothing about what comes next, so
	 * counting carries on from the numbers that are there.
	 */
	@Test
	public void testNamedGenerationDoesNotBreakCountingUp() {
		registry.create("books", "1");
		registry.addGeneration("books", "blue");

		assertThat(IndexRegistry.nextGeneration(registry.get("books").orElseThrow()), is("2"));
	}
}
