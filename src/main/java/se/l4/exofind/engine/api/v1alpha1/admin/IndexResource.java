package se.l4.exofind.engine.api.v1alpha1.admin;

import java.io.IOException;
import java.util.List;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.UnrepresentableStateException;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.admin.model.GenerationSummary;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexListResponse;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexStatus;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.registry.RegisteredIndex;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Administration of the indexes on this node - creating them, changing what
 * they contain and synchronizing them with the remote.
 *
 * Definitions are handled as desired state: a definition is sent in full and
 * replaces the previous one, so the same request can be repeated without
 * changing the outcome. The version of a definition is returned as an
 * {@code ETag} and can be sent back as {@code If-Match} to be told that
 * someone else has changed the index instead of overwriting their change.
 *
 * An index holds generations, and a definition belongs to a generation rather
 * than to the index. Every endpoint here takes either the index - which means
 * the generation it currently answers from - or one generation by name, written
 * {@code books@2}. A definition that the documents already indexed were not
 * indexed under is rolled out by creating a generation, filling it and
 * promoting it, rather than by changing an index in place: the name callers use
 * never changes, so nothing they hold has to be updated when it happens.
 *
 * Everything here that changes an index - its definition, its generations,
 * which one it answers from - runs on the indexer, and a request that reaches
 * another node is passed along to it. The registry itself could be written
 * from anywhere, being one object replaced conditionally, but routing every
 * change about a name through the node that writes the name keeps "one writer
 * per index" true for all of it rather than only for the documents.
 *
 * Indexing and searching documents are not part of this API.
 */
@Path("/v1alpha1/admin/indexes")
@Produces(MediaType.APPLICATION_JSON)
public class IndexResource {
	private static final ErrorType MISSING_BODY = ErrorType.withCode("request:missing_body")
		.withMessage("A definition is required");

	private static final ErrorType IO_ERROR = ErrorType.withCode("index:io_error")
		.withArguments("index")
		.withMessage("The index `{{index}}` could not be updated on disk");

	private final Indexes indexes;
	private final AuthContext auth;

	public IndexResource(Indexes indexes, AuthContext auth) {
		this.indexes = indexes;
		this.auth = auth;
	}

	/**
	 * List the indexes available on this node that the caller has been granted
	 * something on.
	 *
	 * <p>An index no grant of the caller's key covers is left out rather than
	 * refused, the same way asking for it directly answers that there is no such
	 * index - a listing is not a way around what a key can see.
	 *
	 * @return
	 */
	@GET
	@RequiresPermission(value = Permission.INDEXES_READ, anyIndex = true)
	public IndexListResponse list() {
		var principal = auth.principal();
		var found = indexes.getRegistered()
			.select(index -> principal.allows(Permission.INDEXES_READ, index.name()))
			.collect(index -> new IndexListResponse.IndexSummary(
				index.name(),
				index.live(),
				toGenerations(index)
			))
			.toSortedListBy(IndexListResponse.IndexSummary::name);

		return new IndexListResponse(found);
	}

	/**
	 * Get an index, its definition and its current status.
	 *
	 * @param name
	 * @return
	 */
	@GET
	@Path("/{name}")
	@RequiresPermission(Permission.INDEXES_READ)
	public Response get(@PathParam("name") String name) {
		var index = indexes.getOrThrow(name);
		return toResponse(Response.ok(), index).build();
	}

	/**
	 * Create an index, add a generation to one, or replace the definition of
	 * something that is already there.
	 *
	 * <p>Which of those happens is decided by the name. {@code books} creates
	 * the index with a first generation, or replaces the definition of the
	 * generation it answers from; {@code books@2} adds that generation to an
	 * index that already exists, or replaces its definition. A generation
	 * created here holds no documents and the index goes on answering from the
	 * one it had, until {@code actions/promote} says otherwise.
	 *
	 * @param name
	 *   the index, or one generation of it
	 * @param ifMatch
	 *   version the definition is expected to have, as returned by the
	 *   {@code ETag} of a previous request. When given and the index has since
	 *   been changed the request fails instead of overwriting that change
	 * @param definition
	 * @return
	 * @throws UnrepresentableStateException
	 *   if the index already has a definition holding settings this version of
	 *   the API can not describe, which replacing it would drop
	 */
	@PUT
	@Path("/{name}")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.INDEXES_WRITE)
	@ServedBy(value = ServedBy.Node.INDEXER, creates = true)
	public Response put(
		@PathParam("name") String name,
		@HeaderParam("If-Match") String ifMatch,
		@Context UriInfo uriInfo,
		IndexDefinition definition
	) {
		if(definition == null) {
			throw new ValidationException(MISSING_BODY.toMessage(ObjectLocation.root()));
		}

		var stored = IndexDefinitionMapper.toStored(definition);
		var requested = IndexName.parse(name);
		var existing = indexes.get(name);

		if(existing.isEmpty()) {
			/*
			 * There is nothing to be told about a conflict with, so an
			 * expected version can not be satisfied.
			 */
			if(expectedVersion(ifMatch) != null) {
				throw new IndexNotFoundException(name);
			}

			var created = requested.isPinned()
				? indexes.createGeneration(name, stored)
				: indexes.create(name, stored);

			return toResponse(Response.created(uriInfo.getAbsolutePath()), created).build();
		}

		var index = existing.get();

		/*
		 * A definition replaces the previous one whole, so anything in the
		 * stored one this version has no model for would go without the caller
		 * ever seeing it.
		 */
		IndexDefinitionMapper.checkRepresentable(index.getDefinition());

		try {
			index.updateDefinition(stored, expectedVersion(ifMatch));
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		return toResponse(Response.ok(), index).build();
	}

	/**
	 * Delete an index and every generation of it, or one generation on its own.
	 *
	 * <p>The index is taken out of the registry, so it is gone for the whole
	 * deployment rather than only for this node - every node removes its own
	 * copy when it next reads the registry. What the remote holds under it is
	 * not removed. The generation an index answers from is refused, so an index
	 * is never left answering for nothing.
	 *
	 * @param name
	 *   the index, or one generation of it
	 * @return
	 */
	@DELETE
	@Path("/{name}")
	@RequiresPermission(Permission.INDEXES_DELETE)
	@ServedBy(ServedBy.Node.INDEXER)
	public Response delete(@PathParam("name") String name) {
		try {
			indexes.delete(name);
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		return Response.noContent().build();
	}

	/**
	 * Make an index answer from one of its generations, which is how a rebuilt
	 * index is rolled out.
	 *
	 * <p>Callers using the index by name read the promoted generation from here
	 * on - on this node at once, and on every other node within its refresh
	 * interval. Nothing they hold changes, so this is also how a rollout is
	 * undone: promote the generation that was answering before.
	 *
	 * @param name
	 *   the generation to promote, as {@code index@generation}
	 * @return
	 */
	@POST
	@Path("/{name}/actions/promote")
	@RequiresPermission(Permission.INDEXES_PROMOTE)
	@ServedBy(ServedBy.Node.INDEXER)
	public Response promote(@PathParam("name") String name) {
		indexes.promote(name);
		return toResponse(Response.ok(), indexes.getOrThrow(name)).build();
	}

	/**
	 * Commit pending changes to an index and push them to the remote.
	 *
	 * @param name
	 * @return
	 */
	@POST
	@Path("/{name}/actions/commit")
	@RequiresPermission(Permission.INDEXES_COMMIT)
	@ServedBy(ServedBy.Node.INDEXER)
	public IndexStatus commit(@PathParam("name") String name) {
		var index = indexes.getOrThrow(name);

		try {
			index.commit();
		} catch(IOException e) {
			throw new IndexException(IO_ERROR, e, "index", name);
		}

		return toStatus(index);
	}

	/**
	 * Pull the latest state of an index from the remote.
	 *
	 * <p>A pull updates the copy on the node serving it, so it runs wherever
	 * it lands - sending it to the indexer would refresh the one node that is
	 * already current.
	 *
	 * @param name
	 * @return
	 */
	@POST
	@Path("/{name}/actions/pull")
	@RequiresPermission(Permission.INDEXES_PULL)
	@ServedBy(ServedBy.Node.ANY_NODE)
	public IndexStatus pull(@PathParam("name") String name) {
		var index = indexes.getOrThrow(name);
		index.pull();
		return toStatus(index);
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
	 * Build a response describing one generation of an index, tagged with the
	 * version of its definition.
	 *
	 * @param builder
	 * @param index
	 * @return
	 */
	private Response.ResponseBuilder toResponse(
		Response.ResponseBuilder builder,
		Index index
	) {
		var version = index.getDefinitionVersion();
		var name = IndexName.parse(index.getId());
		var registered = indexes.getRegistered(name.index()).orElse(null);

		return builder
			.tag(new EntityTag(version))
			.entity(
				new IndexInfo(
					name.index(),
					name.generation(),
					registered != null && name.generation().equals(registered.live()),
					version,
					IndexDefinitionMapper.toApi(index.getDefinition()),
					toStatus(index),
					registered == null ? List.of() : toGenerations(registered)
				)
			);
	}

	/**
	 * List the generations of an index, saying which one it answers from.
	 */
	private static List<GenerationSummary> toGenerations(RegisteredIndex index) {
		return index.generations()
			.collect(generation -> new GenerationSummary(
				generation.name(),
				generation.name().equals(index.live()),
				generation.createdAt() == null ? null : generation.createdAt().toString()
			))
			.toList();
	}

	private static IndexStatus toStatus(Index index) {
		var createdMajor = index.getLuceneCreatedMajor();

		return new IndexStatus(
			index.getState(),
			index.isReadOnly(),
			index.getLuceneCompatibility(),
			createdMajor.isPresent() ? createdMajor.getAsInt() : null
		);
	}
}
