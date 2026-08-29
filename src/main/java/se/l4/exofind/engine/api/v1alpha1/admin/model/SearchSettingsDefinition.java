package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Search settings of an index, describing how its searches behave apart from
 * how its documents were indexed.
 *
 * <p>Settings are the state a caller wants - they are sent in full and replace
 * what was stored, so the same request can be repeated without changing the
 * outcome. Unlike a definition they belong to the index name rather than to a
 * generation: promoting a generation keeps them, and changing them reaches
 * every node without going through the index's writer.
 *
 * @param ranking
 *   the ranking searches run with instead of the definition's. An empty object
 *   turns the definition's ranking off; left out - together with everything
 *   else - the settings say nothing and the definition stands
 * @param synonyms
 *   synonym sets applied to the text of a search, by name
 * @param typoExclusions
 *   words matched as they are spelled however much typo tolerance their
 *   fields declare, by name
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Per-index settings that affect how searches are answered, sent in full and \
	replacing what was stored. They belong to the index name rather than to a \
	generation, so promoting a generation keeps them.""")
public record SearchSettingsDefinition(
	@Schema(description = """
		The ranking searches run with instead of the definition's, in the same \
		shape as the definition's `ranking`. While present it replaces the \
		definition's ranking completely; an empty object turns ranking off. \
		Supplying `signals` in a search request still replaces both. Validated \
		against the generation the index name answers from, using the same \
		`index:ranking:*` codes that validate a definition's ranking.""")
	IndexDefinition.Ranking ranking,

	@Schema(description = """
		Synonym sets applied to the text of a search, by name. Unlike the sets \
		in an index definition, which widen a value as it is indexed, these \
		widen what a search asks for - so a rule added here reaches documents \
		that were indexed long before it.""")
	Map<String, QuerySynonyms> synonyms,

	@Schema(description = """
		Words matched as they are spelled, by name. A word listed here is \
		looked up as it was typed however much typo tolerance the field it is \
		searched in declares, which is how a brand name or a model code keeps \
		its spelling inside text that is otherwise typo tolerant.""")
	Map<String, TypoExclusions> typoExclusions
) {
	/**
	 * A synonym set applied to what a search asks for.
	 *
	 * <p>The rules are the ones an index definition holds in its resources, so
	 * a set can be moved between the two sides without being rewritten. They
	 * are applied after the analysis chain of the field has had the text, so
	 * the terms of the rules are read through that chain as well: a term the
	 * chain leaves nothing of, such as a stopword, matches nothing.
	 *
	 * @param rules
	 *   the rules of the set
	 * @param fields
	 *   the fields the set is applied to, or {@code null} for every field a
	 *   search reads as text
	 * @param boost
	 *   what a term the rules added counts against the term that was typed, or
	 *   {@code null} for the engine default
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		A synonym set applied to what a search asks for, rather than to values \
		as they are indexed.""")
	public record QuerySynonyms(
		@Schema(description = """
			The rules of the set, in the same shape as the rules of a set in \
			an index definition's `resources`.""")
		List<IndexDefinition.Resources.Synonyms.Rule> rules,

		@Schema(description = """
			The fields the set is applied to, named as a search names them. \
			Omitted applies it to every field a search reads as text. A name \
			the generation the index answers from does not have is refused \
			here; a generation promoted later that lacks it makes searches \
			skip the field rather than fail.""")
		List<String> fields,

		@Schema(
			description = """
				What a term the rules added counts against the term that was \
				typed. Below `1` a document holding the word that was typed \
				ranks above one holding only a synonym of it; `1` makes the \
				two count the same. Defaults to `0.8`.""",
			examples = "0.8"
		)
		Float boost
	) {
	}

	/**
	 * Words a search matches as they are spelled.
	 *
	 * <p>The words are read through the analysis chain of the field, so a word
	 * the chain leaves nothing of, such as a stopword, excludes nothing, and
	 * one it leaves several terms of excludes each of them.
	 *
	 * <p>The list is read against the words a search was typed with: a word
	 * that is not listed keeps the tolerance of its field, including when a
	 * near reading of it lands on a listed word.
	 *
	 * @param words
	 *   the words, as they would be typed
	 * @param fields
	 *   the fields the words are excluded in, or {@code null} for every field
	 *   a search reads as text
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Words matched as they are spelled, whatever typo tolerance the fields \
		they are searched in declare.""")
	public record TypoExclusions(
		@Schema(description = """
			The words, as they would be typed. A word is read through the \
			analysis chain of each field it is excluded in, so a word the \
			chain leaves nothing of excludes nothing, and one it leaves \
			several terms of excludes each of them.""")
		List<String> words,

		@Schema(description = """
			The fields the words are excluded in, named as a search names \
			them. Omitted covers every field a search reads as text. A name \
			the generation the index answers from does not have is refused \
			here; a generation promoted later that lacks it makes searches \
			forgive mistakes in the field as the definition says.""")
		List<String> fields
	) {
	}
}
