package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

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
public record IndexerListResponse(
	List<Candidate> candidates,
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
	public record Candidate(
		String node,
		String address,
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
	public record Claim(
		String index,
		String node,
		String address,
		String expiresAt
	) {
	}
}
