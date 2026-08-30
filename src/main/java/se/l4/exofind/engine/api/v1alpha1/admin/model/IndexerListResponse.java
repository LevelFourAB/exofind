package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Candidate nodes competing to write indexes and active writer claims across
 * the deployment. Reported by the engine and never accepted as input.
 *
 * <p>Indexes without an active claim are omitted until a write assigns a
 * writer.
 *
 * @param candidates
 *   the nodes competing to write indexes, ordered by node
 * @param claims
 *   active writer claims per index, ordered by index
 */
@Schema(description = """
	Candidate nodes competing to write indexes and the active writer claim for \
	each index. The response reflects the answering node's view of shared \
	deployment state and can lag actual state by a few seconds. On nodes using \
	local storage, both lists are empty.""")
public record IndexerListResponse(
	@Schema(description = "The candidate nodes competing to write indexes, ordered by node.")
	List<Candidate> candidates,

	@Schema(description = """
		The active writer claim for each index, ordered by index. Indexes \
		without an active claim are omitted until a write assigns a writer. \
		Claims on indexes where the key lacks permissions are also omitted.""")
	List<Claim> claims
) {
	/**
	 * A node competing to write indexes.
	 *
	 * @param node
	 *   the name the node competes under
	 * @param address
	 *   the target address for write forwarding, or {@code null} if the node
	 *   provided no address
	 * @param expiresAt
	 *   the timestamp when the candidacy expires unless renewed by the node
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "A node competing to write indexes.")
	public record Candidate(
		@Schema(description = "The name the node competes under.", examples = "node-a-7f21")
		String node,

		@Schema(
			description = """
				Target address for write forwarding. Omitted when the node did \
				not set `EXOFIND_NODE_ADDRESS`.""",
			examples = "http://node-a:8080"
		)
		String address,

		@Schema(
			description = """
				The timestamp when the candidacy expires unless renewed by the \
				node, as an ISO 8601 timestamp.""",
			examples = "2026-08-21T10:15:30Z"
		)
		String expiresAt
	) {
	}

	/**
	 * An index and the node writing it.
	 *
	 * @param index
	 *   the name of the index
	 * @param node
	 *   the node writing the index
	 * @param address
	 *   the target address for write forwarding, or {@code null} if the node
	 *   provided no address
	 * @param expiresAt
	 *   the timestamp when the claim expires unless renewed by the node
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "An index and the node writing it.")
	public record Claim(
		@Schema(description = "Name of the index.", examples = "products")
		String index,

		@Schema(description = "The node writing the index.", examples = "node-a-7f21")
		String node,

		@Schema(
			description = """
				Target address for write forwarding. Omitted when the node did \
				not set `EXOFIND_NODE_ADDRESS`.""",
			examples = "http://node-a:8080"
		)
		String address,

		@Schema(
			description = """
				The timestamp when the claim expires unless renewed by the \
				node, as an ISO 8601 timestamp.""",
			examples = "2026-08-21T10:15:30Z"
		)
		String expiresAt
	) {
	}
}
