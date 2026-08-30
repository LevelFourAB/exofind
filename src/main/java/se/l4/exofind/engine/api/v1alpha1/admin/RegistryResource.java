package se.l4.exofind.engine.api.v1alpha1.admin;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import se.l4.exofind.engine.api.ExofindApi;
import se.l4.exofind.engine.api.auth.RequiresPermission;
import se.l4.exofind.engine.api.errors.ErrorResponse;
import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.api.v1alpha1.admin.model.RegistryAuditResponse;
import se.l4.exofind.engine.api.v1alpha1.admin.model.RegistryRepairRequest;
import se.l4.exofind.engine.api.v1alpha1.admin.model.RegistryRepairResponse;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.RegistryAudit;
import se.l4.exofind.engine.index.registry.RegistryAuditReport;
import se.l4.exofind.engine.index.registry.RegistryAuditUnavailableException;
import se.l4.exofind.engine.storage.StorageMode;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Audits and repairs the shared index registry against remote storage.
 *
 * <p><p>The registry defines which indexes and generations exist across the
 * deployment. Auditing compares the registry against remote storage without
 * modifying either. Repairing registers unlisted synced generations found in
 * storage.
 *
 * <p><p>Repair operations only add entries and never remove indexes,
 * generations, or stored data. Requests are served by whichever node receives
 * them and are never forwarded.
 *
 * <p><p>Both endpoints operate only in object storage mode. In local storage
 * mode, requests return an error.
 */
@Tag(
	name = "Registry",
	description = "Compares the index registry with what storage holds, and repairs it.",
	externalDocs = @ExternalDocumentation(
		description = "Registry reference",
		url = "https://exofind.dev/reference/admin-api/#registry"
	)
)
@SecurityRequirement(name = ExofindApi.API_KEY)
@Path("/v1alpha1/admin/registry")
@Produces(MediaType.APPLICATION_JSON)
public class RegistryResource {
	private final StorageMode mode;
	private final Instance<RegistryAudit> audit;
	private final IndexRegistry registry;

	public RegistryResource(
		StorageMode mode,
		Instance<RegistryAudit> audit,
		IndexRegistry registry
	) {
		this.mode = mode;
		this.audit = audit;
		this.registry = registry;
	}

	/**
	 * Reads the registry and remote storage, comparing the two without changing
	 * either.
	 *
	 * @return
	 */
	@GET
	@Path("/audit")
	@RequiresPermission(Permission.REGISTRY_AUDIT)
	@Operation(
		operationId = "auditRegistry",
		summary = "Audit the registry against storage",
		description = """
			Reads the registry and remote storage, comparing the two without \
			changing either.

			Unregistered generations in storage indicate interrupted rollouts \
			or leftover storage from deleted generations. Registered \
			generations missing from storage have no data available to pull.

			Served by whichever node receives the request and never forwarded. \
			Answers only in object storage mode. Requires the `registry.audit` \
			permission (deployment-scoped)."""
	)
	@APIResponse(
		responseCode = "200",
		description = "Comparison of the shared registry with remote storage.",
		content = @Content(schema = @Schema(implementation = RegistryAuditResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The credential does not have the `registry.audit` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The endpoint was called on a node configured with local storage \
			rather than shared storage (`index:registry:audit_unavailable`).""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public RegistryAuditResponse audit() {
		return toResponse(auditOrThrow().audit());
	}

	/**
	 * Registers every synced generation held in storage that the registry does
	 * not name.
	 *
	 * <p><p>The repair only adds entries, keeping existing entries as stored.
	 * If the registry is absent, it is written fresh; if corrupt, it is
	 * replaced with one rebuilt from storage.
	 *
	 * <p><p>When {@code "promoteNewest": true}, each index created by the
	 * repair answers for its highest-numbered generation. Otherwise, a created
	 * index answers for nothing until a generation is promoted.
	 *
	 * @param body
	 *   configuration for created indexes, or omitted for defaults
	 * @return
	 */
	@POST
	@Path("/actions/repair")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.REGISTRY_REPAIR)
	@ServedBy(ServedBy.Node.ANY_NODE)
	@Operation(
		operationId = "repairRegistry",
		summary = "Repair the registry from storage",
		description = """
			Registers every `SYNCED` generation that storage holds and the \
			registry does not name. The repair operation only adds entries: it \
			keeps existing entries as stored and never deletes an index, a \
			generation, or storage data. If the registry is absent, the repair \
			writes it fresh. If the registry is corrupt, the repair replaces \
			it with one rebuilt from storage.

			When `"promoteNewest": true`, each index created by the repair \
			answers for its highest-numbered generation. Hand-named \
			generations are not selected. Indexes that are already registered \
			keep what they answer for. When omitted or false, created indexes \
			answer for nothing until a generation is promoted.

			The write is conditional and rebuilds on top of concurrent \
			registry changes. The answering node applies the repaired registry \
			immediately; other nodes pick it up within \
			`EXOFIND_INDEXES_REFRESH_INTERVAL`.

			Served by whichever node receives the request and never forwarded. \
			Answers only in object storage mode. Requires the \
			`registry.repair` permission (deployment-scoped)."""
	)
	@APIResponse(
		responseCode = "200",
		description = "A summary of the changes made by the repair.",
		content = @Content(schema = @Schema(implementation = RegistryRepairResponse.class))
	)
	@APIResponse(
		responseCode = "401",
		description = "The request carries no credential accepted by this node.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "403",
		description = "The credential does not have the `registry.repair` permission.",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	@APIResponse(
		responseCode = "409",
		description = """
			The endpoint was called on a node configured with local storage \
			(`index:registry:audit_unavailable`), or writing the repaired \
			registry failed (`index:registry:conflict`, \
			`index:registry:io_error`). The registry remains unchanged.""",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class))
	)
	public RegistryRepairResponse repair(RegistryRepairRequest body) {
		var result = auditOrThrow().repair(
			body != null && Boolean.TRUE.equals(body.promoteNewest())
		);

		if(!result.isEmpty()) {
			// This node serves what was repaired at once rather than at its next refresh
			registry.refresh();
		}

		return new RegistryRepairResponse(
			result.createdIndexes().toList(),
			result.addedGenerations().toList(),
			result.promoted().toList()
		);
	}

	private RegistryAudit auditOrThrow() {
		if(mode != StorageMode.OBJECT) {
			throw new RegistryAuditUnavailableException();
		}

		return audit.get();
	}

	private static RegistryAuditResponse toResponse(RegistryAuditReport report) {
		return new RegistryAuditResponse(
			report.registry(),
			report.indexes()
				.collect(index -> new RegistryAuditResponse.AuditedIndex(
					index.name(),
					index.registered(),
					index.live(),
					index.proposedLive(),
					index.generations()
						.collect(generation -> new RegistryAuditResponse.AuditedGeneration(
							generation.name(),
							generation.registered(),
							generation.stored()
						))
						.toList()
				))
				.toList(),
			report.unusable().toList()
		);
	}
}
