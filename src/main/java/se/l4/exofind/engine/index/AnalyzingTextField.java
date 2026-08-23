package se.l4.exofind.engine.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;

/**
 * Analyzed text field carrying its own analyzer, written in one of three
 * shapes: bare, or with the offsets highlighting reads - in term vectors, or
 * in the postings themselves - per the layout of the index.
 */
public class AnalyzingTextField extends Field {
	/**
	 * What a value of the field is written with, beyond its terms.
	 */
	public enum Shape {
		/**
		 * Terms with positions, nothing for highlighting to read.
		 */
		PLAIN,
		/**
		 * Term vectors per document carrying the offsets, with the positions
		 * and payloads that were always written beside them.
		 */
		HIGHLIGHTABLE_TERM_VECTORS,
		/**
		 * Offsets in the postings of the field itself, and no term vectors.
		 */
		HIGHLIGHTABLE_POSTINGS
	}

	private static final FieldType TYPE_NORMAL;
	private static final FieldType TYPE_HIGHLIGHTABLE;
	private static final FieldType TYPE_HIGHLIGHTABLE_POSTINGS;

	static {
		TYPE_NORMAL = new FieldType();
		TYPE_NORMAL.setStored(false);
		TYPE_NORMAL.setTokenized(true);
		TYPE_NORMAL.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);

		TYPE_HIGHLIGHTABLE = new FieldType(TYPE_NORMAL);
		TYPE_HIGHLIGHTABLE.setStoreTermVectors(true);
		TYPE_HIGHLIGHTABLE.setStoreTermVectorOffsets(true);
		TYPE_HIGHLIGHTABLE.setStoreTermVectorPositions(true);
		TYPE_HIGHLIGHTABLE.setStoreTermVectorPayloads(true);

		TYPE_HIGHLIGHTABLE_POSTINGS = new FieldType(TYPE_NORMAL);
		TYPE_HIGHLIGHTABLE_POSTINGS.setIndexOptions(
			IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS
		);
	}

	private final Analyzer analyzer;

	public AnalyzingTextField(
		String name,
		CharSequence value,
		Shape shape,
		Analyzer analyzer
	) {
		super(
			name,
			value,
			switch(shape) {
				case PLAIN -> TYPE_NORMAL;
				case HIGHLIGHTABLE_TERM_VECTORS -> TYPE_HIGHLIGHTABLE;
				case HIGHLIGHTABLE_POSTINGS -> TYPE_HIGHLIGHTABLE_POSTINGS;
			}
		);
		this.analyzer = analyzer;
	}

	@Override
	public TokenStream tokenStream(Analyzer analyzer, TokenStream reuse) {
		return super.tokenStream(this.analyzer, reuse);
	}
}
