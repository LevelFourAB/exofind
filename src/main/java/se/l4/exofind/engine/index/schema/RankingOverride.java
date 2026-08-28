package se.l4.exofind.engine.index.schema;

import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.query.RankingSignal;

/**
 * A ranking to search with instead of the one the definition holds, compiled
 * against one generation's schema.
 *
 * <p>Settings belong to the index name while a schema belongs to a generation,
 * so an override can name a field the generation searching with it does not
 * have - written against one generation and outliving it into the next. Such
 * an entry is skipped rather than failing the search, because failing would
 * make promoting a generation depend on the settings having been rewritten
 * first; what was skipped is carried so it can be reported instead of only
 * logged.
 *
 * @param tieBreakers
 *   the tie breakers to append after whatever ordering a search asks for, in
 *   place of the definition's
 * @param signals
 *   the signals to multiply into relevance, in place of the definition's -
 *   unless the search brings signals of its own
 * @param skippedFields
 *   names of the fields whose entries this schema could not answer for,
 *   sorted; empty when the whole override is in force
 */
public record RankingOverride(
	ImmutableList<RankingConfig.TieBreaker> tieBreakers,
	ImmutableList<RankingSignal> signals,
	ListIterable<String> skippedFields
) {
}
