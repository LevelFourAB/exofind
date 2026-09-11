package se.l4.exofind.engine.query;

/**
 * Rank a document higher the closer the value of a field is to a ceiling, as
 * {@code value / ceiling} held between zero and one.
 *
 * The shape for a score computed outside the engine that already lies in a
 * known range - an engagement score between zero and one, a margin in
 * percent. {@link SaturationSignal} would bend such a score; this one passes
 * it through as it is, with the ceiling saying what counts as all of it.
 *
 * @param field
 *   name of the field, as it is called in the definition of the index
 * @param ceiling
 *   the value that counts for all of what the signal can give
 * @param weight
 *   how much the signal can lift a document at most, as a share of its score
 */
public record LinearSignal(String field, double ceiling, float weight)
	implements RankingSignal {
	public LinearSignal {
		if(field == null) {
			throw new IllegalArgumentException("A signal reads a field, so it has to name one");
		}

		if(!(ceiling > 0) || !Double.isFinite(ceiling)) {
			throw new IllegalArgumentException("The ceiling of a signal has to be above zero");
		}

		if(!(weight >= 0) || !Float.isFinite(weight)) {
			throw new IllegalArgumentException("A signal can not weigh less than nothing");
		}
	}

	@Override
	public String type() {
		return "linear";
	}

	/**
	 * Get this signal weighing the given amount.
	 *
	 * @param weight
	 * @return
	 */
	public LinearSignal withWeight(float weight) {
		return new LinearSignal(field, ceiling, weight);
	}
}
