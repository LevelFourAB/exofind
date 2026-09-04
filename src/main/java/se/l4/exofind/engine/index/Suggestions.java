package se.l4.exofind.engine.index;

import java.util.Comparator;
import java.util.HashSet;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SuggestRequest;
import se.l4.exofind.engine.query.SuggestResult;

/**
 * Turns a {@link SuggestRequest} into the search that finds the values and
 * the values found into a {@link SuggestResult}, for {@link Index#suggest}.
 *
 * The values come from a search that brings back no hits and counts one
 * facet per suggested field, each carrying the typed text as its prefix -
 * the same search a filter panel runs to find the values of one facet, over
 * every suggested field at once. The counts are then the ones a facet of a
 * search under the same filters answers, read from the same cache.
 *
 * A second search with the prefix forgiving a mistake is run only when the
 * first found fewer values than the limit, since compiling the automaton for
 * it costs more than the first search does. Every value the first search
 * found is a value the second finds too, so the values of the second that
 * the first did not find are the ones a mistake away.
 */
final class Suggestions {
	private Suggestions() {
	}

	/**
	 * One value found, with the field that holds it and whether it was found
	 * a mistake away from the text.
	 */
	record Candidate(String field, SearchResult.Facet.Value value, boolean corrected) {
		/**
		 * What tells this candidate from every other: a value of a field is
		 * found once by each search.
		 */
		Object key() {
			return Lists.immutable.of(field, value.value());
		}
	}

	/**
	 * The most common values first, then by field and value so that two runs
	 * over the same reader answer the same order.
	 */
	private static final Comparator<Candidate> BY_COUNT = Comparator
		.comparingLong((Candidate candidate) -> candidate.value().count())
		.reversed()
		.thenComparing(Candidate::field)
		.thenComparing(candidate -> String.valueOf(candidate.value().value()));

	/**
	 * Build the search that finds the values of every suggested field the
	 * text starts, or is the given number of mistakes away from.
	 */
	static SearchRequest searchFor(SuggestRequest request, SuggestFields fields, int edits) {
		var facets = Lists.mutable.<Facet>empty();
		for(var field : fields.fields()) {
			facets.add(
				Facet.of(field)
					.withLimit(request.limit())
					.withPrefix(request.text())
					.withPrefixEdits(edits)
			);
		}

		return SearchRequest.create()
			.withFilters(request.filters())
			.withFacets(facets)
			.withLocale(request.locale())
			.withLimit(0)
			.build();
	}

	/**
	 * Read the values one search found, as candidates.
	 *
	 * @param corrected
	 *   whether the search forgave a mistake
	 */
	static ListIterable<Candidate> candidatesOf(
		SearchResult result,
		SuggestFields fields,
		boolean corrected
	) {
		var candidates = Lists.mutable.<Candidate>empty();
		for(var field : fields.fields()) {
			var facet = result.facets().get(field);
			if(facet == null) {
				continue;
			}

			for(var value : facet.values()) {
				candidates.add(new Candidate(field, value, corrected));
			}
		}

		return candidates.sortThis(BY_COUNT);
	}

	/**
	 * Pick the suggestions to answer: the candidates the text starts first,
	 * by count, then the ones a mistake away that the first search did not
	 * find, by count, up to the limit.
	 *
	 * @param near
	 *   the candidates of the search forgiving a mistake, or {@code null}
	 *   where none ran
	 */
	static ListIterable<Candidate> pick(
		ListIterable<Candidate> exact,
		ListIterable<Candidate> near,
		int limit
	) {
		var picked = Lists.mutable.<Candidate>empty();
		var seen = new HashSet<Object>();

		for(var candidate : exact) {
			if(picked.size() == limit) {
				break;
			}

			if(seen.add(candidate.key())) {
				picked.add(candidate);
			}
		}

		if(near != null) {
			for(var candidate : near) {
				if(picked.size() == limit) {
					break;
				}

				if(seen.add(candidate.key())) {
					picked.add(candidate);
				}
			}
		}

		return picked;
	}

	/**
	 * Shape one candidate as a suggestion, marking how much of it was typed.
	 *
	 * The text is the label where the settings declare one for the value in
	 * the locale of the request, as that is what a person reads in a list
	 * and types towards. Where the typed text starts the value but not the
	 * label, the value is what is shown, so that the mark says something
	 * true.
	 *
	 * @param normalizer
	 *   what folds the values of the field, or {@code null} where the field
	 *   compares values unfolded
	 * @param luceneField
	 *   the name the normalizer folds under
	 * @param typed
	 *   the typed text as the request holds it
	 */
	static SuggestResult.Suggestion toSuggestion(
		Candidate candidate,
		Analyzer normalizer,
		String luceneField,
		String typed
	) {
		var value = candidate.value();
		var shown = String.valueOf(value.value());
		var label = value.label();

		if(candidate.corrected()) {
			return new SuggestResult.Suggestion(
				label != null ? label : shown,
				0,
				true,
				candidate.field(),
				value.value(),
				label,
				value.count()
			);
		}

		var folded = fold(normalizer, luceneField, typed);
		var text = shown;
		var covered = -1;
		if(label != null) {
			covered = coveredBy(normalizer, luceneField, label, folded);
			if(covered >= 0) {
				text = label;
			}
		}

		if(covered < 0) {
			covered = coveredBy(normalizer, luceneField, shown, folded);
			if(covered < 0) {
				// Found by the label of another value spelling, so nothing of what is shown was typed
				text = label != null ? label : shown;
				covered = 0;
			}
		}

		return new SuggestResult.Suggestion(
			text,
			covered,
			false,
			candidate.field(),
			value.value(),
			label,
			value.count()
		);
	}

	/**
	 * How many characters at the start of a text the folded prefix covers,
	 * or {@code -1} where the folded text does not start with it.
	 *
	 * Folding is read as one way and monotone - lowercasing a longer text
	 * gives a longer result - so the shortest start of the text that folds
	 * to at least the prefix is the part that was typed.
	 */
	static int coveredBy(Analyzer normalizer, String luceneField, String text, BytesRef prefix) {
		if(!startsWith(fold(normalizer, luceneField, text), prefix)) {
			return -1;
		}

		if(prefix.length == 0) {
			return 0;
		}

		var end = 0;
		while(end < text.length()) {
			end = text.offsetByCodePoints(end, 1);
			if(fold(normalizer, luceneField, text.substring(0, end)).length >= prefix.length) {
				return end;
			}
		}

		return text.length();
	}

	/**
	 * Fold a text the way the dictionary of the field is folded. A text the
	 * analyzer cannot fold into one token is kept as it is, the way
	 * {@link FoldedTerms} keeps a value.
	 */
	private static BytesRef fold(Analyzer normalizer, String luceneField, String text) {
		if(normalizer == null) {
			return new BytesRef(text.toLowerCase());
		}

		try {
			return normalizer.normalize(luceneField, text);
		} catch(IllegalStateException e) {
			return new BytesRef(text);
		}
	}

	private static boolean startsWith(BytesRef bytes, BytesRef prefix) {
		if(bytes.length < prefix.length) {
			return false;
		}

		for(var i = 0; i < prefix.length; i++) {
			if(bytes.bytes[bytes.offset + i] != prefix.bytes[prefix.offset + i]) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Get whether a text is long enough to be looked up a mistake away.
	 */
	static boolean longEnoughForTypos(String text) {
		return text.codePointCount(0, text.length()) >= SuggestRequest.MIN_LENGTH_TYPOS;
	}

	/**
	 * A list to collect suggestions into, sized for the limit.
	 */
	static MutableList<SuggestResult.Suggestion> collector(int limit) {
		return Lists.mutable.withInitialCapacity(limit);
	}
}
