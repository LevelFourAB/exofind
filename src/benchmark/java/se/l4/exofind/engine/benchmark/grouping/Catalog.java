package se.l4.exofind.engine.benchmark.grouping;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import se.l4.exofind.engine.benchmark.corpus.Words;

/**
 * A catalogue of products that each hold a few variants, generated rather than
 * stored.
 *
 * <p>The product at an ordinal is always the same product, on any machine and
 * in any order, so the shapes under comparison are built from the same
 * catalogue and a result compares with one from another day.
 *
 * <p>Values are drawn unevenly, the way the values of a real field are: a
 * handful of colours cover most of the variants and the rest are rare, so how
 * much of the catalogue a condition selects follows from which colour it names.
 * {@link #color(int)} names one by how common it is.
 *
 * <p>Instances are immutable and safe for concurrent use.
 */
public final class Catalog {
	/**
	 * The seed the catalogue is generated from. Changing it changes what the
	 * benchmarks measure, and results from before the change no longer compare.
	 */
	public static final long SEED = 4711L;

	/** How many colours a variant is drawn from. */
	public static final int COLORS = 20;

	/** How many sizes a variant is drawn from. */
	public static final int SIZES = 8;

	/** How many brands a product is drawn from. */
	public static final int BRANDS = 200;

	/** The largest number of variants a product holds. */
	public static final int MAX_VARIANTS = 12;

	/**
	 * How many low bits of a variant identifier hold its position within its
	 * product. Wide enough for {@link #MAX_VARIANTS}.
	 */
	private static final int VARIANT_BITS = 4;

	private static final int VOCABULARY = 20_000;

	private static final int CATEGORY_LEVELS = 10;

	private final Words words = Words.of(SEED, VOCABULARY);

	/**
	 * One variant of a product - what a shopper picks when they have already
	 * picked the product.
	 *
	 * @param id
	 *   identifier unique across the catalogue, {@link #productOf} reading the
	 *   product back out of it
	 * @param color
	 *   the one colour the variant comes in
	 * @param sizes
	 *   the sizes it comes in, at least one
	 * @param price
	 *   what it costs, which differs between the variants of a product
	 */
	public record Variant(
		long id,
		String color,
		List<String> sizes,
		double price,
		int stock
	) {
	}

	/**
	 * One product, holding the text and the refinements a search runs against
	 * and the variants it is bought as.
	 *
	 * @param id
	 *   identifier, which is also the ordinal the product was generated at
	 */
	public record Product(
		long id,
		String title,
		String description,
		String brand,
		String category,
		float rating,
		List<Variant> variants
	) {
	}

	/**
	 * Get the vocabulary the text of these products is drawn from, for choosing
	 * terms to search for.
	 */
	public Words words() {
		return words;
	}

	/**
	 * Get the product at an ordinal. Ordinals from {@code 0} up are what a
	 * catalogue of a given size holds.
	 */
	public Product product(long ordinal) {
		var random = new SplittableRandom(SEED * 31 + ordinal);

		var title = words.sentence(random, 3 + random.nextInt(4));
		var description = words.sentence(random, 20 + random.nextInt(40));
		var brand = "brand-" + skewed(random, BRANDS);

		var top = skewed(random, CATEGORY_LEVELS);
		var middle = random.nextInt(CATEGORY_LEVELS);
		var leaf = random.nextInt(CATEGORY_LEVELS);
		var category = "cat-" + top + "/cat-" + top + "-" + middle
			+ "/cat-" + top + "-" + middle + "-" + leaf;

		var rating = Math.round(random.nextDouble() * 50) / 10f;

		var count = 1 + skewed(random, MAX_VARIANTS);
		var variants = new ArrayList<Variant>(count);
		for(var i = 0; i < count; i++) {
			variants.add(variant(random, variantId(ordinal, i)));
		}

		return new Product(ordinal, title, description, brand, category, rating, variants);
	}

	/**
	 * Get the colour of a rank, {@code 0} being the colour most of the
	 * catalogue comes in and {@link #COLORS} - 1 the rarest.
	 */
	public static String color(int rank) {
		return "color-" + rank;
	}

	/**
	 * Get the identifier of the variant at a position within a product.
	 *
	 * @throws IllegalArgumentException
	 *   if {@code position} is at or beyond {@link #MAX_VARIANTS}
	 */
	public static long variantId(long product, int position) {
		if(position >= MAX_VARIANTS) {
			throw new IllegalArgumentException("Position " + position + " is beyond a product");
		}

		return (product << VARIANT_BITS) | position;
	}

	/**
	 * Get the product a variant identifier belongs to.
	 */
	public static long productOf(long variant) {
		return variant >>> VARIANT_BITS;
	}

	private Variant variant(RandomGenerator random, long id) {
		var color = color(skewed(random, COLORS));

		var count = 1 + random.nextInt(3);
		var sizes = new ArrayList<String>(count);
		for(var i = 0; i < count; i++) {
			var size = "size-" + random.nextInt(SIZES);
			if(!sizes.contains(size)) {
				sizes.add(size);
			}
		}

		var price = Math.round(random.nextDouble() * 100_000) / 100d;

		return new Variant(id, color, List.copyOf(sizes), price, skewed(random, 500));
	}

	/**
	 * Draw an integer below {@code bound}, the low ones more often than the
	 * high ones.
	 */
	private static int skewed(RandomGenerator random, int bound) {
		var value = (int) (-Math.log(1 - random.nextDouble()) * bound / 4);
		return Math.min(value, bound - 1);
	}
}
