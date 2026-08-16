package se.l4.exofind.engine.query;

import java.time.Duration;

/**
 * Rank a document higher the more recent the value of a field is.
 *
 * @param field
 *   name of the field, as it is called in the definition of the index
 * @param halfLife
 *   how long it takes for the signal to be worth half as much
 * @param weight
 *   how much the signal can lift a document at most, as a share of its score
 */
public record DecaySignal(String field, Duration halfLife, float weight)
	implements RankingSignal {
	public DecaySignal {
		if(field == null) {
			throw new IllegalArgumentException("A signal reads a field, so it has to name one");
		}

		if(halfLife == null || halfLife.isZero() || halfLife.isNegative()) {
			throw new IllegalArgumentException("The half life of a signal has to be longer than nothing");
		}

		if(!(weight >= 0) || !Float.isFinite(weight)) {
			throw new IllegalArgumentException("A signal can not weigh less than nothing");
		}
	}

	@Override
	public String type() {
		return "decay";
	}

	/**
	 * Get this signal weighing the given amount.
	 *
	 * @param weight
	 * @return
	 */
	public DecaySignal withWeight(float weight) {
		return new DecaySignal(field, halfLife, weight);
	}
}
