package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.function.Function;
import java.util.function.LongFunction;

import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.LongValueFacetCounts;
import org.apache.lucene.facet.StringValueFacetCounts;
import org.apache.lucene.index.IndexReader;
import org.eclipse.collections.api.factory.Lists;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Counts the documents a search matched per value of one field, reading the
 * doc values the field was written under {@link FieldNames#VALUES}.
 *
 * A counter is created by the type of the field through
 * {@link se.l4.exofind.engine.index.types.FieldType#createFacetCounter}, which
 * is what decides how the raw doc values read back as values a caller
 * recognises - a boolean field counts {@code T} and {@code F} but answers
 * {@code true} and {@code false}. The two factories here hold the counting
 * itself, so a type only says which field to read and how to decode one
 * value.
 *
 * A field inside an object counts the same thing - how many documents hold each
 * value - but what was collected is the values of the object rather than the
 * documents holding them, so the counting goes through {@link NestedFacets} to
 * roll them up. Which of the two it is arrives with the matches rather than
 * with the field, so a type stays out of it.
 */
public interface FacetCounter {
	/**
	 * Count the given matches per value.
	 *
	 * @param reader
	 *   the reader of the index being searched
	 * @param matches
	 *   what to count, collected over the scope of the facet
	 * @param limit
	 *   how many values to bring back at most
	 * @param order
	 *   the order values come back in
	 * @return
	 *   the counts, never {@code null}
	 * @throws IOException
	 */
	SearchResult.Facet count(
		IndexReader reader,
		FacetMatches matches,
		int limit,
		Facet.Order order
	) throws IOException;

	/**
	 * Count a field written as sorted set doc values, decoding each counted
	 * term through the given function.
	 *
	 * @param field
	 *   the Lucene field the values were written under
	 * @param decode
	 *   how a counted term reads back as a value
	 * @return
	 */
	static FacetCounter overStrings(String field, Function<String, Object> decode) {
		return (reader, matches, limit, order) -> {
			if(!FacetStates.hasValues(reader, field)) {
				return new SearchResult.Facet(Lists.immutable.empty(), 0);
			}

			/*
			 * DOCUMENTS and VALUES both count each match as it comes - the
			 * modes only differ in what a match is - so both are left to
			 * Lucene's counting.
			 */
			switch(matches.mode()) {
				case ROLLED_UP -> {
					return NestedFacets.countStrings(matches, field, limit, order, decode);
				}
				case PARENTS_BY_VALUE -> {
					return NestedFacets.countParentStrings(matches, field, limit, order, decode);
				}
				default -> {
				}
			}

			var counts = new StringValueFacetCounts(
				FacetStates.of(reader, field),
				matches.hits()
			);

			/*
			 * All children come back in term order, which for these doc
			 * values is ascending by value.
			 */
			var result = order == Facet.Order.VALUE
				? counts.getAllChildren(field)
				: counts.getTopChildren(limit, field);

			return toFacet(result, limit, label -> decode.apply(label));
		};
	}

	/**
	 * Count a field written as sorted numeric doc values, decoding each
	 * counted number through the given function.
	 *
	 * @param field
	 *   the Lucene field the values were written under
	 * @param decode
	 *   how a counted number reads back as a value
	 * @return
	 */
	static FacetCounter overLongs(String field, LongFunction<Object> decode) {
		return (reader, matches, limit, order) -> {
			switch(matches.mode()) {
				case ROLLED_UP -> {
					return NestedFacets.countLongs(matches, field, limit, order, decode);
				}
				case PARENTS_BY_VALUE -> {
					return NestedFacets.countParentLongs(matches, field, limit, order, decode);
				}
				default -> {
				}
			}

			var counts = new LongValueFacetCounts(field, matches.hits());

			var result = order == Facet.Order.VALUE
				? counts.getAllChildrenSortByValue()
				: counts.getTopChildren(limit, field);

			return toFacet(result, limit, label -> decode.apply(Long.parseLong(label)));
		};
	}

	private static SearchResult.Facet toFacet(
		FacetResult result,
		int limit,
		Function<String, Object> decode
	) {
		if(result == null) {
			return new SearchResult.Facet(Lists.immutable.empty(), 0);
		}

		var values = Lists.mutable.<SearchResult.Facet.Value>empty();
		for(var entry : result.labelValues) {
			// Ordering by value reads every child, the limit still holds
			if(values.size() == limit) {
				break;
			}

			values.add(
				new SearchResult.Facet.Value(decode.apply(entry.label), entry.value.longValue())
			);
		}

		return new SearchResult.Facet(values.toImmutable(), result.childCount);
	}
}
