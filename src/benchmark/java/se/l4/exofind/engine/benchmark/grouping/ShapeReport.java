package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import se.l4.exofind.engine.benchmark.engine.BenchmarkIndexes;

/**
 * Prints what each layout costs to keep and whether the layouts agree on what
 * answers a search.
 *
 * <p>Timings on their own would say the flattest layout wins. This is the other
 * half of the comparison: how large each index is, how many Lucene documents it
 * holds, how long it took to fill, and - against the layout Exofind uses today
 * as the answer - how many products each of the others brings back that should
 * not be there and how many it misses.
 *
 * <pre>
 * java -cp ... se.l4.exofind.engine.benchmark.grouping.ShapeReport [products]
 * </pre>
 */
public final class ShapeReport {
	private ShapeReport() {
	}

	public static void main(String[] args) throws IOException {
		var size = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
		var catalog = new Catalog();

		var built = new LinkedHashMap<Shape, Long>();
		for(var shape : Shape.values()) {
			if(Files.isDirectory(Shapes.directory(shape, size))) {
				built.put(shape, -1L);
			} else {
				var started = System.nanoTime();
				Shapes.template(shape, size);
				built.put(shape, System.nanoTime() - started);
			}
		}

		var opened = new LinkedHashMap<Shape, ShapeIndex>();
		try {
			for(var shape : Shape.values()) {
				opened.put(shape, Shapes.open(shape, size));
			}

			System.out.println("Catalogue of " + size + " products, "
				+ variants(catalog, size) + " variants");
			System.out.println();

			cost(opened, built);
			System.out.println();

			answers(opened, catalog);
		} finally {
			for(var index : opened.values()) {
				index.close();
			}
		}

		System.out.println();
		changes(size, catalog, Math.min(size, 20_000));
	}

	/**
	 * Time changing the price of one variant, over and over, in each layout.
	 *
	 * <p>Each layout is copied first and the copy thrown away after, so the
	 * indexes the searches ran against are the ones they were built as. What is
	 * timed is buffering the change and the commit that makes it searchable,
	 * which is what a write to the engine has to do.
	 */
	private static void changes(int size, Catalog catalog, int changes) throws IOException {
		System.out.println("Changing the price of one variant, " + changes + " times");
		System.out.printf("%-12s %14s %14s %12s%n",
			"shape", "per change", "documents", "grown by");

		for(var shape : Shape.values()) {
			var copy = Path.of("target", "benchmark-work", "changes-" + shape);
			BenchmarkIndexes.delete(copy);
			Layouts.copy(Shapes.template(shape, size), copy);

			var before = Layouts.bytes(copy);
			var started = System.nanoTime();

			try(var writer = ShapeWriter.open(shape, copy)) {
				for(var change = 0; change < changes; change++) {
					/*
					 * Spread across the catalogue rather than run in order, so
					 * that the products being rewritten are not the ones still
					 * in the writer's buffer.
					 */
					writer.change(catalog.product((change * 7919L) % size));
				}

				writer.commit();
			}

			var taken = System.nanoTime() - started;
			var after = Layouts.bytes(copy);

			System.out.printf("%-12s %11.1f us %14s %10.1f MB%n",
				shape,
				taken / 1000d / changes,
				rewritten(shape, catalog, size, changes),
				(after - before) / (1024d * 1024d)
			);

			BenchmarkIndexes.delete(copy);
		}
	}

	/**
	 * How many Lucene documents the changes forced a layout to write.
	 */
	private static String rewritten(Shape shape, Catalog catalog, int size, int changes) {
		var total = 0L;
		for(var change = 0; change < changes; change++) {
			var product = catalog.product((change * 7919L) % size);
			total += switch(shape) {
				case NESTED -> product.variants().size() + 1;
				case BLOCKED -> product.variants().size();
				case GROUPED, COLLAPSED, ROLLED_UP, SPLIT -> 1;
			};
		}

		return Long.toString(total);
	}

	private static void cost(
		Map<Shape, ShapeIndex> opened,
		Map<Shape, Long> built
	) throws IOException {
		System.out.printf("%-12s %12s %10s %12s %12s%n",
			"shape", "documents", "segments", "on disk", "filled in");

		for(var entry : opened.entrySet()) {
			var stats = entry.getValue().stats();
			var time = built.get(entry.getKey());

			System.out.printf("%-12s %12d %10d %10.1f MB %12s%n",
				entry.getKey(),
				stats.documents(),
				stats.segments(),
				stats.bytes() / (1024d * 1024d),
				time < 0 ? "(kept)" : String.format("%.1f s", time / 1e9)
			);
		}
	}

	private static void answers(Map<Shape, ShapeIndex> opened, Catalog catalog)
		throws IOException
	{
		var asks = new LinkedHashMap<String, Ask>();
		asks.put("colour", Ask.everything().withColor(Catalog.color(3)));
		asks.put("colour + price", Ask.everything().withColor(Catalog.color(3))
			.withMaxPrice(250d));
		asks.put("text", Ask.everything().withText(catalog.words().byRank(30)));
		asks.put("text + colour", Ask.everything().withText(catalog.words().byRank(30))
			.withColor(Catalog.color(3)));
		asks.put("rarer colour", Ask.everything().withColor(Catalog.color(12)));

		System.out.println("Products found, against " + Shape.NESTED + " as the answer");
		System.out.printf("%-16s %-12s %10s %10s %10s%n",
			"ask", "shape", "found", "wrong", "missed");

		for(var ask : asks.entrySet()) {
			var expected = opened.get(Shape.NESTED).allProducts(ask.getValue());

			for(var entry : opened.entrySet()) {
				var found = entry.getValue().allProducts(ask.getValue());
				var wrong = missing(found, expected);
				var missed = missing(expected, found);

				System.out.printf("%-16s %-12s %10d %10d %10d%n",
					entry.getKey() == Shape.NESTED ? ask.getKey() : "",
					entry.getKey(),
					found.length,
					wrong,
					missed
				);
			}

			System.out.println();
		}

		System.out.println("Products per colour, for a text search");
		var counted = Ask.everything().withText(catalog.words().byRank(30)).withLimit(4);
		for(var entry : opened.entrySet()) {
			try {
				var counts = entry.getValue().colors(counted);
				var shown = new StringBuilder();
				for(var i = 0; i < counts.values().length; i++) {
					shown.append(i == 0 ? "" : ", ")
						.append(counts.values()[i])
						.append('=')
						.append(counts.counts()[i]);
				}

				System.out.printf("%-12s %s%n", entry.getKey(), shown);
			} catch(UnsupportedOperationException e) {
				System.out.printf("%-12s %s%n", entry.getKey(), "not answerable");
			}
		}

		System.out.println();
		System.out.println("The cheapest matching variant, for a colour");
		var cheapest = Ask.everything().withColor(Catalog.color(3)).withLimit(5);
		for(var entry : opened.entrySet()) {
			try {
				var hits = entry.getValue().cheapestFirst(cheapest);
				System.out.printf("%-12s %s%n",
					entry.getKey(),
					Arrays.toString(hits.ids()));
			} catch(UnsupportedOperationException e) {
				System.out.printf("%-12s %s%n", entry.getKey(), "not answerable");
			}
		}
	}

	/**
	 * Count how many of {@code found} are not in {@code expected}. Both are
	 * ascending.
	 */
	private static long missing(long[] found, long[] expected) {
		var missing = 0L;
		var at = 0;

		for(var id : found) {
			while(at < expected.length && expected[at] < id) {
				at++;
			}

			if(at >= expected.length || expected[at] != id) {
				missing++;
			}
		}

		return missing;
	}

	private static long variants(Catalog catalog, int size) {
		var total = 0L;
		for(var ordinal = 0; ordinal < size; ordinal++) {
			total += catalog.product(ordinal).variants().size();
		}

		return total;
	}
}
