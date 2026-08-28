package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

/**
 * Every reindex job the caller may see, finished ones included.
 *
 * @param reindexes
 *   the jobs, ordered by index name
 */
public record ReindexListResponse(
	List<ReindexInfo> reindexes
) {
}
