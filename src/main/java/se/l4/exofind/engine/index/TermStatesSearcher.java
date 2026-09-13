package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.concurrent.Executor;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;

/**
 * Searcher that looks a term up through the {@link TermStatesCache} of the
 * node, so that a term named by more than one query is seeked once per
 * reader.
 *
 * <p>Compound queries create the weights of their clauses through the
 * searcher, so every {@link TermQuery} of a request arrives here, however
 * deep it sits. A term query that was given its states by whoever built it
 * is left alone.
 */
final class TermStatesSearcher extends IndexSearcher {
	private final TermStatesCache termStates;

	TermStatesSearcher(IndexReader reader, Executor executor, TermStatesCache termStates) {
		super(reader, executor);

		this.termStates = termStates;
	}

	@Override
	public Weight createWeight(Query query, ScoreMode scoreMode, float boost)
		throws IOException
	{
		if(query instanceof TermQuery termQuery && termQuery.getTermStates() == null) {
			var term = termQuery.getTerm();
			return super.createWeight(
				new TermQuery(term, termStates.get(this, term)),
				scoreMode,
				boost
			);
		}

		return super.createWeight(query, scoreMode, boost);
	}
}
