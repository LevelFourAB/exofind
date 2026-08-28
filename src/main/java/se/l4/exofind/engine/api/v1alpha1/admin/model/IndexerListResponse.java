package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How the indexes are divided among the nodes right now, as the answering
 * node knows it. Reported by the engine and never accepted as input.
 *
 * <p>An index without a claim has no writer until a write for it appoints
 * one, so it is answered by not appearing rather than by an empty entry.
 *
 * @param candidates
 *   the nodes competing to write indexes, ordered by node
 * @param claims
 *   one entry per index some node writes, ordered by index
 */
@Schema(description = """
	How the indexes are divided among the nodes, as the answering node knows \
	it - so the answer can lag actual state by a few seconds. On a node using \
	local storage both lists are empty.""")
public record IndexerListResponse(
	@Schema(description = "The nodes competing to write indexes, ordered by node.")
	List<Candidate> candidates,

	@Schema(description = """
		One entry per index some node writes, ordered by index. An index with \
		no active claim is left out until a write appoints a writer, and a \
		claim on an index the key has no grant for is left out too.""")
	List<Claim> claims
) {
	/**
	 * A node competing to write indexes.
	 *
	 * @param node
	 *   the name the node competes under
	 * @param address
	 *   where writes are sent, or {@code null} when the node offered no
	 *   address
	 * @param expiresAt
	 *   when the candidacy lapses unless the node renews it
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
				When the candidacy lapses unless the node renews it, as an ISO \
				8601 timestamp.""",
			examples = "2026-08-21T10:15:30Z"
		)
		String expiresAt
	) {
	}

	/**
	 * One index and the node writing it.
	 *
	 * @param index
	 *   name of the index
	 * @param node
	 *   the node writing the index
	 * @param address
	 *   where writes for the index are sent, or {@code null} when the node
	 *   offered no address
	 * @param expiresAt
	 *   when the claim lapses unless the node renews it
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "One index and the node writing it.")
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
				When the claim lapses unless the node renews it, as an ISO \
				8601 timestamp.""",
			examples = "2026-08-21T10:15:30Z"
		)
		String expiresAt
	) {
	}
}
