package se.l4.exofind.engine.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.uhighlight.FieldOffsetStrategy;
import org.apache.lucene.search.uhighlight.LengthGoalBreakIterator;
import org.apache.lucene.search.uhighlight.OffsetsEnum;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.PassageScorer;
import org.apache.lucene.search.uhighlight.PostingsOffsetStrategy;
import org.apache.lucene.search.uhighlight.UHComponents;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.ByteRunAutomaton;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.primitive.IntObjectMap;

import se.l4.exofind.engine.query.SearchRequest;

/**
 * Builds the highlighted fragments of the hits of one search.
 *
 * Matches are found by reading the offsets highlightable fields were indexed
 * with - in the field's own postings, or in term vectors, per the layout of
 * the index - and the text they are cut from is the stored copy the same
 * declaration forced. The field the query matched holds the offsets, its
 * {@code stored} sibling holds the text, and the offsets of one are offsets
 * into the other because both were written from the same value.
 *
 * The text is not read here. Stored fields arrive compressed in blocks, so a
 * document read for its fragments and read again for its values costs two
 * decompressions of the same bytes; the caller reads each hit once and hands
 * the documents over, and {@link #storedField(String)} is what says which
 * field has to be among them.
 */
class Highlighter {
	/**
	 * One field a search asked to highlight.
	 *
	 * @param field
	 *   the name the field goes by in the definition, which is the name the
	 *   fragments are keyed by
	 * @param luceneField
	 *   the Lucene field the matches are read from, resolved by
	 *   {@link QueryCompiler#highlightField} to the locale the search read
	 *   the field in
	 * @param options
	 *   how to build the fragments
	 */
	record Target(String field, String luceneField, SearchRequest.Highlight options) {
	}

	/**
	 * The Lucene field holding the text the fragments of a highlighted field
	 * are cut from.
	 *
	 * A highlightable field is never stored itself - the declaration that made
	 * it highlightable stored a sibling written from the same value, which is
	 * what the offsets in its term vectors point into.
	 *
	 * @param luceneField
	 *   the field the matches are read from, as
	 *   {@link QueryCompiler#highlightField} resolved it
	 * @return
	 *   the field its text is stored under
	 */
	static String storedField(String luceneField) {
		var parsed = FieldNames.parse(luceneField);
		return FieldNames.name(parsed.field(), parsed.locale(), FieldNames.STORED);
	}

	private final ListIterable<Target> targets;
	private final PinnedOffsetsHighlighter highlighter;

	/**
	 * The analyzer mirrors what the writer is configured with. It is never
	 * asked to analyze anything - offsets come from where the index keeps
	 * them - but the highlighter derives its defaults, such as the gap between
	 * the values of a multi-valued field, from it. One instance serves every
	 * search: an {@link org.apache.lucene.analysis.Analyzer} carries a
	 * {@code ThreadLocal} for reuse, so building one per search litters every
	 * searching thread's ThreadLocal map with entries to clean up.
	 */
	private static final StandardAnalyzer GAP_ANALYZER = new StandardAnalyzer();

	/**
	 * @param searcher
	 * @param targets
	 *   the fields to highlight
	 * @param documents
	 *   the stored fields of every document that will be highlighted, keyed by
	 *   Lucene id, each holding at least the {@link #storedField(String)} of
	 *   every target
	 * @param offsetsInPostings
	 *   whether the index keeps the offsets in the postings of the fields,
	 *   rather than in term vectors
	 */
	Highlighter(
		IndexSearcher searcher,
		ListIterable<Target> targets,
		IntObjectMap<org.apache.lucene.document.Document> documents,
		boolean offsetsInPostings
	) {
		this.targets = targets;

		var byLuceneField = targets.groupByUniqueKey(Target::luceneField);

		var builder = UnifiedHighlighter.builder(searcher, GAP_ANALYZER)
			/*
			 * A word that was matched while half typed sits in the index as a
			 * prefix or an automaton rather than as a term, and lighting the
			 * whole word up when `spr` matched `Spring` is the point of
			 * showing it. With offsets in term vectors the highlighter walks
			 * those itself, against the handful of terms each document holds.
			 * With offsets in postings its own handling walks away from the
			 * postings into re-analysis, so the automata are kept out of its
			 * hands and the terms they match are handed to it outright - see
			 * PinnedOffsetsHighlighter#getHighlightComponents.
			 */
			.withHandleMultiTermQuery(!offsetsInPostings)
			/*
			 * The compiler builds queries out of terms, never positions, so
			 * matching by weight has nothing extra to say here - and it is the
			 * one mode that does not lean purely on the vectors.
			 */
			.withWeightMatches(false)
			// A field the query matched nothing in answers with no fragments
			// rather than with the start of the text as a summary
			.withMaxNoHighlightPassages(0);

		this.highlighter = new PinnedOffsetsHighlighter(
			builder,
			byLuceneField,
			documents,
			offsetsInPostings
				? UnifiedHighlighter.OffsetSource.POSTINGS
				: UnifiedHighlighter.OffsetSource.TERM_VECTORS
		);
	}

	/**
	 * Highlight the given documents, in the order given.
	 *
	 * @param scoringQuery
	 *   the part of the search that ranks, compiled by
	 *   {@link QueryCompiler#compileScoring}
	 * @param docIds
	 *   Lucene ids of the documents to highlight
	 * @return
	 *   one map per document, keyed by the name a field has in the
	 *   definition. A field a document holds no match in has no entry
	 * @throws IOException
	 */
	ListIterable<ImmutableMap<String, ImmutableList<String>>> highlight(
		Query scoringQuery,
		int[] docIds
	) throws IOException {
		var fields = new String[targets.size()];
		var maxPassages = new int[targets.size()];
		targets.forEachWithIndex((target, i) -> {
			fields[i] = target.luceneField();
			maxPassages[i] = target.options().fragments();
		});

		Map<String, Object[]> byField;
		try {
			byField = highlighter.highlight(fields, scoringQuery, docIds, maxPassages);
		} catch(UncheckedIOException e) {
			// Expanding multi-term queries reads the index where no IOException fits
			throw e.getCause();
		}

		var results = Lists.mutable.<ImmutableMap<String, ImmutableList<String>>>empty();
		for(var doc = 0; doc < docIds.length; doc++) {
			var highlights = Maps.mutable.<String, ImmutableList<String>>empty();
			for(var target : targets) {
				var fragments = byField.get(target.luceneField())[doc];
				if(fragments instanceof List<?> list && !list.isEmpty()) {
					highlights.put(
						target.field(),
						Lists.immutable.ofAll(list).collect(String.class::cast)
					);
				}
			}

			results.add(highlights.toImmutable());
		}

		return results;
	}

	/**
	 * The {@link UnifiedHighlighter} pointed at what this index writes:
	 * offsets always from where the index's layout keeps them, text from the
	 * {@code stored} sibling of the field carrying them, fragments cut and
	 * wrapped per target.
	 */
	private static final class PinnedOffsetsHighlighter extends UnifiedHighlighter {
		/**
		 * What the values of a multi-valued field are joined by in the text
		 * passages are cut from.
		 */
		static final char VALUE_SEPARATOR = MULTIVAL_SEP_CHAR;

		private final MapIterable<String, Target> targets;
		private final IntObjectMap<org.apache.lucene.document.Document> documents;
		private final OffsetSource offsetSource;

		private PinnedOffsetsHighlighter(
			UnifiedHighlighter.Builder builder,
			MapIterable<String, Target> targets,
			IntObjectMap<org.apache.lucene.document.Document> documents,
			OffsetSource offsetSource
		) {
			super(builder);
			this.targets = targets;
			this.documents = documents;
			this.offsetSource = offsetSource;
		}

		private Target target(String field) {
			var target = targets.get(field);
			if(target == null) {
				throw new IllegalArgumentException("Not a field being highlighted: " + field);
			}

			return target;
		}

		@Override
		protected OffsetSource getOffsetSource(String field) {
			/*
			 * Pinned rather than sniffed from the field infos: a highlightable
			 * field always carries offsets where the index's layout says, and
			 * a segment that happens to hold no value for it must come out
			 * empty instead of falling back to re-analyzing text the index
			 * never analyzes this way.
			 */
			return offsetSource;
		}

		@Override
		protected OffsetSource getOptimizedOffsetSource(UHComponents components) {
			var optimized = super.getOptimizedOffsetSource(components);

			/*
			 * The stock optimization answers ANALYSIS for whatever it cannot
			 * read from the source alone - an automaton, a query type it does
			 * not recognize. Re-analysis is never right here: the analyzer
			 * this was built with is not the chain the field was indexed by,
			 * so it would highlight different terms than the search matched.
			 * What the source cannot answer comes out unhighlighted instead.
			 */
			return optimized == OffsetSource.ANALYSIS ? offsetSource : optimized;
		}

		@Override
		protected FieldOffsetStrategy getOffsetStrategy(
			OffsetSource offsetSource,
			UHComponents components
		) {
			/*
			 * The queries this highlighter is handed reduce to plain terms:
			 * weight matching is off, multi-term queries were expanded in
			 * getHighlightComponents, and the compiler builds nothing
			 * position-sensitive. The guards keep the stock per-document
			 * strategy for anything that stops being true.
			 */
			if(offsetSource == OffsetSource.POSTINGS
				&& !components.phraseHelper().hasPositionSensitivity()
				&& components.automata().length == 0
				&& !components.highlightFlags().contains(HighlightFlag.WEIGHT_MATCHES))
			{
				return new SeekOncePostingsOffsetStrategy(components);
			}

			return super.getOffsetStrategy(offsetSource, components);
		}

		@Override
		protected UHComponents getHighlightComponents(
			String field,
			Query query,
			Set<org.apache.lucene.index.Term> allTerms
		) {
			if(offsetSource == OffsetSource.POSTINGS) {
				allTerms = withExpandedQueries(field, query, allTerms);
			}

			return super.getHighlightComponents(field, query, allTerms);
		}

		/**
		 * How many index terms one multi-term query hands the highlighter,
		 * per segment. A page of hits holds at most tens of matched variants,
		 * so the cap sits far above what highlighting can use - it is there
		 * so a short prefix over a large index cannot make every page of
		 * results enumerate the dictionary. A variant past the cap goes
		 * unhighlighted.
		 */
		private static final int MAX_EXPANSIONS = 1024;

		/**
		 * The terms of the query, together with the index terms its
		 * multi-term queries match - each asked for the enumeration it runs
		 * on, so a word matched as a prefix or through its misreadings
		 * highlights exactly what the search matched. Expanding here is what
		 * lets the offsets stay read from postings: handed concrete terms,
		 * the highlighter has nothing left it would re-analyze for.
		 */
		private Set<org.apache.lucene.index.Term> withExpandedQueries(
			String field,
			Query query,
			Set<org.apache.lucene.index.Term> allTerms
		) {
			var multiTermQueries = new ArrayList<MultiTermQuery>();
			query.visit(new QueryVisitor() {
				@Override
				public boolean acceptField(String queried) {
					return field.equals(queried);
				}

				@Override
				public void visitLeaf(Query leaf) {
					if(leaf instanceof MultiTermQuery mtq && field.equals(mtq.getField())) {
						multiTermQueries.add(mtq);
					}
				}

				@Override
				public void consumeTermsMatching(
					Query leaf,
					String queried,
					Supplier<ByteRunAutomaton> automaton
				) {
					if(leaf instanceof MultiTermQuery mtq && field.equals(queried)) {
						multiTermQueries.add(mtq);
					}
				}
			});

			if(multiTermQueries.isEmpty()) {
				return allTerms;
			}

			var expanded = new HashSet<>(allTerms);
			try {
				for(var leaf : getIndexSearcher().getIndexReader().leaves()) {
					var terms = leaf.reader().terms(field);
					if(terms == null) {
						continue;
					}

					for(var multiTermQuery : multiTermQueries) {
						var termsEnum = multiTermQuery.getTermsEnum(terms);
						BytesRef term;
						var budget = MAX_EXPANSIONS;
						while(budget-- > 0 && (term = termsEnum.next()) != null) {
							expanded.add(
								new org.apache.lucene.index.Term(field, BytesRef.deepCopyOf(term))
							);
						}
					}
				}
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}

			return expanded;
		}

		@Override
		protected List<CharSequence[]> loadFieldValues(
			String[] fields,
			DocIdSetIterator docIter,
			int cacheCharsThreshold
		) throws IOException {
			/*
			 * The documents were read before highlighting began, so nothing is
			 * read here and the threshold that would otherwise cap how many are
			 * held at once has nothing left to cap.
			 *
			 * The fields being highlighted are never themselves stored - the
			 * text lives in their `stored` sibling, written from the same
			 * value. The arrays line up by position, so taking the siblings in
			 * place of the fields hands every offset the text it points into.
			 */
			var stored = new String[fields.length];
			for(var i = 0; i < fields.length; i++) {
				stored[i] = storedField(fields[i]);
			}

			var values = new ArrayList<CharSequence[]>();
			for(
				var docId = docIter.nextDoc();
				docId != DocIdSetIterator.NO_MORE_DOCS;
				docId = docIter.nextDoc()
			) {
				var document = documents.get(docId);
				var perField = new CharSequence[stored.length];
				for(var i = 0; i < stored.length; i++) {
					perField[i] = text(document, stored[i]);
				}

				values.add(perField);
			}

			return values;
		}

		/**
		 * Join what one document holds for one field into the text its
		 * passages are cut from, the way the highlighter would have read it:
		 * values separated by {@link #VALUE_SEPARATOR}, cut off at
		 * {@link #getMaxLength()} so that a long field costs a bounded amount
		 * of work however much of it was stored.
		 *
		 * @return
		 *   the text, or {@code null} if the document holds no value for the
		 *   field, which is how a field with nothing to highlight is told from
		 *   one holding an empty value
		 */
		private CharSequence text(org.apache.lucene.document.Document document, String field) {
			var values = document.getValues(field);
			if(values.length == 0) {
				return null;
			}

			var maxLength = getMaxLength();
			if(values.length == 1) {
				var value = values[0];
				return value.length() <= maxLength ? value : value.substring(0, maxLength);
			}

			var text = new StringBuilder(Math.min(maxLength, values[0].length() + 256));
			for(var i = 0; i < values.length && text.length() < maxLength; i++) {
				if(i > 0) {
					text.append(VALUE_SEPARATOR);
				}

				text.append(values[i], 0, Math.min(maxLength - text.length(), values[i].length()));
			}

			return text;
		}

		@Override
		protected BreakIterator getBreakIterator(String field) {
			/*
			 * Fragments break on sentences, stretched or shrunk toward the
			 * length asked for, with the match centered in what remains.
			 */
			return LengthGoalBreakIterator.createClosestToLength(
				BreakIterator.getSentenceInstance(Locale.ROOT),
				target(field).options().length(),
				0.5f
			);
		}

		@Override
		protected PassageFormatter getFormatter(String field) {
			var options = target(field).options();
			return new FragmentsFormatter(options.pre(), options.post());
		}

		@Override
		protected PassageScorer getScorer(String field) {
			return SCORER;
		}

		private Map<String, Object[]> highlight(
			String[] fields,
			Query query,
			int[] docIds,
			int[] maxPassages
		) throws IOException {
			return highlightFieldsAsObjects(fields, query, docIds, maxPassages);
		}
	}

	/**
	 * Reads offsets from postings with each term sought once per segment
	 * rather than once per document.
	 *
	 * <p>The stock {@link PostingsOffsetStrategy} opens a fresh
	 * {@link org.apache.lucene.index.TermsEnum} and seeks every term of the
	 * query again for each document, though the documents of a page mostly
	 * share a segment. This one keeps the {@link PostingsEnum} of each term
	 * for as long as the segment stays the same, which turns the repeated
	 * seeks into forward advances - sound because the highlighter visits the
	 * documents of one call in ascending order.
	 *
	 * <p>Handles plain terms only; {@link #getOffsetStrategy} is what checks
	 * that nothing position-sensitive, no automaton and no weight matching is
	 * in play before choosing this. One instance serves one call and is not
	 * safe for concurrent use.
	 */
	private static final class SeekOncePostingsOffsetStrategy extends PostingsOffsetStrategy {
		private LeafReader reader;
		private PostingsEnum[] postings;

		private SeekOncePostingsOffsetStrategy(UHComponents components) {
			super(components);
		}

		@Override
		public OffsetsEnum getOffsetsEnum(LeafReader reader, int docId, String content)
			throws IOException
		{
			if(this.reader != reader) {
				this.reader = reader;
				this.postings = seekTerms(reader);
			}

			if(postings == null) {
				// The segment holds no value for the field
				return OffsetsEnum.EMPTY;
			}

			var terms = components.terms();
			var matches = new ArrayList<OffsetsEnum>(terms.length);
			for(var i = 0; i < terms.length; i++) {
				var termPostings = postings[i];
				if(termPostings == null) {
					continue;
				}

				var current = termPostings.docID();
				if(current < docId) {
					current = termPostings.advance(docId);
				}

				if(current == docId) {
					matches.add(new OffsetsEnum.OfPostings(terms[i], termPostings));
				} else if(current == DocIdSetIterator.NO_MORE_DOCS) {
					// Advancing an exhausted enum is undefined, so it is
					// dropped rather than looked at again
					postings[i] = null;
				}
			}

			return switch(matches.size()) {
				case 0 -> OffsetsEnum.EMPTY;
				case 1 -> matches.get(0);
				default -> new OffsetsEnum.MultiOffsetsEnum(matches);
			};
		}

		/**
		 * Position a {@link PostingsEnum} on each term of the query that the
		 * segment holds, aligned by index with {@code components.terms()}. A
		 * term the segment lacks stays {@code null}.
		 *
		 * @return
		 *   the enums, or {@code null} when the segment holds no postings for
		 *   the field at all
		 * @throws IllegalArgumentException
		 *   if the field was indexed without offsets, matching the stock
		 *   strategy
		 */
		private PostingsEnum[] seekTerms(LeafReader reader) throws IOException {
			var termsIndex = reader.terms(getField());
			if(termsIndex == null) {
				return null;
			}

			var terms = components.terms();
			var termsEnum = termsIndex.iterator();
			var postings = new PostingsEnum[terms.length];
			for(var i = 0; i < terms.length; i++) {
				if(!termsEnum.seekExact(terms[i])) {
					continue;
				}

				var termPostings = termsEnum.postings(null, PostingsEnum.OFFSETS);
				if(termPostings == null) {
					throw new IllegalArgumentException(
						"field '" + getField() + "' was indexed without offsets, cannot highlight"
					);
				}

				postings[i] = termPostings;
			}

			return postings;
		}
	}

	/**
	 * Scores a passage the way {@link PassageScorer} does, unique term by
	 * unique term in the order they were matched. The stock {@code score}
	 * deduplicates the terms through a fresh
	 * {@link org.apache.lucene.util.BytesRefHash} per passage, whose block
	 * pool opens at 32 KB - kilobytes allocated to tell apart the handful of
	 * matches a passage holds. Comparing the matches against each other is
	 * quadratic in that handful and allocates nothing.
	 */
	private static final PassageScorer SCORER = new PassageScorer() {
		@Override
		public float score(Passage passage, int contentLength) {
			var terms = passage.getMatchTerms();
			var freqsInDoc = passage.getMatchTermFreqsInDoc();
			var count = passage.getNumMatches();

			var score = 0d;

			matches:
			for(var i = 0; i < count; i++) {
				var term = terms[i];

				for(var j = 0; j < i; j++) {
					if(terms[j].bytesEquals(term)) {
						continue matches;
					}
				}

				var freqInPassage = 1;
				for(var j = i + 1; j < count; j++) {
					if(terms[j].bytesEquals(term)) {
						freqInPassage++;
					}
				}

				score += tf(freqInPassage, passage.getLength())
					* weight(contentLength, freqsInDoc[i]);
			}

			return (float) (score * norm(passage.getStartOffset()));
		}
	};

	/**
	 * Cuts each passage into its own fragment, with every match wrapped in
	 * the pair of markers asked for. Matches that touch or overlap, the way
	 * the same word matched as a term and again as a prefix does, are wrapped
	 * once.
	 */
	private static final class FragmentsFormatter extends PassageFormatter {
		private final String pre;
		private final String post;

		private FragmentsFormatter(String pre, String post) {
			this.pre = pre;
			this.post = post;
		}

		@Override
		public List<String> format(Passage[] passages, String content) {
			var fragments = new ArrayList<String>(passages.length);
			for(var passage : passages) {
				fragments.add(format(passage, content));
			}

			return fragments;
		}

		private String format(Passage passage, String content) {
			var sb = new StringBuilder();
			var pos = passage.getStartOffset();

			for(var i = 0; i < passage.getNumMatches(); i++) {
				var start = passage.getMatchStarts()[i];
				var end = passage.getMatchEnds()[i];

				// The same stretch of text can match more than once
				while(i + 1 < passage.getNumMatches() && passage.getMatchStarts()[i + 1] < end) {
					end = Math.max(end, passage.getMatchEnds()[++i]);
				}

				// A match can run past the end of its passage
				end = Math.min(end, passage.getEndOffset());

				sb.append(content, pos, start);
				sb.append(pre);
				sb.append(content, start, end);
				sb.append(post);

				pos = end;
			}

			sb.append(content, pos, Math.max(pos, passage.getEndOffset()));

			/*
			 * A passage can start at a separator between two values of a
			 * multi-valued field, or run across one. The separator is not
			 * part of any value, so it reads as a space between values and
			 * never as the edge of a fragment.
			 */
			return sb.toString()
				.replace(PinnedOffsetsHighlighter.VALUE_SEPARATOR, ' ')
				.strip();
		}
	}
}
