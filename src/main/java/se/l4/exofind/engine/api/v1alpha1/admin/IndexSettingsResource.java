package se.l4.exofind.engine.api.v1alpha1.admin;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.admin.model.SearchSettingsDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.SearchSettingsInfo;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsFeatures;
import se.l4.exofind.engine.index.settings.SearchSettingsNotFoundException;
import se.l4.exofind.engine.index.settings.SearchSettingsStore;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The search settings of an index - the ranking its searches run with, kept
 * apart from the definition of what its documents were indexed as.
 *
 * <p>Settings are handled as desired state the way definitions are: sent in
 * full, replacing what was stored, with the version returned as an
 * {@code ETag} that can be sent back as {@code If-Match}. Unlike a definition
 * they belong to the index name rather than to a generation - promoting a
 * generation keeps them. Every other node picks a change up within
 * {@code exofind.settings.refresh-interval}.
 *
 * <p>Changes run on the index's writer. Not because the object needs it - it
 * is one object replaced conditionally, safe from any node - but because the
 * writer is what reports the new version into the registry, and the node that
 * writes both is the node whose crash between them is one story rather than
 * two. A caller sends the request anywhere and it is passed along, the way
 * document writes are.
 *
 * <p>The ranking is validated against the generation the name answers from
 * when it is stored. A generation promoted later may lack a field the settings
 * name; searches then skip that entry rather than fail, and the index's status
 * says so.
 */
@Path("/v1alpha1/admin/indexes/{name}/settings")
@Produces(MediaType.APPLICATION_JSON)
public class IndexSettingsResource {
	private static final ErrorType MISSING_BODY = ErrorType.withCode("request:missing_body")
		.withMessage("Settings are required");

	private final Indexes indexes;
	private final SearchSettings searchSettings;

	public IndexSettingsResource(Indexes indexes, SearchSettings searchSettings) {
		this.indexes = indexes;
		this.searchSettings = searchSettings;
	}

	/**
	 * Get the search settings of an index as they are stored.
	 *
	 * <p>Read from the storage rather than from this node's copy, so settings
	 * stored elsewhere are answered as soon as they exist. An index that has
	 * none - one searching with its definition alone - answers that there are
	 * no settings rather than with something empty, so the {@code ETag} always
	 * names a version that exists.
	 *
	 * @param name
	 * @return
	 */
	@GET
	@RequiresPermission(Permission.INDEXES_READ)
	public Response get(@PathParam("name") String name) {
		indexes.getOrThrow(name);

		var index = IndexName.parse(name).index();
		var snapshot = searchSettings.read(index)
			.orElseThrow(() -> new SearchSettingsNotFoundException(index));

		return Response.ok()
			.tag(new EntityTag(unquote(snapshot.version())))
			.entity(toInfo(snapshot))
			.build();
	}

	/**
	 * Replace the search settings of an index.
	 *
	 * <p>The ranking is validated against the generation the name answers
	 * from, so settings that would rank by nothing are refused rather than
	 * stored. Takes effect for searches on this node at once and on every
	 * other node within its refresh interval.
	 *
	 * @param name
	 *   the index, or one generation of it - the settings belong to the index
	 *   either way, a generation only names what to validate against
	 * @param ifMatch
	 *   version the settings are expected to have, as returned by the
	 *   {@code ETag} of a previous request. When given and the settings have
	 *   since been changed the request fails instead of overwriting that
	 *   change
	 * @param definition
	 * @return
	 */
	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.SETTINGS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	public Response put(
		@PathParam("name") String name,
		@HeaderParam("If-Match") String ifMatch,
		SearchSettingsDefinition definition
	) {
		if(definition == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var index = indexes.getOrThrow(name);

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

		var expected = expectedVersion(ifMatch);
		var snapshot = searchSettings.put(
			IndexName.parse(name).index(),
			SearchSettingsFeatures.describe(builder.build()),
			expected == null ? null : "\"" + expected + "\""
		);

		return Response.ok()
			.tag(new EntityTag(unquote(snapshot.version())))
			.entity(toInfo(snapshot))
			.build();
	}

	/**
	 * Remove the search settings of an index, returning it to searching with
	 * its definition alone.
	 *
	 * <p>Takes effect the way replacing them does. Removing what is not there
	 * changes nothing, so the request can be repeated.
	 *
	 * @param name
	 * @return
	 */
	@DELETE
	@RequiresPermission(Permission.SETTINGS_WRITE)
	@ServedBy(ServedBy.Node.INDEXER)
	public Response delete(@PathParam("name") String name) {
		indexes.getOrThrow(name);

		searchSettings.delete(IndexName.parse(name).index());

		return Response.noContent().build();
	}

	private static SearchSettingsInfo toInfo(SearchSettings.Snapshot snapshot) {
		return new SearchSettingsInfo(
			snapshot.stored().hasRanking()
				? RankingMapper.toApi(snapshot.stored().getRanking())
				: null,
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
