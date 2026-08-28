package se.l4.exofind.engine.api.v1alpha1.admin.model;

/**
 * What a caller asks a reindex to do.
 *
 * @param from
 *   the generation to read from - the index itself, or one generation of it
 *   as {@code books@1} - or {@code null} for whichever is live
 * @param promote
 *   {@code auto} or {@code null} to promote once caught up, {@code manual}
 *   to stop in the ready phase and leave the promote to the caller
 */
public record ReindexRequest(
	String from,
	String promote
) {
}
