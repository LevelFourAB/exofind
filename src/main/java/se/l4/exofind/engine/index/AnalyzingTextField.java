package se.l4.exofind.engine.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;

public class AnalyzingTextField extends Field {
	private static final FieldType TYPE_NORMAL;
	private static final FieldType TYPE_HIGHLIGHTABLE;

	static {
		TYPE_NORMAL = new FieldType();
		TYPE_NORMAL.setStored(false);
		TYPE_NORMAL.setTokenized(true);
		TYPE_NORMAL.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);

		TYPE_HIGHLIGHTABLE = new FieldType(TYPE_NORMAL);
		TYPE_HIGHLIGHTABLE.setStored(false);
		TYPE_HIGHLIGHTABLE.setTokenized(true);
		TYPE_HIGHLIGHTABLE.setStoreTermVectors(true);
		TYPE_HIGHLIGHTABLE.setStoreTermVectorOffsets(true);
		TYPE_HIGHLIGHTABLE.setStoreTermVectorPositions(true);
		TYPE_HIGHLIGHTABLE.setStoreTermVectorPayloads(true);
	}
	private final Analyzer analyzer;

	public AnalyzingTextField(
		String name,
		CharSequence value,
		boolean highlightable,
		Analyzer analyzer
	) {
		super(
			name,
			value,
			highlightable ? TYPE_HIGHLIGHTABLE : TYPE_NORMAL
		);
		this.analyzer = analyzer;
	}

	@Override
	public TokenStream tokenStream(Analyzer analyzer, TokenStream reuse) {
		return super.tokenStream(this.analyzer, reuse);
	}
}
