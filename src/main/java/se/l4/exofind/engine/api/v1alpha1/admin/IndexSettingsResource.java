package se.l4.exofind.engine.api.v1alpha1.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.api.ExofindApi;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.ErrorResponse;
import se.l4.exofind.engine.api.errors.UnrepresentableStateException;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.SearchSettingsDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.SearchSettingsInfo;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.settings.DeclaredValue;
import se.l4.exofind.engine.index.settings.FieldSettings;
import se.l4.exofind.engine.index.settings.QuerySynonyms;
import se.l4.exofind.engine.index.settings.QueryTypoExclusions;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsException;
import se.l4.exofind.engine.index.settings.SearchSettingsFeatures;
import se.l4.exofind.engine.index.settings.SearchSettingsNotFoundException;
import se.l4.exofind.engine.index.settings.SearchSettingsStore;
import se.l4.exofind.engine.index.settings.SearchSettingsVersionMismatchException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Search settings of an index, configuring search behavior separately from the
 * index definition.
 *
 * <p>Settings are handled as desired state: sent in full, replacing what was
 * stored, with the version returned as an {@code ETag} that can be sent back as
 * {@code If-Match}. A change to part of them is sent as paths instead, matching
 * how document field updates are structured. Search settings belong to the
 * index name rather than to a generation, so promoting a generation preserves
 * them. Other nodes pick up changes within
 * {@code exofind.settings.refresh-interval}.
 *
 * <p>Modifying requests run on the node that writes the index; requests
 * received by other nodes are forwarded automatically.
 *
 * <p>Field names in search settings are validated against the generation the
 * index currently answers from. If a generation promoted later lacks a
 * configured field, searches skip that entry rather than fail.
 */
@Tag(
	name = "Search settings",
	description = "Per-index search configuration, managed separately from index definitions.",
	externalDocs = @ExternalDocumentation(
		description = "Search settings reference",
		url = "https://exofind.dev/reference/admin-api/#search-settings"
	)
)
@SecurityRequirement(name = ExofindApi.API_KEY)
@Path("/v1alpha1/admin/indexes/{name}/settings")
@Produces(MediaType.APPLICATION_JSON)
public class IndexSettingsResource {
	/**
	 * How many times a change to part of the settings is built again on top of
	 * a concurrent one before giving up, matching what a whole one is given.
	 */
	private static final int PATCH_ATTEMPTS = 3;

	private static final ErrorType MISSING_BODY = ErrorType.withCode("request:missing_body")
		.withMessage("Settings are required");

	private static final ErrorType MISSING_CHANGE = ErrorType.withCode("request:missing_body")
		.withMessage("A change is required");

	private static final ErrorType UNKNOWN_FIELD = ErrorType
		.withCode("request:update:path_unknown_field")
		.withArguments("path", "field")
		.withMessage(
			"`{{path}}` reaches into the field `{{field}}`, which search settings do not have"
		);

	private static final ErrorType INVALID_SYNONYM_RULE = ErrorType
		.withCode("index:settings:synonyms:invalid_rule")
		.withArguments("name")
		.withMessage(
			"Synonym set `{{name}}` has a rule that is not exactly one kind - equivalent words, or a one way mapping"
		);

	private static final ErrorType INVALID_VALUE = ErrorType
		.withCode("request:update:value_invalid")
		.withArguments("path", "reason")
		.withMessage("`{{path}}` cannot be given that value: {{reason}}");

	/*
	 * Distinct from `index:settings:unavailable`, which is about this node
	 * having nowhere to keep settings. This one is about the stored settings
	 * holding something this node has no name for, which only a change built on
	 * top of them can run into.
	 */
	private static final ErrorType UNREPRESENTABLE = ErrorType
		.withCode("index:settings:unrepresentable")
		.withMessage(
			"The stored settings hold parts this version can not describe, and changing part of them here would drop those"
		);

	private final Indexes indexes;
	private final ObjectMapper mapper;
	private final SearchSettings searchSettings;

	public IndexSettingsResource(
		Indexes indexes,
		ObjectMapper mapper,
		SearchSettings searchSettings
	) {
		this.indexes = indexes;
		this.mapper = mapper;
		this.searchSettings = searchSettings;
	}

	/**
	 * Returns the search settings of an index as stored in remote storage.
	 *
	 * <p>If an index has no search settings and searches with its definition
	 * alone, the request returns a not-found error rather than an empty object,
	 * ensuring the {@code ETag} always represents an explicit stored version.
	 *
	 * @param name
	 * @return
	 */
	@GET
	@RequiresPermission(Permission.INDEXES_READ)
	@Operation(
		operationId = "getSearchSettings",
		summary = "Get search settings",
		description = """
			Returns the search settings as stored, with their version in the \
			`ETag` header. Settings are read directly from storage rather than \
			from the node's local copy.

			An index with no search settings - one searching with its \
			definition alone - returns `404` with `index:settings:not_found` \
			rather than an empty object, ensuring the `ETag` always represents \
			an explicit stored version.

			Served by whichever node receives the request. Requires the \
			`indexes.read` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = """
			The stored settings, with their version in the `ETag` header.""",
		content = @Content(
			schema = @Schema(implementation = SearchSettingsInfo.class),
			examples = @ExampleObject(name = "settings", value = SearchSettingsInfo.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `indexes.read` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = """
			The index has no search settings (`index:settings:not_found`), the \
			index does not exist, or the API key lacks permission on the \
			index.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			Settings storage could not be reached \
			(`index:settings:io_error`, `index:settings:unavailable`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public Response get(
		@Parameter(
			description = """
				The index the settings belong to. Naming a generation reads \
				the same settings, as they belong to the index name rather \
				than to a generation.""",
			example = "books"
		)
		@PathParam("name") String name
	) {
		indexes.getOrThrow(name);

		var index = IndexName.parse(name).index();
		var snapshot = searchSettings.read(index)
			.orElseThrow(() -> new SearchSettingsNotFoundException(index));

		return answer(snapshot);
	}

	/**
	 * Replaces the search settings of an index.
	 *
	 * <p>The server validates the ranking against the generation the index name
	 * answers from. A change takes effect for searches on this node immediately
	 * and on other nodes within the settings refresh interval.
	 *
	 * @param name
	 *   the index, or one generation of it; the settings belong to the index
	 *   either way, and a generation specifies which generation to validate
	 *   against
	 * @param ifMatch
	 *   version the settings are expected to have, as returned by the
	 *   {@code ETag} header of a previous request; if the settings changed
	 *   since, the request fails instead of overwriting that change
	 * @param definition
	 * @return
	 */
	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.SETTINGS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "putSearchSettings",
		summary = "Replace search settings",
		description = """
			Replaces the settings completely and returns them as stored. While \
			a `ranking` is present, it replaces the definition's ranking \
			completely; an empty object turns ranking off.

			The server validates the ranking against the generation the index \
			name answers from, using the same `index:ranking:*` error codes \
			used to validate a definition's ranking. The server validates the \
			fields named by `synonyms`, `typoExclusions`, and `fields` against the same \
			generation.

			A change takes effect for searches on the answering node \
			immediately and on all other nodes within \
			`EXOFIND_SETTINGS_REFRESH_INTERVAL`. Until then, two nodes can \
			rank the same query differently. Search settings outlive \
			generations: a generation promoted later can lack a field the \
			settings name, and searches then skip that entry rather than fail.

			Runs on the node that writes the index. Requires the \
			`settings.write` permission, which is separate from \
			`indexes.write` so relevance tuning can be granted without \
			permission to change what an index contains."""
	)
	@APIResponse(
		responseCode = "200",
		description = """
			The settings as stored, with their new version in the `ETag` \
			header.""",
		content = @Content(
			schema = @Schema(implementation = SearchSettingsInfo.class),
			examples = @ExampleObject(name = "settings", value = SearchSettingsInfo.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The request body is missing, or the settings failed validation \
			against the generation the index answers from (`index:ranking:*`, \
			`index:settings:synonyms:*`, `index:settings:typo_exclusions:*`, \
			`index:settings:fields:*`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `settings.write` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = "No index has this name, or the API key lacks permissions covering it.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			Concurrent modifications prevented the settings from being stored \
			(`index:settings:conflict`), storage could not be reached \
			(`index:settings:io_error`, `index:settings:unavailable`), or no \
			node is available to write the index. The stored settings remain \
			unchanged; send the request again.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "412",
		description = """
			The `If-Match` version does not match the stored settings \
			(`index:settings:version_mismatch`). Read them again and rebuild \
			the change on the version that comes back.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public Response put(
		@Parameter(
			description = """
				The index the settings belong to. Naming a generation stores \
				the same settings and only says which generation to validate \
				the ranking against.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Version the settings are expected to be at, as returned by a \
				previous response's `ETag`. `*` matches any existing version. \
				A version that no longer matches answers `412` instead of \
				overwriting the change that moved it.""",
			example = "\"9f2c1a0b3d4e5f60\""
		)
		@HeaderParam("If-Match") String ifMatch,
		@RequestBody(content = @Content(
			schema = @Schema(implementation = SearchSettingsDefinition.class),
			examples = @ExampleObject(
				name = "settings",
				summary = "A ranking signal, a tie-breaker and a synonym set",
				value = SearchSettingsDefinition.EXAMPLE
			)
		))
		SearchSettingsDefinition definition
	) {
		if(definition == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var index = indexes.getOrThrow(name);

		var expected = expectedVersion(ifMatch);
		var snapshot = searchSettings.put(
			IndexName.parse(name).index(),
			toStored(index, definition),
			expected == null ? null : "\"" + expected + "\""
		);

		return answer(snapshot);
	}

	/**
	 * Modifies named parts of the search settings of an index.
	 *
	 * <p>The change is applied to the stored settings, and the result is
	 * validated against the generation the index answers from.
	 *
	 * @param name
	 *   the index, or one generation of it; the settings belong to the index
	 *   either way, and a generation specifies which generation to validate
	 *   against
	 * @param ifMatch
	 *   version the settings are expected to have, as returned by the
	 *   {@code ETag} header of a previous request; if the settings changed
	 *   since, the request fails instead of building the change on the version
	 *   that replaced them
	 * @param body
	 *   the places to change, keyed by path
	 * @return
	 * @throws UnrepresentableStateException
	 *   if the stored settings hold parts this version has no name for, which a
	 *   change built on top of them would drop
	 */
	@PATCH
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.SETTINGS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "patchSearchSettings",
		summary = "Change some of the search settings",
		description = """
			Changes named parts of the settings, leaving the rest as they are, \
			and returns them as stored. The request body is a change object \
			where each key is a path naming a location in the settings: a path \
			with a value replaces the target value, a path set to `null` \
			clears the target value, and an omitted path leaves the existing \
			value unchanged.

			Paths use field names joined by `.`. A path element can include a \
			bracket selector to select list entries by content rather than \
			index: `ranking.signals[field=sales].weight` changes one weight, \
			`ranking.signals[field=sales]` replaces one signal, \
			`ranking.signals[]` adds a signal, and `ranking` replaces or \
			clears the whole ranking. Selecting entries by content ensures \
			changes apply even if the list order changes.

			The merged settings are validated against the generation the index \
			name answers from, using the same `index:ranking:*` error codes as \
			a `PUT` request. An index with no stored settings is modified as \
			if it had empty settings.

			Without an `If-Match` header, a change that conflicts with a \
			concurrent update rebuilds on the newer version up to three times \
			before returning `index:settings:conflict`. With an `If-Match` \
			header, a version mismatch returns `412` without retrying.

			Takes effect immediately on the answering node and on all other \
			nodes within `EXOFIND_SETTINGS_REFRESH_INTERVAL`.

			Runs on the node that writes the index. Requires the \
			`settings.write` permission."""
	)
	@APIResponse(
		responseCode = "200",
		description = """
			The settings as stored, with their new version in the `ETag` \
			header.""",
		content = @Content(
			schema = @Schema(implementation = SearchSettingsInfo.class),
			examples = @ExampleObject(name = "settings", value = SearchSettingsInfo.EXAMPLE)
		)
	)
	@APIResponse(
		responseCode = "400",
		description = """
			The request body is missing, a key is not a path or names a place \
			the settings cannot be changed at (`request:update:*`), or the \
			result failed validation against the generation the index answers \
			from (`index:ranking:*`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `settings.write` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = "No index has this name, or the API key lacks permissions covering it.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			Concurrent modifications prevented the change from being made \
			(`index:settings:conflict`), the stored settings hold parts this \
			version cannot describe (`index:settings:unrepresentable`), \
			storage could not be reached (`index:settings:io_error`, \
			`index:settings:unavailable`), or no node is available to write \
			the index. The stored settings are unchanged.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "412",
		description = """
			The `If-Match` version does not match the stored settings \
			(`index:settings:version_mismatch`). Read them again and rebuild \
			the change on the version that comes back.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public Response patch(
		@Parameter(
			description = """
				The index the settings belong to. Naming a generation stores \
				the same settings and only says which generation to validate \
				the ranking against.""",
			example = "books"
		)
		@PathParam("name") String name,
		@Parameter(
			description = """
				Version the settings are expected to be at, as returned by a \
				previous response's `ETag`. `*` matches any existing version. \
				A version that no longer matches answers `412` instead of \
				building the change on the one that replaced it.""",
			example = "\"9f2c1a0b3d4e5f60\""
		)
		@HeaderParam("If-Match") String ifMatch,
		@RequestBody(
			description = """
				The places to change, keyed by path. A path with a value \
				replaces what it names, a path set to `null` clears it, and a \
				place no path names is left as it is.""",
			required = true,
			content = @Content(
				schema = @Schema(type = SchemaType.OBJECT, implementation = Object.class),
				examples = @ExampleObject(
					name = "change",
					value = """
						{ "ranking.signals[field=sales].weight": 2.0 }"""
				)
			)
		)
		Map<String, Object> body
	) {
		if(body == null) {
			throw new ValidationException(MISSING_CHANGE.toMessage(ObjectLocation.root()));
		}

		var index = indexes.getOrThrow(name);
		var stored = IndexName.parse(name).index();
		var expected = expectedVersion(ifMatch);

		for(var attempt = 0; attempt < PATCH_ATTEMPTS; attempt++) {
			var snapshot = searchSettings.read(stored).orElse(null);

			var base = snapshot == null
				? new SearchSettingsDefinition(null, null, null, null)
				: describable(snapshot);

			var changed = read(ObjectPatch.applyTo(mapper.valueToTree(base), body, mapper));

			try {
				return answer(searchSettings.put(
					stored,
					toStored(index, changed),
					expected != null ? "\"" + expected + "\"" : version(snapshot)
				));
			} catch(SearchSettingsVersionMismatchException e) {
				if(expected != null) {
					/*
					 * The caller named the version to build on, so a mismatch
					 * is theirs to resolve - building on a fresher one would
					 * overwrite the very change they asked to be told about.
					 */
					throw e;
				}
			}
		}

		throw SearchSettingsException.conflict();
	}

	/**
	 * Turn settings received over the API into the object to store, refusing a
	 * ranking the generation the index name answers from cannot answer for.
	 *
	 * @throws ValidationException
	 *   if the ranking names something the generation does not have
	 */
	private static SearchSettingsStore toStored(
		Index index,
		SearchSettingsDefinition definition
	) {
		var builder = SearchSettingsStore.newBuilder();

		if(definition.ranking() != null) {
			var ranking = RankingMapper.toStored(definition.ranking());

			var errors = index.validateSearchSettings(
				ranking,
				ObjectLocation.root().forField("ranking")
			);
			if(errors.notEmpty()) {
				throw new ValidationException(errors);
			}

			builder.setRanking(ranking);
		}

		if(definition.synonyms() != null) {
			for(var entry : definition.synonyms().entrySet()) {
				builder.putSynonyms(entry.getKey(), toStored(entry.getKey(), entry.getValue()));
			}

			var errors = index.validateSearchSettings(
				builder.getSynonymsMap(),
				ObjectLocation.root().forField("synonyms")
			);
			if(errors.notEmpty()) {
				throw new ValidationException(errors);
			}
		}

		if(definition.typoExclusions() != null) {
			for(var entry : definition.typoExclusions().entrySet()) {
				builder.putTypoExclusions(entry.getKey(), toStored(entry.getValue()));
			}

			var errors = index.validateTypoExclusions(
				builder.getTypoExclusionsMap(),
				ObjectLocation.root().forField("typoExclusions")
			);
			if(errors.notEmpty()) {
				throw new ValidationException(errors);
			}
		}

		if(definition.fields() != null) {
			for(var entry : definition.fields().entrySet()) {
				builder.putFields(entry.getKey(), toStored(entry.getValue()));
			}

			var errors = index.validateFieldSettings(
				builder.getFieldsMap(),
				ObjectLocation.root().forField("fields")
			);
			if(errors.notEmpty()) {
				throw new ValidationException(errors);
			}
		}

		return SearchSettingsFeatures.describe(builder.build());
	}

	private static FieldSettings toStored(SearchSettingsDefinition.FieldSettings settings) {
		var builder = FieldSettings.newBuilder();

		if(settings.interpret() != null) {
			builder.setInterpret(
				se.l4.exofind.engine.index.settings.InterpretConfig.getDefaultInstance()
			);
		}

		if(settings.values() != null) {
			for(var value : settings.values()) {
				var declared = DeclaredValue.newBuilder();
				if(value.value() != null) {
					declared.setValue(value.value());
				}
				if(value.order() != null) {
					declared.setOrder(value.order());
				}
				if(value.labels() != null) {
					declared.putAllLabels(value.labels());
				}

				builder.addValues(declared);
			}
		}

		return builder.build();
	}

	private static SearchSettingsDefinition.FieldSettings toApi(FieldSettings settings) {
		List<SearchSettingsDefinition.DeclaredValue> values = null;
		if(settings.getValuesCount() > 0) {
			values = new ArrayList<>(settings.getValuesCount());
			for(var declared : settings.getValuesList()) {
				values.add(new SearchSettingsDefinition.DeclaredValue(
					declared.getValue(),
					declared.hasOrder() ? declared.getOrder() : null,
					declared.getLabelsMap().isEmpty() ? null : new TreeMap<>(declared.getLabelsMap())
				));
			}
		}

		return new SearchSettingsDefinition.FieldSettings(
			settings.hasInterpret() ? new SearchSettingsDefinition.Interpret() : null,
			values
		);
	}

	/**
	 * Read the field settings of stored settings as the API describes them,
	 * {@code null} when there are none - the same round trip the synonym sets
	 * are read by.
	 */
	private static Map<String, SearchSettingsDefinition.FieldSettings> fieldsOf(
		SearchSettingsStore stored
	) {
		if(stored.getFieldsMap().isEmpty()) {
			return null;
		}

		var fields = new TreeMap<String, SearchSettingsDefinition.FieldSettings>();
		for(var entry : stored.getFieldsMap().entrySet()) {
			fields.put(entry.getKey(), toApi(entry.getValue()));
		}

		return fields;
	}

	private static QuerySynonyms toStored(
		String name,
		SearchSettingsDefinition.QuerySynonyms synonyms
	) {
		var builder = QuerySynonyms.newBuilder()
			.setSet(SynonymsMapper.toStored(name, synonyms.rules(), INVALID_SYNONYM_RULE));

		if(synonyms.fields() != null) {
			builder.addAllFields(synonyms.fields());
		}
		if(synonyms.boost() != null) {
			builder.setBoost(synonyms.boost());
		}

		return builder.build();
	}

	private static SearchSettingsDefinition.QuerySynonyms toApi(QuerySynonyms synonyms) {
		return new SearchSettingsDefinition.QuerySynonyms(
			SynonymsMapper.toApi(synonyms.getSet()),
			synonyms.getFieldsCount() == 0 ? null : List.copyOf(synonyms.getFieldsList()),
			synonyms.hasBoost() ? synonyms.getBoost() : null
		);
	}

	private static QueryTypoExclusions toStored(
		SearchSettingsDefinition.TypoExclusions exclusions
	) {
		var builder = QueryTypoExclusions.newBuilder();

		if(exclusions.words() != null) {
			builder.addAllWords(exclusions.words());
		}
		if(exclusions.fields() != null) {
			builder.addAllFields(exclusions.fields());
		}

		return builder.build();
	}

	private static SearchSettingsDefinition.TypoExclusions toApi(QueryTypoExclusions exclusions) {
		return new SearchSettingsDefinition.TypoExclusions(
			exclusions.getWordsCount() == 0 ? null : List.copyOf(exclusions.getWordsList()),
			exclusions.getFieldsCount() == 0 ? null : List.copyOf(exclusions.getFieldsList())
		);
	}

	/**
	 * Read the synonym sets of stored settings as the API describes them,
	 * {@code null} when there are none - which is what the settings are sent
	 * back as, so that describing them and storing them again is a round trip.
	 */
	private static Map<String, SearchSettingsDefinition.QuerySynonyms> synonymsOf(
		SearchSettingsStore stored
	) {
		if(stored.getSynonymsMap().isEmpty()) {
			return null;
		}

		var synonyms = new TreeMap<String, SearchSettingsDefinition.QuerySynonyms>();
		for(var entry : stored.getSynonymsMap().entrySet()) {
			synonyms.put(entry.getKey(), toApi(entry.getValue()));
		}

		return synonyms;
	}

	/**
	 * Read the excluded words of stored settings as the API describes them,
	 * {@code null} when there are none - the same round trip the synonym sets
	 * are read by.
	 */
	private static Map<String, SearchSettingsDefinition.TypoExclusions> typoExclusionsOf(
		SearchSettingsStore stored
	) {
		if(stored.getTypoExclusionsMap().isEmpty()) {
			return null;
		}

		var exclusions = new TreeMap<String, SearchSettingsDefinition.TypoExclusions>();
		for(var entry : stored.getTypoExclusionsMap().entrySet()) {
			exclusions.put(entry.getKey(), toApi(entry.getValue()));
		}

		return exclusions;
	}

	/**
	 * Read the stored settings as the API describes them, refusing to describe
	 * an object this version would not store back whole.
	 *
	 * <p>What is dropped by describing an object is invisible in the result, so
	 * it is caught by taking the object through both directions and comparing
	 * it against itself - which holds because the same settings always map to
	 * the same stored object.
	 *
	 * @throws UnrepresentableStateException
	 *   if the object needs a feature this build does not have, or holds
	 *   anything else the round trip does not return
	 */
	private static SearchSettingsDefinition describable(SearchSettings.Snapshot snapshot) {
		if(snapshot.unsupportedFeatures().notEmpty()) {
			throw new UnrepresentableStateException(UNREPRESENTABLE);
		}

		var stored = snapshot.stored();
		IndexDefinition.Ranking ranking = stored.hasRanking()
			? RankingMapper.toApi(stored.getRanking())
			: null;
		var synonyms = synonymsOf(stored);
		var typoExclusions = typoExclusionsOf(stored);
		var fields = fieldsOf(stored);

		SearchSettingsStore roundTripped;
		try {
			var builder = SearchSettingsStore.newBuilder();
			if(ranking != null) {
				builder.setRanking(RankingMapper.toStored(ranking));
			}
			if(synonyms != null) {
				for(var entry : synonyms.entrySet()) {
					builder.putSynonyms(
						entry.getKey(),
						toStored(entry.getKey(), entry.getValue())
					);
				}
			}
			if(typoExclusions != null) {
				for(var entry : typoExclusions.entrySet()) {
					builder.putTypoExclusions(entry.getKey(), toStored(entry.getValue()));
				}
			}
			if(fields != null) {
				for(var entry : fields.entrySet()) {
					builder.putFields(entry.getKey(), toStored(entry.getValue()));
				}
			}

			roundTripped = SearchSettingsFeatures.describe(builder.build());
		} catch(EngineException e) {
			/*
			 * A combination that is stored but that the API model can not hold
			 * without contradicting itself, such as a signal shape this version
			 * has no name for. Describing it is the same failure as not having
			 * a name for it.
			 */
			throw new UnrepresentableStateException(UNREPRESENTABLE, e);
		}

		if(!roundTripped.equals(SearchSettingsFeatures.describe(stored))) {
			throw new UnrepresentableStateException(UNREPRESENTABLE);
		}

		return new SearchSettingsDefinition(ranking, synonyms, typoExclusions, fields);
	}

	/**
	 * Read changed settings back out of the JSON they were changed as.
	 *
	 * <p>A field the settings do not have is refused rather than dropped: a
	 * path that names nothing would otherwise be answered with settings that
	 * do not hold the change it asked for.
	 *
	 * @throws ValidationException
	 *   if a path names a field search settings do not have, or gives one a
	 *   value it cannot hold
	 */
	private SearchSettingsDefinition read(ObjectNode changed) {
		try {
			return mapper.readerFor(SearchSettingsDefinition.class)
				.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.readValue(changed);
		} catch(UnrecognizedPropertyException e) {
			throw new ValidationException(
				UNKNOWN_FIELD.toMessage(
					ObjectLocation.root().forField(pathOf(e)),
					"path", pathOf(e),
					"field", e.getPropertyName()
				)
			);
		} catch(JsonMappingException e) {
			throw new ValidationException(
				INVALID_VALUE.toMessage(
					ObjectLocation.root().forField(pathOf(e)),
					"path", pathOf(e),
					"reason", e.getOriginalMessage()
				)
			);
		} catch(IOException e) {
			throw new ValidationException(
				INVALID_VALUE.toMessage(
					ObjectLocation.root(),
					"path", "",
					"reason", e.getMessage()
				)
			);
		}
	}

	/**
	 * Write where a mapping failure sits as a path, so an error points at the
	 * place the change named rather than at a Java type.
	 */
	private static String pathOf(JsonMappingException e) {
		var path = new StringBuilder();

		for(var reference : e.getPath()) {
			if(reference.getFieldName() != null) {
				if(path.length() > 0) {
					path.append('.');
				}

				path.append(reference.getFieldName());
			} else if(reference.getIndex() >= 0) {
				path.append('[').append(reference.getIndex()).append(']');
			}
		}

		return path.toString();
	}

	/**
	 * The version to build a change on, {@code null} when the index has no
	 * settings and the change starts from empty ones.
	 */
	private static String version(SearchSettings.Snapshot snapshot) {
		return snapshot == null ? null : snapshot.version();
	}

	private static Response answer(SearchSettings.Snapshot snapshot) {
		return Response.ok()
			.tag(new EntityTag(unquote(snapshot.version())))
			.entity(toInfo(snapshot))
			.build();
	}

	/**
	 * Removes the search settings of an index, returning it to searching with
	 * its definition alone.
	 *
	 * <p>Takes effect immediately on this node and across the deployment within
	 * the settings refresh interval. Deleting settings that do not exist
	 * changes nothing, so the request can be repeated.
	 *
	 * @param name
	 * @return
	 */
	@DELETE
	@RequiresPermission(Permission.SETTINGS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	@Operation(
		operationId = "deleteSearchSettings",
		summary = "Remove search settings",
		description = """
			Removes the settings, returning the index to the ranking in its \
			definition. Takes effect immediately on the node that holds the \
			index and on other nodes within the settings refresh interval. \
			Deleting settings that do not exist changes nothing and answers \
			`204` all the same, so the request can be repeated.

			Deletion does not remove data held in remote storage, so an index \
			created again under the same name picks its old settings back up.

			Runs on the node that writes the index. Requires the \
			`settings.write` permission."""
	)
	@APIResponse(
		responseCode = "204",
		description = """
			The index has no search settings any more, whether or not it \
			had any."""
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential this node accepts.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The API key does not have the `settings.write` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "404",
		description = "No index has this name, or the API key lacks permissions covering it.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The change could not be stored (`index:settings:conflict`, \
			`index:settings:io_error`, `index:settings:unavailable`), or no \
			node is available to write the index. The stored settings are \
			unchanged.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "502",
		description = "The index writer did not respond to the forwarded request.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public Response delete(
		@Parameter(
			description = "The index whose settings to remove.",
			example = "books"
		)
		@PathParam("name") String name
	) {
		indexes.getOrThrow(name);

		searchSettings.delete(IndexName.parse(name).index());

		return Response.noContent().build();
	}

	private static SearchSettingsInfo toInfo(SearchSettings.Snapshot snapshot) {
		return new SearchSettingsInfo(
			snapshot.stored().hasRanking()
				? RankingMapper.toApi(snapshot.stored().getRanking())
				: null,
			synonymsOf(snapshot.stored()),
			typoExclusionsOf(snapshot.stored()),
			fieldsOf(snapshot.stored()),
			unquote(snapshot.version()),
			snapshot.unsupportedFeatures().isEmpty()
				? null
				: snapshot.unsupportedFeatures().toList()
		);
	}

	/**
	 * Read the version an {@code If-Match} header asks for. {@code *} matches
	 * any version, which is the same as not checking at all here as the index
	 * is known to exist by the time the version is used.
	 *
	 * @param ifMatch
	 * @return
	 *   the version, or {@code null} when no particular version is expected
	 */
	private static String expectedVersion(String ifMatch) {
		if(ifMatch == null) {
			return null;
		}

		var value = ifMatch.trim();
		if(value.isEmpty() || value.equals("*")) {
			return null;
		}

		if(value.startsWith("W/")) {
			value = value.substring(2);
		}

		if(value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}

		return value;
	}

	/**
	 * The version as it goes into an {@code ETag} or a body, without the
	 * quotes the storage keeps it under - {@link EntityTag} adds its own.
	 */
	private static String unquote(String version) {
		if(version.length() >= 2 && version.startsWith("\"") && version.endsWith("\"")) {
			return version.substring(1, version.length() - 1);
		}

		return version;
	}
}
