package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Tests for what the shapes make of a value. Decay computes its halvings
 * itself rather than asking {@link Math#pow}, so what it answers is held
 * against the full precision answer here - the claim is an order under the
 * ulp of the float a score is, and an edit that loosens the series would
 * otherwise change rankings with nothing failing.
 */
public class RankingSignalsTest {
	@Test
	public void testDecayMatchesFullPrecisionHalving() {
		var halfLife = Duration.ofDays(90).toMillis();
		var now = 1_766_000_000_000L;
		var decay = new RankingSignals.Decay(halfLife, now);

		for(var age = 1L; age < halfLife * 128; age += halfLife / 97) {
			var exact = Math.pow(0.5, age / (double) halfLife);
			var answered = decay.contribution(now - age);

			assertThat(
				"age of " + age + " ms",
				Math.abs(answered - exact) / exact,
				is(lessThan(1e-7))
			);
		}
	}

	@Test
	public void testDecayOfTheFutureIsWhole() {
		var decay = new RankingSignals.Decay(1000, 5000);

		assertThat(decay.contribution(5000), is(1d));
		assertThat(decay.contribution(9000), is(1d));
	}

	@Test
	public void testDecayFarBeyondPrecisionIsNothing() {
		var decay = new RankingSignals.Decay(1, 5_000_000);

		assertThat(decay.contribution(0), is(0d));
	}

	@Test
	public void testSaturationAtThePivotIsHalf() {
		var saturation = new RankingSignals.Saturation(50);

		assertThat(saturation.contribution(50), is(closeTo(0.5, 1e-9)));
		assertThat(saturation.contribution(0), is(0d));
		assertThat(saturation.contribution(-10), is(0d));
	}

	/**
	 * A score already in its range passes through as it is, and one outside
	 * it is held to the ends - the bound is what lets a search skip documents
	 * that cannot compete.
	 */
	@Test
	public void testLinearIsTheShareOfTheCeiling() {
		var linear = new RankingSignals.Linear(1);

		assertThat(linear.contribution(0.25), is(closeTo(0.25, 1e-9)));
		assertThat(linear.contribution(1), is(1d));
		assertThat(linear.contribution(4), is(1d));
		assertThat(linear.contribution(0), is(0d));
		assertThat(linear.contribution(-0.5), is(0d));

		var percent = new RankingSignals.Linear(100);
		assertThat(percent.contribution(30), is(closeTo(0.3, 1e-9)));
	}
}
