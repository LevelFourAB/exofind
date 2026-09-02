package se.l4.exofind.engine.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.function.Consumer;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.junit.jupiter.api.Test;

/**
 * The columns have to say exactly what the doc values say, document by
 * document, in both layouts Lucene writes a field in.
 */
public class FacetColumnsTest {
	private static final String FIELD = "f";

	@Test
	public void testMultiValuedOrdsLaidEndToEnd() throws IOException {
		withSegment(
			writer -> {
				add(writer, strings("b", "a"));
				add(writer, strings());
				add(writer, strings("c"));
				add(writer, strings("a", "c"));
			},
			reader -> {
				var column = assertInstanceOf(
					FacetColumns.Ords.Multi.class,
					FacetColumns.ords(reader, FIELD)
				);

				// Ordinals follow term order: a=0, b=1, c=2
				assertArrayEquals(new int[] { 0, 2, 2, 3, 5 }, column.starts());
				assertArrayEquals(new int[] { 0, 1, 2, 0, 2 }, column.ords());
				assertEquals(3, column.docCount());
			}
		);
	}

	@Test
	public void testSingleValuedOrdsMarkMissingDocuments() throws IOException {
		withSegment(
			writer -> {
				add(writer, strings("y"));
				add(writer, strings());
				add(writer, strings("x"));
			},
			reader -> {
				var column = assertInstanceOf(
					FacetColumns.Ords.Single.class,
					FacetColumns.ords(reader, FIELD)
				);

				assertArrayEquals(new int[] { 1, FacetColumns.Ords.Single.NONE, 0 }, column.ord());
				assertEquals(2, column.docCount());
			}
		);
	}

	@Test
	public void testMultiValuedLongsKeepRepeatsAndOrder() throws IOException {
		withSegment(
			writer -> {
				add(writer, longs(7, 5, 5));
				add(writer, longs());
				add(writer, longs(1));
			},
			reader -> {
				var column = assertInstanceOf(
					FacetColumns.Longs.Multi.class,
					FacetColumns.longs(reader, FIELD)
				);

				assertArrayEquals(new int[] { 0, 3, 3, 4 }, column.starts());
				assertArrayEquals(new long[] { 5, 5, 7, 1 }, column.values());
			}
		);
	}

	@Test
	public void testSingleValuedLongsSayWhichDocumentsHoldOne() throws IOException {
		withSegment(
			writer -> {
				add(writer, longs(3));
				add(writer, longs());
				add(writer, longs(9));
			},
			reader -> {
				var column = assertInstanceOf(
					FacetColumns.Longs.Single.class,
					FacetColumns.longs(reader, FIELD)
				);

				assertEquals(3, column.value()[0]);
				assertEquals(9, column.value()[2]);
				assertEquals(2, column.docCount());
				assertTrue(column.has(0));
				assertFalse(column.has(1));
				assertTrue(column.has(2));
			}
		);
	}

	@Test
	public void testSingleValuedLongsHeldByEveryDocumentKeepNoBitset() throws IOException {
		withSegment(
			writer -> {
				add(writer, longs(3));
				add(writer, longs(9));
			},
			reader -> {
				var column = assertInstanceOf(
					FacetColumns.Longs.Single.class,
					FacetColumns.longs(reader, FIELD)
				);

				assertNull(column.present());
				assertArrayEquals(new long[] { 3, 9 }, column.value());
			}
		);
	}

	@Test
	public void testSpansReadBothLayoutsTheSameWay() throws IOException {
		withSegment(
			writer -> {
				add(writer, strings("y"));
				add(writer, strings());
				add(writer, strings("x"));
			},
			reader -> {
				var spans = new FacetColumns.OrdSpans(FacetColumns.ords(reader, FIELD));
				assertEquals(1, spans.to(0) - spans.from(0));
				assertEquals(1, spans.values[spans.from(0)]);
				assertEquals(0, spans.to(1) - spans.from(1));
				assertEquals(0, spans.values[spans.from(2)]);
			}
		);

		withSegment(
			writer -> {
				add(writer, strings("y", "x"));
				add(writer, strings());
			},
			reader -> {
				var spans = new FacetColumns.OrdSpans(FacetColumns.ords(reader, FIELD));
				assertEquals(0, spans.from(0));
				assertEquals(2, spans.to(0));
				assertEquals(0, spans.to(1) - spans.from(1));
			}
		);
	}

	@Test
	public void testPostingsCountAValueAmongTheMatches() throws IOException {
		withSegment(
			writer -> {
				add(writer, strings("b", "a"));
				add(writer, strings());
				add(writer, strings("c"));
				add(writer, strings("a", "c"));
			},
			reader -> {
				var postings = FacetColumns.ordPostings(
					FacetColumns.ords(reader, FIELD),
					3,
					reader.maxDoc()
				);

				// Four documents fit one word, and no value is held by more than two
				assertNull(postings.dense()[0]);
				assertArrayEquals(new int[] { 0, 2, 3, 5 }, postings.starts());
				assertArrayEquals(new int[] { 0, 3, 0, 2, 3 }, postings.docs());

				var matches = new FixedBitSet(reader.maxDoc());
				matches.set(0);
				matches.set(2);
				assertEquals(1, postings.count(0, matches));
				assertEquals(1, postings.count(1, matches));
				assertEquals(1, postings.count(2, matches));

				matches.set(3);
				assertEquals(2, postings.count(0, matches));
				assertEquals(2, postings.count(2, matches));
			}
		);
	}

	@Test
	public void testAValueHeldByManyDocumentsGetsABitmap() throws IOException {
		withSegment(
			writer -> {
				for(var i = 0; i < 200; i++) {
					add(writer, strings(i % 100 == 0 ? "rare" : "common"));
				}
			},
			reader -> {
				var postings = FacetColumns.ordPostings(
					FacetColumns.ords(reader, FIELD),
					2,
					reader.maxDoc()
				);

				// common=0, rare=1: 200 documents are four words, 198 is above twice that
				assertEquals(198, postings.dense()[0].cardinality());
				assertNull(postings.dense()[1]);
				assertArrayEquals(new int[] { 0, 100 }, postings.docs());

				var matches = new FixedBitSet(reader.maxDoc());
				matches.set(0, 150);
				assertEquals(148, postings.count(0, matches));
				assertEquals(2, postings.count(1, matches));
			}
		);
	}

	@Test
	public void testSortedNumbersMakeARangeARun() throws IOException {
		withSegment(
			writer -> {
				add(writer, longs(7, 5, 5));
				add(writer, longs());
				add(writer, longs(1));
				add(writer, longs(5));
			},
			reader -> {
				var postings = FacetColumns.longPostings(
					FacetColumns.longs(reader, FIELD),
					reader.maxDoc()
				);

				assertFalse(postings.single());
				assertArrayEquals(new long[] { 1, 5, 5, 5, 7 }, postings.values());
				assertArrayEquals(new int[] { 2, 0, 0, 3, 0 }, postings.docs());

				assertEquals(1, postings.from(5));
				assertEquals(4, postings.to(5));
				assertEquals(0, postings.from(0));
				assertEquals(5, postings.to(100));
				assertEquals(4, postings.from(6));
			}
		);
	}

	private interface Reading {
		void read(LeafReader reader) throws IOException;
	}

	private static void withSegment(Consumer<IndexWriter> writing, Reading reading)
		throws IOException
	{
		try(var directory = new ByteBuffersDirectory()) {
			try(var writer = new IndexWriter(directory, new IndexWriterConfig())) {
				writing.accept(writer);
				writer.forceMerge(1);
			}

			try(var reader = DirectoryReader.open(directory)) {
				assertEquals(1, reader.leaves().size());
				reading.read(reader.leaves().get(0).reader());
			}
		}
	}

	private static void add(IndexWriter writer, Document document) {
		try {
			writer.addDocument(document);
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static Document strings(String... values) {
		var document = new Document();
		for(var value : values) {
			document.add(new SortedSetDocValuesField(FIELD, new BytesRef(value)));
		}
		return document;
	}

	private static Document longs(long... values) {
		var document = new Document();
		for(var value : values) {
			document.add(new SortedNumericDocValuesField(FIELD, value));
		}
		return document;
	}
}
