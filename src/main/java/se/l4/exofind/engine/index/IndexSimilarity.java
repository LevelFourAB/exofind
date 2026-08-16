package se.l4.exofind.engine.index;

import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.PerFieldSimilarityWrapper;
import org.apache.lucene.search.similarities.Similarity;

import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexSchema;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;

/**
 * How an index scores the text it matched, which is the stock scoring except
 * for how much the length of a value counts against it.
 *
 * A field whose values are names wants a long value ranked below a short one
 * holding the same words - the words left over are the difference between what
 * was asked for and something merely related to it - while a field holding
 * prose wants far less of that, and one holding a list of everything a
 * document is about wants none. Which of the three a field is, is the
 * definition's to say, and this is where that reaches Lucene.
 *
 * The length itself is in the norms of the field either way, written when the
 * value was indexed; what a definition chooses is only how much of it is read.
 * So a changed setting reorders results from the next search on, without
 * anything being reindexed - the same way a changed field weight does. The
 * writer scores through this as well, because norms are written by whatever
 * similarity the writer holds and the two have to agree about them.
 *
 * The schema is the live one of the index, mutated in place when the
 * definition changes, so a search always scores by the definition the index
 * currently holds.
 */
public class IndexSimilarity extends PerFieldSimilarityWrapper {
	/**
	 * How much a term turning up repeatedly in one value goes on counting,
	 * which is Lucene's own default. Named here only because the length
	 * setting has to be given alongside it.
	 */
	private static final float K1 = 1.2f;

	/**
	 * What the engine scores with when a field says nothing, which is Lucene's
	 * own default, and what {@code MODERATE} pins.
	 */
	private static final Similarity MODERATE = new BM25Similarity();

	private static final Similarity NONE = new BM25Similarity(K1, 0f);
	private static final Similarity STRONG = new BM25Similarity(K1, 1f);

	private final IndexSchema schema;

	public IndexSimilarity(IndexSchema schema) {
		this.schema = schema;
	}

	@Override
	public Similarity get(String field) {
		var usage = usageOf(field);
		if(usage == null || !usage.hasLengthNormalization()) {
			return MODERATE;
		}

		return switch(usage.getLengthNormalization()) {
			case LENGTH_NORMALIZATION_NONE -> NONE;
			case LENGTH_NORMALIZATION_STRONG -> STRONG;
			/*
			 * MODERATE, and anything a newer version wrote that this one has
			 * no scoring for - which a node only ever sees when the setting
			 * grew a value after the feature name was released, as a
			 * definition needing a name this build lacks never opens.
			 */
			default -> MODERATE;
		};
	}

	/**
	 * Get the text usage a Lucene field was written for, which is what says how
	 * its length counts.
	 *
	 * @param field
	 *   the name a value was written under
	 * @return
	 *   the usage, or {@code null} for a field that holds no analyzed text -
	 *   everything else the schema writes, and the fields Lucene keeps for
	 *   itself
	 */
	private StringFieldTypeDef.TextUsageConfig usageOf(String field) {
		var parsed = FieldNames.parse(field);
		if(parsed == null) {
			return null;
		}

		var schemaField = schema.getField(parsed.field());
		if(schemaField.isEmpty()
			|| schemaField.get().getDef().getType().getTypeCase() != FieldTypeDef.TypeCase.STRING) {
			return null;
		}

		var string = schemaField.get().getDef().getType().getString();

		if(FieldNames.MATCHING.equals(parsed.suffix()) && string.hasMatching()) {
			return string.getMatching();
		}

		if(FieldNames.AUTOCOMPLETE.equals(parsed.suffix()) && string.hasAutocomplete()) {
			return string.getAutocomplete();
		}

		return null;
	}
}
