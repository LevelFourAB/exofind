package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import se.l4.exofind.engine.benchmark.JvmArgs;

/**
 * What each way of laying out products and their variants costs to search.
 *
 * <p>Every benchmark puts the same question to every layout, so a row of
 * results is one question answered five ways. A layout that cannot answer a
 * question at all fails rather than being left out, because a comparison that
 * silently skips what a layout cannot do reads as though it could.
 *
 * <p>{@code ShapeReport} prints what each layout costs to keep and how far its
 * answers are from the others, which is what these timings have to be read
 * beside - the fastest of them is also the one that answers a different
 * question.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = { JvmArgs.VECTOR, JvmArgs.NATIVE_ACCESS })
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Thread)
public class GroupingBenchmark {
	/**
	 * A page of products narrowed by one condition on a variant - the refinement
	 * a shopper ticks.
	 */
	@Benchmark
	public ShapeIndex.Hits filteredProducts(LoadedShape state) throws IOException {
		return state.index.products(state.filtered);
	}

	/**
	 * A page of products narrowed by two conditions that have to hold inside one
	 * variant, which is the whole reason a layout keeps variants apart.
	 */
	@Benchmark
	public ShapeIndex.Hits correlatedProducts(LoadedShape state) throws IOException {
		return state.index.products(state.correlated);
	}

	/**
	 * A page of products for words from a search box, saying nothing about
	 * variants - what every search that is not a refinement costs.
	 */
	@Benchmark
	public ShapeIndex.Hits searchedProducts(LoadedShape state) throws IOException {
		return state.index.products(state.searched);
	}

	/**
	 * A page of products for words from a search box with a refinement ticked.
	 */
	@Benchmark
	public ShapeIndex.Hits searchedAndFilteredProducts(LoadedShape state) throws IOException {
		return state.index.products(state.searchedAndFiltered);
	}

	/**
	 * How many products answer a search, exactly - the number a UI shows and the
	 * number pagination is built on.
	 */
	@Benchmark
	public long countProducts(LoadedShape state) throws IOException {
		return state.index.countProducts(state.correlated);
	}

	/**
	 * A page of products ordered by the cheapest of the variants that answered
	 * the search.
	 */
	@Benchmark
	public ShapeIndex.Hits cheapestFirst(LoadedShape state) throws IOException {
		return state.index.cheapestFirst(state.filtered);
	}

	/**
	 * How many products answering a search come in each colour, counted once per
	 * product.
	 */
	@Benchmark
	public ShapeIndex.Counts colorFacets(LoadedShape state) throws IOException {
		return state.index.colors(state.searched);
	}

	/**
	 * A page of variants rather than of products - the other half of the use
	 * case, where the shopper is picking between the variants themselves.
	 */
	@Benchmark
	public ShapeIndex.Hits filteredVariants(LoadedShape state) throws IOException {
		return state.index.variants(state.correlated);
	}
}
