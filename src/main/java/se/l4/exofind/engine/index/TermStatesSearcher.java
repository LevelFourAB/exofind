package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.concurrent.Executor;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Searcher that remembers where a term sits in the reader it was opened over,
 * so that a term named by more than one query is looked up once.
 *
 * <p>Turning a {@link TermQuery} into a weight needs the term's place in the
 * term dictionary of every segment, and its document frequency for scoring.
 * Lucene collects both into a {@link TermStates} by seeking the term in each
 * segment, and does so again for every query that names the term. A text
 * search builds one term query per word per field, a field is usually
 * searched in several ways at once, and the requests that follow ask for the
 * same words again, so the same seek is paid over and over.
 *
 * <p>What the seek finds depends only on the reader and the term, and a
 * searcher is opened over one reader and never outlives it, so the states of
 * a term can be kept for the life of the searcher. A built
 * {@link TermStates} is not written to afterwards and Lucene already shares
 * one between the weights of a single query, so several searches may read one
 * entry at the same time.
 *
 * <p>Entries are always built with statistics, even for a weight that does
 * not score. A {@link TermStates} built without them refuses to answer
 * {@code docFreq()}, and one cached entry answers every later query naming
 * the term, scoring or not.
 */
final class TermStatesSearcher extends IndexSearcher {
	/**
	 * How many terms one searcher remembers. The bound is what keeps a search
	 * over an unusual number of distinct words - a long query, or a run of
	 * them - from holding memory until the next reopen, rather than something
	 * a normal load is expected to reach: the words of the requests between
	 * two commits number in the hundreds. An entry holds the term and one
	 * position per segment, a few hundred bytes, so the whole bound is a few
	 * megabytes and it is released with the searcher.
	 */
	private static final int MAX_TERMS = 10_000;

	private final Cache<Term, TermStates> termStates;

	TermStatesSearcher(IndexReader reader, Executor executor) {
		super(reader, executor);

		this.termStates = Caffeine.newBuilder()
			.maximumSize(MAX_TERMS)
			.build();
	}

	@Override
	public Weight createWeight(Query query, ScoreMode scoreMode, float boost)
		throws IOException
	{
		/*
		 * Compound queries create the weights of their clauses through the
		 * searcher, so every term query of a request arrives here, however
		 * deep it sits. A term query that was given its states by whoever
		 * built it is left alone.
		 */
		if(query instanceof TermQuery termQuery && termQuery.getTermStates() == null) {
			var term = termQuery.getTerm();
			return super.createWeight(
				new TermQuery(term, statesOf(term)),
				scoreMode,
				boost
			);
		}

		return super.createWeight(query, scoreMode, boost);
	}

	/**
	 * Get where a term sits in this searcher's reader, seeking it out on the
	 * first query that names it.
	 *
	 * <p>The seek runs outside the cache. Two searches that want the same
	 * unseen term therefore do it twice and one of the two results is thrown
	 * away, which costs no more than the lookup they would each have done
	 * without a cache at all.
	 */
	private TermStates statesOf(Term term)
		throws IOException
	{
		var cached = termStates.getIfPresent(term);
		if(cached != null) {
			return cached;
		}

		var built = TermStates.build(this, term, true);
		termStates.put(term, built);
		return built;
	}
}
