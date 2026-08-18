package se.l4.exofind.engine.benchmark.corpus;

import static se.l4.exofind.engine.benchmark.corpus.Fields.bool;
import static se.l4.exofind.engine.benchmark.corpus.Fields.completed;
import static se.l4.exofind.engine.benchmark.corpus.Fields.faceted;
import static se.l4.exofind.engine.benchmark.corpus.Fields.filtered;
import static se.l4.exofind.engine.benchmark.corpus.Fields.float32;
import static se.l4.exofind.engine.benchmark.corpus.Fields.float64;
import static se.l4.exofind.engine.benchmark.corpus.Fields.geoPoint;
import static se.l4.exofind.engine.benchmark.corpus.Fields.hierarchy;
import static se.l4.exofind.engine.benchmark.corpus.Fields.int32;
import static se.l4.exofind.engine.benchmark.corpus.Fields.matched;
import static se.l4.exofind.engine.benchmark.corpus.Fields.object;
import static se.l4.exofind.engine.benchmark.corpus.Fields.sorted;
import static se.l4.exofind.engine.benchmark.corpus.Fields.string;
import static se.l4.exofind.engine.benchmark.corpus.Fields.timestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.GeoPoint;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;

/**
 * The corpora the benchmarks run against, each a shape an index is commonly
 * given.
 *
 * <ul>
 * <li>{@code minimal} - a key and two plain fields, indexing nothing that has
 * to be analyzed. What the rest is measured against to tell the cost of a
 * usage from the cost of holding a document at all.
 * <li>{@code catalogue} - the shape of a product or place search: matched and
 * completed text, filters, facets including a tree of categories, numbers to
 * sort and bucket by, a timestamp, a point on the earth and a list of nested
 * variants.
 * <li>{@code articles} - a few short fields and one long body, so that
 * analysis, term volume and highlighting dominate. Named with a locale
 * ({@code articles:sv}) to index the body under that locale's analysis chain.
 * </ul>
 *
 * <p>Every corpus is generated from one seed, so two runs of a benchmark index
 * and search the same documents.
 */
public final class Corpora {
	/**
	 * The seed every corpus is generated from. Changing it changes what the
	 * benchmarks measure, and results from before the change no longer compare.
	 */
	public static final long SEED = 4711L;

	private static final int VOCABULARY = 20_000;

	private static final long EPOCH = Instant.parse("2021-01-01T00:00:00Z").toEpochMilli();
	private static final long SPAN = 5L * 365 * 24 * 60 * 60 * 1000;

	private Corpora() {
	}

	/**
	 * Get a corpus by the name a benchmark parameter gives it - {@code
	 * minimal}, {@code catalogue}, {@code articles}, or {@code articles:<tag>}
	 * for a body indexed under a BCP 47 locale.
	 *
	 * @throws IllegalArgumentException
	 *   if no corpus goes by that name
	 */
	public static Corpus of(String name) {
		if(name.startsWith("articles:")) {
			return articles(name.substring("articles:".length()));
		}

		return switch(name) {
			case "minimal" -> minimal();
			case "catalogue" -> catalogue();
			case "articles" -> articles("en");
			default -> throw new IllegalArgumentException("Unknown corpus: " + name);
		};
	}

	public static Corpus minimal() {
		return new Minimal();
	}

	public static Corpus catalogue() {
		return new Catalogue();
	}

	/**
	 * Get the article corpus with its text indexed under the given BCP 47
	 * locale, which decides the analysis chain the body goes through.
	 */
	public static Corpus articles(String locale) {
		return new Articles(locale);
	}

	/**
	 * The generator for one document, seeded so that an ordinal always gives
	 * the same document.
	 */
	private static SplittableRandom random(long ordinal) {
		return new SplittableRandom(SEED * 31 + ordinal);
	}

	/**
	 * Draw an integer below {@code bound}, the low ones more often than the
	 * high ones, so that values are as unevenly common as the values of a real
	 * field are.
	 */
	private static int skewed(RandomGenerator random, int bound) {
		var value = (int) (-Math.log(1 - random.nextDouble()) * bound / 4);
		return Math.min(value, bound - 1);
	}

	private static String timestampAt(RandomGenerator random) {
		return Instant.ofEpochMilli(EPOCH + (long) (random.nextDouble() * SPAN)).toString();
	}

	private static final class Minimal implements Corpus {
		private static final Roles ROLES = new Roles(
			List.of(), null, "value", null, "number", null, null, null, null
		);

		private final Words words = Words.of(SEED, VOCABULARY);

		@Override
		public String name() {
			return "minimal";
		}

		@Override
		public Words words() {
			return words;
		}

		@Override
		public Roles roles() {
			return ROLES;
		}

		@Override
		public String keywordValue(int rank) {
			return words.byRank(rank);
		}

		@Override
		public IndexDef definition() {
			return IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).setRequired(true).build())
				.putFields("value", filtered(string()).build())
				.putFields("number", sorted(filtered(int32())).build())
				.build();
		}

		@Override
		public Document document(long ordinal) {
			var random = random(ordinal);

			return new Document(
				new Document.Value("id", Long.toString(ordinal)),
				new Document.Value("value", words.sample(random)),
				new Document.Value("number", random.nextInt(1_000_000))
			);
		}
	}

	private static final class Catalogue implements Corpus {
		private static final int BRANDS = 200;
		private static final int TAGS = 500;
		private static final int COLORS = 20;
		private static final int SIZES = 8;
		private static final int CATEGORY_LEVELS = 10;

		private static final Roles ROLES = new Roles(
			List.of("title", "description"),
			"titleAhead",
			"brand",
			"tags",
			"price",
			"added",
			"category",
			"location",
			"variants"
		);

		private final Words words = Words.of(SEED, VOCABULARY);

		@Override
		public String name() {
			return "catalogue";
		}

		@Override
		public Words words() {
			return words;
		}

		@Override
		public Roles roles() {
			return ROLES;
		}

		@Override
		public String keywordValue(int rank) {
			return "brand-" + rank;
		}

		@Override
		public IndexDef definition() {
			var variant = ObjectFieldTypeDef.newBuilder()
				.putFields("color", faceted(filtered(string())).build())
				.putFields("size", filtered(string()).setMultiple(true).build())
				.putFields("price", sorted(filtered(float64())).build())
				.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED);

			return IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).setRequired(true).build())
				.putFields("title", string(matched(3f)).build())
				.putFields("titleAhead", string(completed(3f)).build())
				.putFields("description", string(matched(1f)).build())
				.putFields("brand", faceted(sorted(filtered(string()))).build())
				.putFields("category", faceted(filtered(string(hierarchy()))).build())
				.putFields("tags", faceted(filtered(string())).setMultiple(true).build())
				.putFields("price", faceted(sorted(filtered(float64()))).build())
				.putFields("stock", sorted(filtered(int32())).build())
				.putFields("rating", sorted(float32()).build())
				.putFields("published", faceted(filtered(bool())).build())
				.putFields("added", faceted(sorted(filtered(timestamp()))).build())
				.putFields("location", sorted(filtered(geoPoint())).build())
				.putFields("variants", object(variant).setMultiple(true).build())
				.build();
		}

		@Override
		public Document document(long ordinal) {
			var random = random(ordinal);
			var values = new ArrayList<Document.Value>(24);

			var title = words.sentence(random, 3 + random.nextInt(4));

			values.add(new Document.Value("id", Long.toString(ordinal)));
			values.add(new Document.Value("title", title));
			values.add(new Document.Value("titleAhead", title));
			values.add(
				new Document.Value("description", words.sentence(random, 20 + random.nextInt(40)))
			);
			values.add(new Document.Value("brand", "brand-" + skewed(random, BRANDS)));
			values.add(new Document.Value("category", category(random)));

			var tags = random.nextInt(6);
			for(var i = 0; i < tags; i++) {
				values.add(new Document.Value("tags", "tag-" + skewed(random, TAGS)));
			}

			values.add(new Document.Value("price", Math.round(random.nextDouble() * 100_000) / 100d));
			values.add(new Document.Value("stock", skewed(random, 500)));
			values.add(new Document.Value("rating", Math.round(random.nextDouble() * 50) / 10f));
			values.add(new Document.Value("published", random.nextInt(4) > 0));
			values.add(new Document.Value("added", timestampAt(random)));
			values.add(
				new Document.Value(
					"location",
					new GeoPoint(random.nextDouble() * 180 - 90, random.nextDouble() * 360 - 180)
				)
			);

			var variants = 1 + random.nextInt(4);
			for(var i = 0; i < variants; i++) {
				values.add(new Document.Value("variants", variant(random)));
			}

			return new Document(values.toArray(Document.Value[]::new));
		}

		private static Document variant(RandomGenerator random) {
			var values = new ArrayList<Document.Value>(5);
			values.add(new Document.Value("color", "color-" + skewed(random, COLORS)));

			var sizes = 1 + random.nextInt(3);
			for(var i = 0; i < sizes; i++) {
				values.add(new Document.Value("size", "size-" + random.nextInt(SIZES)));
			}

			values.add(
				new Document.Value("price", Math.round(random.nextDouble() * 100_000) / 100d)
			);

			return new Document(values.toArray(Document.Value[]::new));
		}

		/**
		 * A path three levels deep, which is what a facet counting a level at a
		 * time has to walk.
		 */
		private static String category(RandomGenerator random) {
			var top = skewed(random, CATEGORY_LEVELS);
			var middle = random.nextInt(CATEGORY_LEVELS);
			var leaf = random.nextInt(CATEGORY_LEVELS);

			return "cat-" + top + "/cat-" + top + "-" + middle + "/cat-" + top + "-" + middle
				+ "-" + leaf;
		}
	}

	private static final class Articles implements Corpus {
		private static final int AUTHORS = 500;
		private static final int SECTIONS = 20;

		private static final Roles ROLES = new Roles(
			List.of("title", "body"), null, "section", null, null, "published", null, null,
			null
		);

		private final Words words = Words.of(SEED, VOCABULARY);
		private final String locale;

		Articles(String locale) {
			this.locale = locale;
		}

		@Override
		public String name() {
			return "articles:" + locale;
		}

		@Override
		public Words words() {
			return words;
		}

		@Override
		public Roles roles() {
			return ROLES;
		}

		@Override
		public String keywordValue(int rank) {
			return "section-" + rank;
		}

		@Override
		public IndexDef definition() {
			var locales = FieldDef.LocaleConfig.newBuilder().setDefaultLocale(locale);

			return IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).setRequired(true).build())
				.putFields("title", string(matched(3f)).setLocales(locales).build())
				.putFields("body", string(matched(1f)).setLocales(locales).build())
				.putFields("author", faceted(sorted(filtered(string()))).build())
				.putFields("section", faceted(filtered(string())).build())
				.putFields("published", sorted(filtered(timestamp())).build())
				.build();
		}

		@Override
		public Document document(long ordinal) {
			var random = random(ordinal);

			return new Document(
				new Document.Value("id", Long.toString(ordinal)),
				new Document.Value("title", words.sentence(random, 5 + random.nextInt(6))),
				new Document.Value("body", words.sentence(random, 80 + random.nextInt(170))),
				new Document.Value("author", "author-" + skewed(random, AUTHORS)),
				new Document.Value("section", "section-" + skewed(random, SECTIONS)),
				new Document.Value("published", timestampAt(random))
			);
		}
	}

	/**
	 * Generate the first {@code size} documents of a corpus, for a benchmark
	 * that has to hold them all before it starts timing.
	 */
	public static List<Document> documents(Corpus corpus, int size) {
		var documents = new ArrayList<Document>(size);
		for(var i = 0; i < size; i++) {
			documents.add(corpus.document(i));
		}

		return documents;
	}
}
