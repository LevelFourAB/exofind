package se.l4.exofind.engine.index.state;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

import java.time.Duration;

import org.junit.jupiter.api.Test;

public class IndexWriteLoadTest {
	private static final Duration HALF_LIFE = Duration.ofMinutes(10);

	@Test
	void testUnknownIndexReadsAsIdle() {
		var load = new IndexWriteLoad(HALF_LIFE);

		assertThat(load.get("books", 0), is(0d));
	}

	@Test
	void testWritesAccumulate() {
		var load = new IndexWriteLoad(HALF_LIFE);

		load.record("books", 10, 0);
		load.record("books", 5, 0);

		assertThat(load.get("books", 0), is(15d));
	}

	@Test
	void testIndexesAreCountedApart() {
		var load = new IndexWriteLoad(HALF_LIFE);

		load.record("books", 10, 0);

		assertThat(load.get("games", 0), is(0d));
	}

	@Test
	void testAHalfLifeHalvesTheFigure() {
		var load = new IndexWriteLoad(HALF_LIFE);

		load.record("books", 10, 0);

		assertThat(load.get("books", HALF_LIFE.toMillis()), closeTo(5d, 0.0001));
		assertThat(load.get("books", HALF_LIFE.toMillis() * 2), closeTo(2.5d, 0.0001));
	}

	@Test
	void testRecordingDecaysWhatCameBefore() {
		var load = new IndexWriteLoad(HALF_LIFE);

		load.record("books", 10, 0);
		load.record("books", 10, HALF_LIFE.toMillis());

		assertThat(load.get("books", HALF_LIFE.toMillis()), closeTo(15d, 0.0001));
	}

	@Test
	void testAFigureStampedInTheFutureDoesNotGrow() {
		var load = new IndexWriteLoad(HALF_LIFE);

		load.record("books", 10, HALF_LIFE.toMillis());

		assertThat(load.get("books", 0), is(10d));
	}
}
