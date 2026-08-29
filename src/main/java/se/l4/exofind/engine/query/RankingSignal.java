package se.l4.exofind.engine.query;

import java.time.Duration;

/**
 * A value the documents themselves carry, taken into how they rank.
 *
 * A clause says what a document has to be; a signal says what makes one
 * document better than another that is equally good a match - how often it was
 * bought, how recently it arrived. The value is read from a field defined for
 * sorting, shaped into a number between zero and one, and multiplied into the
 * score as {@code 1 + weight * shape}:
 *
 * <pre>
 * SearchRequest.create()
 *   .withQuery(Query.text("boots"))
 *   .withSignals(
 *     RankingSignal.saturation("purchases", 50),
 *     RankingSignal.decay("published", Duration.ofDays(30)).withWeight(0.5f)
 *   )
 *   .build()
 * </pre>
 *
 * Every shape is bounded, so a signal can lift a document by at most its weight
 * however far its value runs, and a document holding no value contributes
 * nothing rather than being multiplied away. Signals only mean something where
 * relevance is the ordering - a search that sorts by a field of its own reads
 * the field, not the score, and is left alone.
 *
 * An index declares the signals every search of it ranks by. A search giving
 * its own adds them to that ranking, or replaces it - see
 * {@link SearchRequest.Signals}. The type is the name a shape goes by outside
 * the engine, so it is never renamed or reused.
 */
public sealed interface RankingSignal permits SaturationSignal, DecaySignal {
	/**
	 * Get the unique identifier for this kind of shape.
	 *
	 * @return
	 */
	String type();

	/**
	 * Get the field the value is read from.
	 *
	 * @return
	 */
	String field();

	/**
	 * Get how much this signal can lift a document at most, as a share of its
	 * score. Zero changes nothing.
	 *
	 * @return
	 */
	float weight();

	/**
	 * Rank a document higher the further its value is above the pivot, as
	 * {@code value / (value + pivot)} - half at the pivot, approaching but
	 * never reaching one above it. The shape for a count that has no ceiling.
	 *
	 * @param field
	 *   name of the field, as it is called in the definition of the index
	 * @param pivot
	 *   the value that counts for half of what the signal can give
	 * @return
	 */
	static SaturationSignal saturation(String field, double pivot) {
		return new SaturationSignal(field, pivot, 1f);
	}

	/**
	 * Rank a document higher the more recent its value is, halving every half
	 * life. The shape for a timestamp, where what matters is age rather than
	 * the instant itself.
	 *
	 * @param field
	 *   name of the field, as it is called in the definition of the index
	 * @param halfLife
	 *   how long it takes for the signal to be worth half as much
	 * @return
	 */
	static DecaySignal decay(String field, Duration halfLife) {
		return new DecaySignal(field, halfLife, 1f);
	}
}
