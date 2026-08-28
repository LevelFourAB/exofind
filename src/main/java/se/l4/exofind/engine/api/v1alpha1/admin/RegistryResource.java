package se.l4.exofind.engine.api.v1alpha1.admin;

import se.l4.exofind.engine.api.auth.RequiresPermission;
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
 * The registry of the deployment's indexes checked against the storage they
 * live in, and repaired from it.
 *
 * <p>The registry is one object, and it alone says which indexes exist - the
 * nodes never discover them by listing the storage. A deployment whose
 * registry object is lost or corrupted therefore forgets its indexes even
 * though every one of them is still there. The audit reads the storage the way
 * nothing else does and says how the two disagree; the repair registers what
 * the storage holds that the registry does not name.
 *
 * <p>The audit is also worth running on a healthy deployment: a generation in
 * the storage the registry does not name is a rollout that was interrupted
 * before it was registered, or storage a delete left behind, and a registered
 * generation with nothing behind it has nowhere left to be pulled from.
 *
 * <p>A repair only ever adds to the registry, so nothing here removes an index
 * or any files. It writes conditionally like every registry change and runs on
 * whichever node receives it - the registry is about the deployment rather
 * than about any index, so these requests are never passed to the indexer.
 *
 * <p>Both endpoints answer only in object storage mode. A node storing locally
 * has no storage to compare its registry with.
 */
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
	 * Compare the registry with the storage. Reads both and changes neither.
	 *
	 * @return
	 */
	@GET
	@Path("/audit")
	@RequiresPermission(Permission.REGISTRY_AUDIT)
	public RegistryAuditResponse audit() {
		return toResponse(auditOrThrow().audit());
	}

	/**
	 * Register everything the storage holds that the registry does not name.
	 * A corrupt registry is replaced with one rebuilt from the storage; a
	 * readable one is added to, with every entry it already has kept as it
	 * is.
	 *
	 * <p>Which generation a rebuilt index should answer for is not written
	 * anywhere in the storage, so a created index answers for nothing until a
	 * generation is promoted - or the request says
	 * {@code "promoteNewest": true}, which makes each created index answer for
	 * its highest-numbered generation. The audit says which generation that
	 * would be before anything is written.
	 *
	 * @param body
	 *   how to treat the indexes the repair creates, or nothing for the
	 *   defaults
	 * @return
	 */
	@POST
	@Path("/actions/repair")
	@Consumes(MediaType.APPLICATION_JSON)
	@RequiresPermission(Permission.REGISTRY_REPAIR)
	@ServedBy(ServedBy.Node.ANY_NODE)
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
