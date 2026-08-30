package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Every reindex job across the deployment, finished ones included.
 *
 * @param reindexes
 *   the jobs, ordered by index name
 */
@Schema(description = "Every reindex job across the deployment, finished ones included.")
public record ReindexListResponse(
	@Schema(description = """
		The jobs the key can view, ordered by index name. A job on an index on \
		which the key lacks permissions is omitted.""")
	List<ReindexInfo> reindexes
) {
}
