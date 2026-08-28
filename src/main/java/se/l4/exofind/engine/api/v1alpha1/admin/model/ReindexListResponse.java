package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Every reindex job the caller may see, finished ones included.
 *
 * @param reindexes
 *   the jobs, ordered by index name
 */
@Schema(description = "Every reindex job across the deployment, finished ones included.")
public record ReindexListResponse(
	@Schema(description = """
		The jobs the key may see, ordered by index name. A job on an index the \
		key has no grant for is left out.""")
	List<ReindexInfo> reindexes
) {
}
