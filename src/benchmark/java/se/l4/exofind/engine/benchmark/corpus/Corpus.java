package se.l4.exofind.engine.benchmark.corpus;

import java.util.List;

import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.schema.IndexDef;

/**
 * A definition together with an endless supply of documents that fit it.
 *
 * <p>Documents are generated rather than stored, so a benchmark decides how
 * many it wants rather than being given what a file happens to hold. The
 * document at an ordinal is always the same document, on any machine and in any
 * order, which is what lets an index built for one benchmark be reused by the
 * next and lets a result be compared with one from another day.
 *
 * <p>Implementations are safe for concurrent use.
 */
public interface Corpus {
	/**
	 * Get the name this corpus is asked for by, which is also what names the
	 * indexes built from it.
	 */
	String name();

	/**
	 * Get the definition an index of these documents has.
	 */
	IndexDef definition();

	/**
	 * Get the document at an ordinal. Ordinals from {@code 0} up are what a
	 * corpus of a given size holds; the primary key of a document is its
	 * ordinal written out.
	 */
	Document document(long ordinal);

	/**
	 * Get the vocabulary the text of these documents is drawn from, for
	 * choosing terms to search for.
	 */
	Words words();

	/**
	 * Get which field of this corpus plays each part a benchmark asks for.
	 */
	Roles roles();

	/**
	 * Get a value the {@link Roles#keyword()} field holds, by how common it is -
	 * rank zero being the most common, which is the refinement a user is
	 * likeliest to tick.
	 */
	String keywordValue(int rank);

	/**
	 * Get a top level the {@link Roles#hierarchy()} field files documents
	 * under, by how common it is - rank zero being the most common, which is
	 * the category a user is likeliest to open. {@code null} for a corpus
	 * without a hierarchy.
	 */
	default String hierarchyPath(int rank) {
		return null;
	}

	/**
	 * The field a benchmark reaches for when it wants to measure a usage rather
	 * than a particular field, so that the same benchmark runs over any corpus
	 * holding a field defined that way.
	 *
	 * <p>A part a corpus has no field for is {@code null}, and a benchmark
	 * needing it says so rather than running against something else -
	 * {@link #require(String, String)} is what says it.
	 *
	 * @param text
	 *   fields text is matched in, the most heavily weighted first
	 * @param autocomplete
	 *   a field completed as it is typed
	 * @param keyword
	 *   a field filtered and counted on, holding one value per document
	 * @param tags
	 *   a field filtered and counted on, holding several values per document
	 * @param number
	 *   a field filtered, ordered and bucketed on
	 * @param timestamp
	 *   a field filtered and ordered on, holding a point in time
	 * @param hierarchy
	 *   a field whose values are paths through a tree, counted a level at a
	 *   time
	 * @param geo
	 *   a field holding a point on the earth
	 * @param nested
	 *   an object field holding several values per document
	 */
	record Roles(
		List<String> text,
		String autocomplete,
		String keyword,
		String tags,
		String number,
		String timestamp,
		String hierarchy,
		String geo,
		String nested
	) {
	}

	/**
	 * Get the field playing a part, refusing when this corpus has none.
	 *
	 * @throws IllegalStateException
	 *   if {@code field} is {@code null}, naming {@code part} in the message
	 */
	default String require(String field, String part) {
		if(field == null) {
			throw new IllegalStateException(
				"The " + name() + " corpus has no " + part + " field; run this benchmark"
					+ " with a corpus that has one"
			);
		}

		return field;
	}
}
