package se.l4.exofind.engine.index;

import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Lists;

import se.l4.exofind.engine.query.SortKey;

/**
 * How a {@link SortKey} maps onto Lucene's {@code searchAfter}.
 *
 * A key holds one value per field of the compiled sort - tie breakers
 * included - together with the doc id that breaks ties between equal values,
 * so continuing from it is handing Lucene the position instead of counting
 * results up to it. The values cross process boundaries inside cursors, which
 * is why a {@link BytesRef} is carried as plain bytes and only turned back
 * when the key is used.
 *
 * Going backwards runs the mirror image of the sort: every field flipped,
 * with the doc tie break appended as an explicit field because Lucene's
 * implicit one only breaks ties in forward doc order. The hits then come back
 * walking away from the position, and the caller reverses them to read in
 * the order the request asked for.
 */
final class SortKeys {
	private SortKeys() {
	}

	/**
	 * The mirror image of an order, for walking backwards from a position.
	 *
	 * @param sort
	 *   the compiled sort, or {@code null} for relevance - best matches first
	 * @return
	 */
	public static Sort reverse(Sort sort) {
		var fields = Lists.mutable.<SortField>empty();

		if(sort == null) {
			// Lucene reads reverse on a score sort as worst matches first
			fields.add(new SortField(null, SortField.Type.SCORE, true));
		} else {
			for(var field : sort.getSort()) {
				fields.add(reverseOf(field));
			}
		}

		fields.add(new SortField(null, SortField.Type.DOC, true));

		return new Sort(fields.toArray(new SortField[0]));
	}

	private static SortField reverseOf(SortField field) {
		if(field instanceof NestedSortField nested) {
			return nested.mirrored();
		}

		if(field.getClass() != SortField.class) {
			throw new UnsupportedOperationException(
				"Sort field " + field + " can not be mirrored for walking backwards"
			);
		}

		var reversed = new SortField(field.getField(), field.getType(), !field.getReverse());

		/*
		 * The missing value is carried over as it is. It says how a document
		 * without a value compares, not where it ends up - so flipping the
		 * comparison already moves it to the other end of the results, the
		 * way a mirror has to.
		 */
		if(field.getMissingValue() != null) {
			reversed.setMissingValue(field.getMissingValue());
		}

		return reversed;
	}

	/**
	 * Turn a key into the position Lucene continues from.
	 *
	 * @param key
	 *   the key a previous result carried
	 * @param sort
	 *   the compiled sort of this search - the forward one even when walking
	 *   backwards, as it is what the key's values are checked against.
	 *   {@code null} for relevance
	 * @param backwards
	 *   whether the search runs the mirrored sort, which takes the doc tie
	 *   break as an explicit last value
	 * @return
	 * @throws IndexInvalidCursorException
	 *   when the key does not fit the sort - the wrong number of values, or a
	 *   value of the wrong kind for the field it would be compared against
	 */
	public static ScoreDoc toAfter(SortKey key, Sort sort, boolean backwards) {
		if(sort == null) {
			if(key.values().size() != 1 || !(key.values().get(0) instanceof Float score)) {
				throw new IndexInvalidCursorException();
			}

			if(!backwards) {
				return new ScoreDoc(key.doc(), score);
			}

			return new FieldDoc(key.doc(), Float.NaN, new Object[] { score, key.doc() });
		}

		var sortFields = sort.getSort();
		if(key.values().size() != sortFields.length) {
			throw new IndexInvalidCursorException();
		}

		var fields = new Object[sortFields.length + (backwards ? 1 : 0)];
		for(var i = 0; i < sortFields.length; i++) {
			fields[i] = toLuceneValue(key.values().get(i), sortFields[i]);
		}

		if(backwards) {
			fields[sortFields.length] = key.doc();
		}

		return new FieldDoc(key.doc(), Float.NaN, fields);
	}

	/**
	 * Check a value against the field it would be compared against, and turn
	 * carried bytes back into the {@link BytesRef} Lucene compares with.
	 */
	private static Object toLuceneValue(Object value, SortField field) {
		return switch(field.getType()) {
			case SCORE, FLOAT -> {
				if(!(value instanceof Float)) {
					throw new IndexInvalidCursorException();
				}
				yield value;
			}

			case DOC, INT -> {
				if(!(value instanceof Integer)) {
					throw new IndexInvalidCursorException();
				}
				yield value;
			}

			case LONG -> {
				if(!(value instanceof Long)) {
					throw new IndexInvalidCursorException();
				}
				yield value;
			}

			case DOUBLE -> {
				if(!(value instanceof Double)) {
					throw new IndexInvalidCursorException();
				}
				yield value;
			}

			case STRING, STRING_VAL -> {
				// null is a document without a value, which is a position too
				if(value == null) {
					yield null;
				}

				if(!(value instanceof byte[] bytes)) {
					throw new IndexInvalidCursorException();
				}

				yield new BytesRef(bytes);
			}

			default -> value;
		};
	}

	/**
	 * The key of a hit, from the position Lucene reported it at.
	 *
	 * @param scoreDoc
	 *   the hit as Lucene returned it
	 * @param backwards
	 *   whether the search ran the mirrored sort, whose last value is the doc
	 *   tie break rather than part of the order asked for
	 * @return
	 */
	public static SortKey keyOf(ScoreDoc scoreDoc, boolean backwards) {
		if(scoreDoc instanceof FieldDoc fieldDoc) {
			var count = backwards ? fieldDoc.fields.length - 1 : fieldDoc.fields.length;

			var values = Lists.mutable.empty();
			for(var i = 0; i < count; i++) {
				var value = fieldDoc.fields[i];
				values.add(
					value instanceof BytesRef bytes
						? BytesRef.deepCopyOf(bytes).bytes
						: value
				);
			}

			return new SortKey(values.toImmutable(), scoreDoc.doc);
		}

		return new SortKey(Lists.immutable.of((Object) scoreDoc.score), scoreDoc.doc);
	}
}
