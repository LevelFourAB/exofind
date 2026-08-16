package se.l4.exofind.engine.query;

/**
 * Rank a document higher the further the value of a field is above a pivot.
 *
 * @param field
 *   name of the field, as it is called in the definition of the index
 * @param pivot
 *   the value that counts for half of what the signal can give
 * @param weight
 *   how much the signal can lift a document at most, as a share of its score
 */
public record SaturationSignal(String field, double pivot, float weight)
	implements RankingSignal {
	public SaturationSignal {
		if(field == null) {
			throw new IllegalArgumentException("A signal reads a field, so it has to name one");
		}

		if(!(pivot > 0) || !Double.isFinite(pivot)) {
			throw new IllegalArgumentException("The pivot of a signal has to be above zero");
		}

		if(!(weight >= 0) || !Float.isFinite(weight)) {
			throw new IllegalArgumentException("A signal can not weigh less than nothing");
		}
	}

	@Override
	public String type() {
		return "saturation";
	}

	/**
	 * Get this signal weighing the given amount.
	 *
	 * @param weight
	 * @return
	 */
	public SaturationSignal withWeight(float weight) {
		return new SaturationSignal(field, pivot, weight);
	}
}
