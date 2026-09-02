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
