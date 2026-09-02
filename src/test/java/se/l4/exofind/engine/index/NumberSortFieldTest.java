package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Pruning;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

/**
 * Tests for the ordering that reads values from doc values and skips through
 * points, and for the mirror of it a search walking backwards runs.
 */
public class NumberSortFieldTest {
	@Test
	public void testValuesComeFromTheDocValuesField() {
		var field = new NumberSortField(
			"price:_:sort",
			"price:_:filter",
			SortField.Type.DOUBLE,
			false
		);

		assertThat(field.getField(), is("price:_:sort"));
		assertThat(field.getPointsField(), is("price:_:filter"));
	}

	@Test
	public void testOnlyNumericTypesAreAccepted() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new NumberSortField("a:_:sort", "a:_:filter", SortField.Type.STRING, false)
		);
	}

	/**
	 * A page read backwards has to keep skipping, so the mirror carries the
	 * points field too.
	 */
	@Test
	public void testMirrorKeepsThePointsField() {
		var field = new NumberSortField(
			"price:_:sort",
			"price:_:filter",
			SortField.Type.DOUBLE,
			false
		);
		field.setMissingValue(Double.POSITIVE_INFINITY);

		var mirrored = SortKeys.reverse(new Sort(field)).getSort()[0];

		assertThat(mirrored, instanceOf(NumberSortField.class));

		var number = (NumberSortField) mirrored;
		assertThat(number.getField(), is("price:_:sort"));
		assertThat(number.getPointsField(), is("price:_:filter"));
		assertThat(number.getType(), is(SortField.Type.DOUBLE));
		assertThat(number.getReverse(), is(true));
		assertThat(number.getMissingValue(), is(Double.POSITIVE_INFINITY));
	}

	/**
	 * The point of the two names is that Lucene finds the points under one of
	 * them while reading values from the other. A comparator that found no
	 * points reads every match, and answers the same order while doing it, so
	 * nothing about a result would show the difference.
	 */
	@Test
	public void testTheComparatorSkipsThroughThePointsAndReadsTheDocValues()
		throws IOException
	{
		try(var directory = new ByteBuffersDirectory()) {
			try(var writer = new IndexWriter(directory, new IndexWriterConfig())) {
				for(var i = 0; i < 100; i++) {
					var document = new Document();
					document.add(new LongPoint("v:_:filter", i));
					document.add(new NumericDocValuesField("v:_:sort", i));
					writer.addDocument(document);
				}

				writer.forceMerge(1);
			}

			try(var reader = DirectoryReader.open(directory)) {
				var segment = reader.leaves().get(0);

				var field = new NumberSortField(
					"v:_:sort",
					"v:_:filter",
					SortField.Type.LONG,
					false
				);

				var comparator = field.getComparator(10, Pruning.GREATER_THAN_OR_EQUAL_TO);
				var leaf = comparator.getLeafComparator(segment);

				// An iterator at all means the points of the filter field were found
				assertThat(leaf.competitiveIterator(), is(notNullValue()));

				// The value read is the doc value, which only the sort field holds
				leaf.copy(0, 5);
				assertThat((Object) comparator.value(0), is((Object) 5L));

				var plain = new SortField("v:_:sort", SortField.Type.LONG, false);
				var plainLeaf = plain
					.getComparator(10, Pruning.GREATER_THAN_OR_EQUAL_TO)
					.getLeafComparator(segment);

				assertThat(plainLeaf.competitiveIterator(), is(nullValue()));
			}
		}
	}

	/**
	 * Descending by a field whose missing values sort last is the order that
	 * stops skipping as soon as a segment holds a Lucene document without a
	 * value: a missing value is then the most competitive one there is. The
	 * values of object fields are such documents, and telling the ordering
	 * which documents are documents of the index takes them out of the
	 * question.
	 */
	@Test
	public void testDescendingSkipsPastTheValuesOfObjectFields() throws IOException {
		try(var directory = new ByteBuffersDirectory()) {
			try(var writer = new IndexWriter(directory, new IndexWriterConfig())) {
				for(var i = 0; i < 1000; i++) {
					var value = new Document();
					NestedDocuments.mark(value, "variants");
					writer.addDocument(value);

					var document = new Document();
					document.add(new LongPoint("v:_:filter", i));
					document.add(new NumericDocValuesField("v:_:sort", i));
					writer.addDocument(document);
				}

				writer.forceMerge(1);
			}

			try(var reader = DirectoryReader.open(directory)) {
				var segment = reader.leaves().get(0);
				var everything = segment.reader().maxDoc();

				var ordering = new NumberSortField(
					"v:_:sort",
					"v:_:filter",
					SortField.Type.LONG,
					true
				);
				ordering.setMissingValue(Long.MAX_VALUE);

				// Half the Lucene documents hold no value, so Lucene stops here
				assertThat(costOnceFull(ordering, segment), is((long) everything));

				var documents = new QueryBitSetProducer(NestedDocuments.parentsQuery());
				var overDocuments = ordering.withDocuments(documents);

				assertThat(costOnceFull(overDocuments, segment), lessThan((long) everything));
			}
		}
	}

	/**
	 * Get what the competitive iterator of a descending sort costs once the
	 * queue is full and the collector has counted past its threshold. An
	 * ordering that cannot skip is left with the whole segment.
	 */
	private static long costOnceFull(NumberSortField ordering, LeafReaderContext segment)
		throws IOException
	{
		var comparator = ordering.getComparator(1, Pruning.GREATER_THAN_OR_EQUAL_TO);
		var leaf = comparator.getLeafComparator(segment);

		// The document holding 990, which leaves nine better ones behind it
		leaf.copy(0, 990 * 2 + 1);

		// Values come off the sort field whichever view of the segment is read
		assertThat((Object) comparator.value(0), is((Object) 990L));

		leaf.setBottom(0);
		leaf.setHitsThresholdReached();

		return leaf.competitiveIterator().cost();
	}

	@Test
	public void testMirrorEndsWithTheDocumentTieBreak() {
		var field = new NumberSortField(
			"price:_:sort",
			"price:_:filter",
			SortField.Type.LONG,
			true
		);

		var reversed = SortKeys.reverse(new Sort(field)).getSort();

		assertThat(reversed.length, is(2));
		assertThat(reversed[1].getType(), is(SortField.Type.DOC));
		assertThat(reversed[1].getReverse(), is(true));
	}
}
