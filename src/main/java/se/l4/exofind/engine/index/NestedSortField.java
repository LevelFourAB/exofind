package se.l4.exofind.engine.index;

import org.apache.lucene.search.SortField;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.ToParentBlockJoinSortField;

/**
 * Ordering documents by one of the values they hold in an object field.
 *
 * Lucene's own block join sort field is what does the work; this one remembers
 * what it was built from so that {@link SortKeys#reverse} can mirror it for a
 * search walking backwards from a cursor. Which value stands for a document is
 * not part of the mirroring - a document ordered by its cheapest value is
 * ordered by that same value whichever way the page is read - so only the
 * comparison is flipped.
 */
final class NestedSortField extends ToParentBlockJoinSortField {
	private final boolean max;
	private final BitSetProducer documents;
	private final BitSetProducer values;

	/**
	 * @param field
	 *   the Lucene field the values were written under
	 * @param type
	 *   how the values compare
	 * @param reverse
	 *   whether documents are ordered from the highest value down
	 * @param max
	 *   whether the highest of a document's matching values stands for it
	 *   rather than the lowest
	 * @param documents
	 *   finds the documents of the index among the values
	 * @param values
	 *   the values a document may be ordered by, which are the ones the search
	 *   matched
	 */
	NestedSortField(
		String field,
		SortField.Type type,
		boolean reverse,
		boolean max,
		BitSetProducer documents,
		BitSetProducer values
	) {
		super(field, type, reverse, max, documents, values);

		this.max = max;
		this.documents = documents;
		this.values = values;
	}

	/**
	 * Get this ordering with its comparison flipped, for walking backwards from
	 * a position.
	 *
	 * @return
	 */
	NestedSortField mirrored() {
		var mirror = new NestedSortField(
			getField(),
			getType(),
			!getReverse(),
			max,
			documents,
			values
		);

		if(getMissingValue() != null) {
			mirror.setMissingValue(getMissingValue());
		}

		return mirror;
	}
}
