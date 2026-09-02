package se.l4.exofind.engine.index;

import java.util.function.ToLongFunction;

import org.apache.lucene.facet.range.LongRange;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.query.Facet;

/**
 * Counts the matches of a search into the buckets of one facet, reading the
 * doc values the field was written under {@link FieldNames#VALUES} - the same
 * values {@link FacetCounter} counts per value.
 *
 * A counter is created by the type of the field through
 * {@link se.l4.exofind.engine.index.types.FieldType#createRangeFacetCounter},
 * which is what decides what a bound means there - a timestamp bound is an
 * instant, a number bound a number of the field's width. The factory here
 * holds the counting itself, so a type only says which field to read and how a
 * bound maps onto the values as they were written.
 *
 * A counter answers a {@link FacetCount}, fed by the shared walk of the
 * facet's scope - see {@link FacetWalk}.
 */
public interface RangeFacetCounter {
	ErrorType EMPTY_RANGE = ErrorType
		.withCode("index:query:facet_range_empty")
		.withMessage("A bucket holds the values from `from` up to but not including `to`, so `to` has to be above `from`");

	/**
	 * Prepare to count one scope.
	 *
	 * @param scope
	 *   what the matches of the scope are and what the counts should be of
	 * @return
	 *   the count to feed through {@link FacetWalk}, never {@code null}
	 */
	FacetCount prepare(FacetMatches scope);

	/**
	 * Count a field written as sorted numeric doc values, mapping each bound
	 * into the encoding the values were written in.
	 *
	 * The encoding has to keep the order of the values, which is what lets a
	 * bucket over the values themselves become a bucket over their encoding -
	 * {@code from} stays inclusive and {@code to} exclusive on the other side.
	 *
	 * @param field
	 *   the Lucene field the values were written under
	 * @param ranges
	 *   the buckets to count into
	 * @param encode
	 *   how a bound reads as an encoded value
	 * @return
	 * @throws IndexException
	 *   if a bucket holds nothing because its bounds are inverted or touch
	 */
	static RangeFacetCounter overLongs(
		String field,
		ListIterable<Facet.Range> ranges,
		ToLongFunction<Object> encode
	) {
		var longRanges = new LongRange[ranges.size()];

		var i = 0;
		for(var range : ranges) {
			var from = range.from() == null
				? Long.MIN_VALUE
				: encode.applyAsLong(range.from());

			var openEnded = range.to() == null;
			var to = openEnded ? Long.MAX_VALUE : encode.applyAsLong(range.to());

			if(!openEnded && from >= to) {
				throw new IndexException(EMPTY_RANGE);
			}

			// Counts are read back by position, so the label only has to be unique
			longRanges[i] = new LongRange(Integer.toString(i), from, true, to, openEnded);
			i++;
		}

		return scope -> new RangeFacetCount(field, scope, ranges, longRanges);
	}
}
