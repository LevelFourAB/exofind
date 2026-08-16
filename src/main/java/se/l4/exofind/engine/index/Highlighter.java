package se.l4.exofind.engine.index;

import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.LengthGoalBreakIterator;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
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
 * Matches are found by reading the term vectors highlightable fields were
 * indexed with, and the text they are cut from is the stored copy the same
 * declaration forced - the field the query matched holds the offsets, its
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
	private final TermVectorHighlighter highlighter;

	/**
	 * @param searcher
	 * @param targets
	 *   the fields to highlight
	 * @param documents
	 *   the stored fields of every document that will be highlighted, keyed by
	 *   Lucene id, each holding at least the {@link #storedField(String)} of
	 *   every target
	 */
	Highlighter(
		IndexSearcher searcher,
		ListIterable<Target> targets,
		IntObjectMap<org.apache.lucene.document.Document> documents
	) {
		this.targets = targets;

		var byLuceneField = targets.groupByUniqueKey(Target::luceneField);

		/*
		 * The analyzer mirrors what the writer is configured with. It is never
		 * asked to analyze anything - offsets come from the term vectors - but
		 * the highlighter derives its defaults, such as the gap between the
		 * values of a multi-valued field, from it.
		 */
		var builder = UnifiedHighlighter.builder(searcher, new StandardAnalyzer())
			/*
			 * A word that was matched while half typed sits in the index as a
			 * prefix or an automaton rather than as a term. Walking those is
			 * what makes the whole word light up when `spr` matched `Spring`,
			 * which is the point of showing it.
			 */
			.withHandleMultiTermQuery(true)
			/*
			 * The compiler builds queries out of terms, never positions, so
			 * matching by weight has nothing extra to say here - and it is the
			 * one mode that does not lean purely on the vectors.
			 */
			.withWeightMatches(false)
			// A field the query matched nothing in answers with no fragments
			// rather than with the start of the text as a summary
			.withMaxNoHighlightPassages(0);

		this.highlighter = new TermVectorHighlighter(builder, byLuceneField, documents);
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

		var byField = highlighter.highlight(fields, scoringQuery, docIds, maxPassages);

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
	 * offsets always from term vectors, text from the {@code stored} sibling
	 * of the field carrying them, fragments cut and wrapped per target.
	 */
	private static final class TermVectorHighlighter extends UnifiedHighlighter {
		/**
		 * What the values of a multi-valued field are joined by in the text
		 * passages are cut from.
		 */
		static final char VALUE_SEPARATOR = MULTIVAL_SEP_CHAR;

		private final MapIterable<String, Target> targets;
		private final IntObjectMap<org.apache.lucene.document.Document> documents;

		private TermVectorHighlighter(
			UnifiedHighlighter.Builder builder,
			MapIterable<String, Target> targets,
			IntObjectMap<org.apache.lucene.document.Document> documents
		) {
			super(builder);
			this.targets = targets;
			this.documents = documents;
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
			 * field always carries vectors with offsets, and a segment that
			 * happens to hold no value for it must come out empty instead of
			 * falling back to re-analyzing text the index never analyzes this
			 * way.
			 */
			return OffsetSource.TERM_VECTORS;
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
				.replace(TermVectorHighlighter.VALUE_SEPARATOR, ' ')
				.strip();
		}
	}
}
