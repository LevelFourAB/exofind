package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * A catalogue laid out in one shape, opened for the length of a trial, with the
 * asks a benchmark puts to it built up front.
 *
 * <p>The index is built once per layout and size and read in place, so what a
 * benchmark measures is the search rather than the filling of the index. Nothing
 * here writes to it.
 */
@State(Scope.Benchmark)
public class LoadedShape {
	/**
	 * The layout to search.
	 */
	@Param({ "NESTED", "GROUPED", "COLLAPSED", "BLOCKED", "ROLLED_UP", "SPLIT" })
	public Shape shape;

	/**
	 * How many products the catalogue holds. The number of variants follows
	 * from it, at around three and a half each.
	 */
	@Param({ "100000" })
	public int size;

	/**
	 * How much of the catalogue the colour a search narrows by covers -
	 * {@code wide} being a colour around one variant in ten comes in and
	 * {@code narrow} one around one in sixty does.
	 */
	@Param({ "wide" })
	public String selectivity;

	/**
	 * How many results a page holds.
	 */
	@Param({ "10" })
	public int limit;

	/**
	 * Whether the searcher keeps what a narrowing clause matched, as a node
	 * does. Turning it off measures a condition nobody has asked before, which
	 * is what the first shopper to tick a refinement pays.
	 */
	@Param({ "on" })
	public String cache;

	public ShapeIndex index;

	/** A colour, and nothing else. */
	public Ask filtered;

	/** A colour and a price, which have to hold inside one variant. */
	public Ask correlated;

	/** Words from a search box, and nothing about variants. */
	public Ask searched;

	/** Words from a search box together with a colour. */
	public Ask searchedAndFiltered;

	@Setup(Level.Trial)
	public void open() throws IOException {
		index = Shapes.open(shape, size, cache.equals("on"));

		var catalog = new Catalog();
		var color = Catalog.color(switch(selectivity) {
			case "wide" -> 3;
			case "narrow" -> 12;
			default -> throw new IllegalArgumentException(
				"Unknown selectivity: " + selectivity + ", expected wide or narrow"
			);
		});

		/*
		 * A word held by roughly one product in eight - enough of the catalogue
		 * that ranking it is the work, rather than finding it.
		 */
		var term = catalog.words().byRank(30);

		filtered = Ask.everything().withLimit(limit).withColor(color);
		correlated = filtered.withMaxPrice(250d);
		searched = Ask.everything().withLimit(limit).withText(term);
		searchedAndFiltered = searched.withColor(color);
	}

	@TearDown(Level.Trial)
	public void close() throws IOException {
		index.close();
	}
}
