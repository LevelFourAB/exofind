package se.l4.exofind.engine.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.TreeSet;
import java.util.function.Predicate;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;

/**
 * Reads the words of a search box as the values some field of the index
 * holds, for one reader and one search locale.
 *
 * <p>{@code red nike shoes} is three words, and two of them are values: a
 * colour and a brand. The values a field holds are its facet dictionary, kept
 * folded per segment in {@link FacetStates#foldedTermsOf}. A span of typed
 * words is folded the same way and looked up there, so {@code RED} and
 * {@code Red} are the same value, and a value of several words is found from
 * the words next to each other.
 *
 * <p>Reading is greedy from the left: at each word the longest span that is a
 * value of some field wins, and reading goes on after it. A span that is a
 * value of several fields, or several spellings of one, is all of them at
 * once; which was meant is left to scoring, see {@link Interpretation}.
 *
 * <p>An instance is bound to one reader and is not shared between searches.
 */
final class ValueReader {
	/**
	 * How many words one value may take. Bounds what a long text costs: every
	 * span up to this length is looked up in every dictionary.
	 */
	static final int MAX_WORDS = 3;

	/**
	 * One field the values are looked up in.
	 *
	 * @param entry
	 *   the field, as the settings name it
	 * @param field
	 *   the Lucene field its values were written under, in the search locale
	 * @param normalizer
	 *   what folds a value and a span before they are compared
	 */
	private record Dictionary(ValueDictionaries.Entry entry, String field, Analyzer normalizer) {
	}

	/**
	 * One value a span of words was found to be.
	 *
	 * @param field
	 *   the field holding the value
	 * @param value
	 *   the value as the field stores it
	 */
	record Hit(ValueDictionaries.Entry field, String value) {
	}

	/**
	 * One span of words read as values.
	 *
	 * @param start
	 *   index of the first word it was read from
	 * @param end
	 *   index after the last word it was read from
	 * @param hits
	 *   the values it is, one per field and stored spelling; never empty
	 */
	record Match(int start, int end, ImmutableList<Hit> hits) {
	}

	private final ImmutableList<Dictionary> dictionaries;
	private final IndexReader reader;

	/**
	 * Prepare to read against one reader.
	 *
	 * @param dictionaries
	 *   the fields whose values are read
	 * @param compiler
	 *   the compiler of the search, which resolves each field in the locale
	 *   the search reads it in
	 * @param reader
	 *   the reader the values are looked up in
	 */
	ValueReader(ValueDictionaries dictionaries, QueryCompiler compiler, IndexReader reader) {
		var resolved = Lists.mutable.<Dictionary>empty();
		for(var entry : dictionaries.fields()) {
			/*
			 * Compiled against this generation, so the field is faceted and
			 * counts strings; a field that counts anything else has no
			 * dictionary to read.
			 */
			if(compiler.facetCounter(entry.name()) instanceof FacetCounter.Strings strings
				&& strings.normalizer() != null) {
				resolved.add(new Dictionary(entry, strings.field(), strings.normalizer()));
			}
		}

		this.dictionaries = resolved.toImmutable();
		this.reader = reader;
	}

	/**
	 * Get whether there is no field to read values of.
	 */
	boolean isEmpty() {
		return dictionaries.isEmpty();
	}

	/**
	 * Read every value out of a run of words typed next to each other.
	 *
	 * @param words
	 *   the words, as typed
	 * @param usable
	 *   which fields a value may be read on this time
	 * @return
	 *   what was read, in the order of the words; empty when nothing was
	 * @throws IOException
	 *   if the values of a field cannot be read
	 */
	ImmutableList<Match> read(ListIterable<String> words, Predicate<ValueDictionaries.Entry> usable)
		throws IOException
	{
		var usableDictionaries = dictionaries.select(dictionary -> usable.test(dictionary.entry()));
		if(usableDictionaries.isEmpty()) {
			return Lists.immutable.empty();
		}

		var matches = Lists.mutable.<Match>empty();

		var at = 0;
		while(at < words.size()) {
			Match found = null;
			var longest = Math.min(MAX_WORDS, words.size() - at);
			for(var length = longest; length >= 1 && found == null; length--) {
				var span = words.subList(at, at + length).makeString(" ");
				var hits = lookup(usableDictionaries, span);
				if(hits.notEmpty()) {
					found = new Match(at, at + length, hits);
				}
			}

			if(found == null) {
				at++;
			} else {
				matches.add(found);
				at = found.end();
			}
		}

		return matches.toImmutable();
	}

	/**
	 * Find every value one span is, in every usable field.
	 */
	private ImmutableList<Hit> lookup(ListIterable<Dictionary> usable, String span)
		throws IOException
	{
		var hits = Lists.mutable.<Hit>empty();

		for(var dictionary : usable) {
			var folded = fold(dictionary, span);

			// Sorted and distinct, so the same value in two segments is one hit
			var values = new TreeSet<String>();
			try {
				for(var context : reader.leaves()) {
					var docValues = context.reader().getSortedSetDocValues(dictionary.field());
					if(docValues == null) {
						continue;
					}

					var terms = FacetStates.foldedTermsOf(
						context,
						dictionary.field(),
						dictionary.normalizer(),
						docValues
					);

					terms.forEachEqualTo(folded, ord -> {
						try {
							values.add(docValues.lookupOrd(ord).utf8ToString());
						} catch(IOException e) {
							throw new UncheckedIOException(e);
						}
					});
				}
			} catch(UncheckedIOException e) {
				throw e.getCause();
			}

			for(var value : values) {
				hits.add(new Hit(dictionary.entry(), value));
			}
		}

		return hits.toImmutable();
	}

	/**
	 * Fold a span the way the dictionary folded its values. A span the
	 * analyzer cannot fold into one token is looked up as typed, which is how
	 * a value it could not fold was kept.
	 */
	private static BytesRef fold(Dictionary dictionary, String span) {
		try {
			return dictionary.normalizer().normalize(dictionary.field(), span);
		} catch(IllegalStateException e) {
			return new BytesRef(span);
		}
	}
}
