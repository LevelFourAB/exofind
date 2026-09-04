package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Search settings of an index, describing how searches are answered
 * independently of how documents were indexed.
 *
 * <p>Settings are handled as desired state. A request sends the settings in
 * full, replacing previous settings, so repeating the request produces the same
 * outcome. Settings belong to the index name rather than to a generation:
 * promoting a generation preserves existing search settings, and updates
 * propagate across the deployment without passing through the index writer.
 *
 * @param ranking
 *   ranking configuration applied instead of the definition ranking; an empty
 *   object turns ranking off, and omitting the settings leaves the definition
 *   ranking in place
 * @param synonyms
 *   query-time synonym sets applied to search text, keyed by set name
 * @param typoExclusions
 *   words matched as spelled regardless of field typo tolerance, keyed by list
 *   name
 * @param fields
 *   how searches read single fields, keyed by field name
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		Per-index settings that affect how searches are answered, sent in full and \
		replacing what was stored. Search settings belong to the index name rather \
		than to a generation, so promoting a generation preserves existing search \
		settings.""",
	examples = SearchSettingsDefinition.EXAMPLE
)
public record SearchSettingsDefinition(
	@Schema(description = """
		The ranking searches run with instead of the definition's ranking, in \
		the same shape as the definition's `ranking`. While present, it \
		replaces the definition's ranking completely; an empty object turns \
		ranking off. A search request adds its own `signals` to whichever \
		ranking is in force, or replaces them with `signalsMode`. Validated \
		against the generation the index name answers from, using the same \
		`index:ranking:*` error codes that validate a definition's ranking.""")
	IndexDefinition.Ranking ranking,

	@Schema(description = """
		Synonym sets applied to the text of a search, keyed by set name. \
		Unlike index-time synonym sets defined in an index definition, which \
		widen document values during indexing, query-time synonym sets widen \
		the search query and apply to every document already in the index.""")
	Map<String, QuerySynonyms> synonyms,

	@Schema(description = """
		Words matched as they are spelled, keyed by list name. A word on a \
		list is looked up as it was typed, however much typo tolerance the \
		field it is searched in declares. Use a list for brand names and model \
		codes that sit inside text you want typo tolerant otherwise.""")
	Map<String, TypoExclusions> typoExclusions,

	@Schema(description = """
		Settings that apply to one field, keyed by field name. A field inside \
		an object is keyed by its dotted path. Field names are validated \
		against the generation the index answers from at write time. See \
		[Field settings](https://exofind.dev/reference/admin-api/#field-settings).""")
	Map<String, FieldSettings> fields
) {
	/**
	 * The example settings, as the JSON a client sends. The OpenAPI schema of
	 * this record shows this text.
	 */
	public static final String EXAMPLE = """
		{
		  "ranking": {
		    "signals": [
		      { "field": "purchases", "saturation": { "pivot": 50 }, "weight": 0.5 }
		    ],
		    "tieBreakers": [
		      { "field": "sales", "direction": "descending" }
		    ]
		  },
		  "synonyms": {
		    "products": {
		      "rules": [ { "equivalent": ["laptop", "notebook"] } ],
		      "fields": ["name"]
		    }
		  },
		  "fields": {
		    "brand": { "interpret": {} }
		  }
		}""";

	/**
	 * A query-time synonym set applied to search text.
	 *
	 * <p>Rules use the same structure as synonym sets in an index definition.
	 * Rules apply after the field's analysis chain processes the text, and rule
	 * terms are analyzed through that same chain. If an analysis chain leaves
	 * nothing of a term (such as a stopword), that term matches nothing.
	 *
	 * @param rules
	 *   the rules of the set
	 * @param fields
	 *   list of target field names, or {@code null} for every field searched as
	 *   text
	 * @param boost
	 *   weight of terms added by the rules relative to the typed term, or
	 *   {@code null} for the engine default
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		A synonym set applied to the search query at query time, rather than \
		to document values during indexing.""")
	public record QuerySynonyms(
		@Schema(description = """
			The rules of the set, using the same shape as rules in an index \
			definition's `resources`.""")
		List<IndexDefinition.Resources.Synonyms.Rule> rules,

		@Schema(description = """
			An optional list of field names the set applies to, named as a \
			search names them. If omitted, the set applies to every field \
			searched as text. Target fields are validated against the \
			generation the index answers from at write time; a generation \
			promoted later that lacks a named field causes searches to skip \
			that field rather than fail.""")
		List<String> fields,

		@Schema(
			description = """
				A positive number specifying what a term added by the rules \
				counts against the typed term. Default `0.8`. Values below `1` \
				rank a document holding the typed term above one holding only \
				a synonym. A value of `1` weighs synonyms and typed terms \
				equally.""",
			examples = "0.8"
		)
		Float boost
	) {
	}

	/**
	 * Words matched as they are spelled during search.
	 *
	 * <p>Words are evaluated through the analysis chain of each field they are
	 * excluded in. If the chain leaves nothing of a word (such as a stopword),
	 * it excludes nothing; if it produces several terms, each term is excluded.
	 *
	 * <p>The list is matched against the words typed in the search. Unlisted
	 * words retain the typo tolerance of their field, even when an approximate
	 * match lands on a listed word.
	 *
	 * @param words
	 *   the words, as typed
	 * @param fields
	 *   the fields the words are excluded in, or {@code null} for every field
	 *   searched as text
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Words matched as they are spelled, regardless of the typo tolerance \
		configured on the searched fields.""")
	public record TypoExclusions(
		@Schema(description = """
			The words, as typed. Words are read through the analysis chain of \
			each field they are excluded in. A word the chain leaves nothing \
			of (such as a stopword) excludes nothing, and a word the chain \
			produces several terms from excludes each of them.""")
		List<String> words,

		@Schema(description = """
			An optional list of field names the words are excluded in, named \
			as a search names them. If omitted, the list covers every field \
			searched as text. Field names are validated against the active \
			generation at write time; a generation promoted later that lacks a \
			named field applies typo tolerance as configured in the index \
			definition.""")
		List<String> fields
	) {
	}

	/**
	 * How searches read one field of the index.
	 *
	 * <p>Every capability is off unless its object is present, the way the
	 * usages of a field definition are. An empty object turns a capability on
	 * with the engine defaults.
	 *
	 * @param interpret
	 *   present when a search in {@code user} mode reads the values the field
	 *   holds out of the query text, or {@code null} when it does not
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		How searches read one field. Every capability is off unless its object \
		is present; an empty object turns it on with the engine defaults.""")
	public record FieldSettings(
		@Schema(description = """
			Reads the values the field holds out of the query text of a search \
			in `user` mode, as a filter on the field. The field must be a \
			`string` field with `filter` and `facet` and without `hierarchy`; \
			otherwise the request returns \
			`index:settings:fields:interpret_unsupported`. Carries no options. \
			See [Reading the values of a \
			field](https://exofind.dev/reference/search-api/#reading-the-values-of-a-field).""")
		Interpret interpret
	) {
	}

	/**
	 * Reads the values of a field out of the query text. Carries no options.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Reads the values of the field out of the query text. Carries no \
		configuration options.""")
	public record Interpret() {
	}
}
