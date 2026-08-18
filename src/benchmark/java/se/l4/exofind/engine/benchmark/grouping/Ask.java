package se.l4.exofind.engine.benchmark.grouping;

/**
 * One question, put in terms of the catalogue rather than of any one way of
 * laying it out.
 *
 * <p>A product answers it when its text matches and it holds a single variant
 * satisfying every variant condition at once - a red variant under 200 rather
 * than a red variant and, elsewhere, a cheap one. Every shape is asked the same
 * question and is free to answer it however its layout allows, which is what
 * makes the timings comparable and the answers worth comparing.
 *
 * @param text
 *   words to match in the title and description of the product, or {@code null}
 *   to ask of the whole catalogue
 * @param color
 *   the colour a variant has to come in, or {@code null} for any
 * @param maxPrice
 *   the most a variant may cost, or {@code null} for any
 * @param limit
 *   how many results to bring back
 */
public record Ask(
	String text,
	String color,
	Double maxPrice,
	int limit
) {
	/**
	 * Get whether anything here narrows by the values of a variant.
	 */
	public boolean hasVariantConditions() {
		return color != null || maxPrice != null;
	}

	public Ask withText(String text) {
		return new Ask(text, color, maxPrice, limit);
	}

	public Ask withColor(String color) {
		return new Ask(text, color, maxPrice, limit);
	}

	public Ask withMaxPrice(Double maxPrice) {
		return new Ask(text, color, maxPrice, limit);
	}

	public Ask withLimit(int limit) {
		return new Ask(text, color, maxPrice, limit);
	}

	/**
	 * Get an ask that narrows by nothing, to be added to.
	 */
	public static Ask everything() {
		return new Ask(null, null, null, 10);
	}
}
