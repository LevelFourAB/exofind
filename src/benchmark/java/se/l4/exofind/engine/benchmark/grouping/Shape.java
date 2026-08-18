package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A way of laying a catalogue of products and their variants out in Lucene.
 *
 * <p>The five differ in one decision - what a Lucene document stands for - and
 * everything else follows from it: which searches are a single pass, which need
 * a join or a grouping, what a count of products costs, and what changing one
 * variant means. {@link ShapeIndex} is what each of them answers the same
 * questions through.
 */
public enum Shape {
	/**
	 * A product is a document and each of its variants is a document in the same
	 * block, marked as a variant. Conditions on a variant run against the
	 * variants and are joined up to the product they sit under. What Exofind
	 * does today.
	 */
	NESTED("nested"),

	/**
	 * A variant is a document, carrying the text and refinements of its product
	 * beside its own values, and products are the groups a page of variants
	 * falls into. Nothing assumes where the other variants of a product are.
	 */
	GROUPED("variants"),

	/**
	 * The same index as {@link #GROUPED}, grouped through an array indexed by
	 * the product's number rather than through a map - what a collapse over a
	 * global ordinal map does. Costs an array as long as there are products per
	 * search, whichever few of them the search matched.
	 */
	COLLAPSED("variants"),

	/**
	 * The same index again, grouped on the knowledge that the variants of a
	 * product arrive together - which the writing order gives and a merge keeps,
	 * and which an update in place would take away.
	 */
	BLOCKED("variants"),

	/**
	 * A product is a document holding every value of every variant, with nothing
	 * saying which value belonged with which.
	 */
	ROLLED_UP("rolled-up"),

	/**
	 * Products in one index and variants in another, joined by the identifier of
	 * the product.
	 */
	SPLIT("split");

	private final String layout;

	Shape(String layout) {
		this.layout = layout;
	}

	/**
	 * Get what the index this shape searches is called. Two shapes that read the
	 * same index share a name, and the index is built once for both.
	 */
	public String layout() {
		return layout;
	}

	/**
	 * Write a catalogue into an empty directory in this shape.
	 *
	 * @param size
	 *   how many products to write, taken from the start of the catalogue
	 */
	public void build(Path path, Catalog catalog, int size) throws IOException {
		switch(this) {
			case NESTED -> Layouts.nested(path, catalog, size);
			case GROUPED, COLLAPSED, BLOCKED -> Layouts.variants(path, catalog, size);
			case ROLLED_UP -> Layouts.rolledUp(path, catalog, size);
			case SPLIT -> Layouts.split(path, catalog, size);
		}
	}

	/**
	 * Open an index written in this shape, read only.
	 *
	 * <p>The caller owns what comes back and has to close it. Nothing is copied
	 * - several searches may read one directory at once.
	 *
	 * @param cached
	 *   whether the searcher keeps what a narrowing clause matched, the way a
	 *   node does. Turning it off is what tells a layout's own cost from the
	 *   cost of running a condition it has answered before
	 */
	public ShapeIndex open(Path path, boolean cached) throws IOException {
		return switch(this) {
			case NESTED -> new NestedIndex(path, cached);
			case GROUPED -> new VariantIndex(path, By.KEY, cached);
			case COLLAPSED -> new VariantIndex(path, By.ORDINAL, cached);
			case BLOCKED -> new VariantIndex(path, By.RUN, cached);
			case ROLLED_UP -> new RolledUpIndex(path, cached);
			case SPLIT -> new SplitIndex(path, cached);
		};
	}
}
