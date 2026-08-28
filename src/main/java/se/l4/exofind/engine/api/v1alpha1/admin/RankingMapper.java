package se.l4.exofind.engine.api.v1alpha1.admin;

import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.index.schema.RankingConfig;

/**
 * Translates a ranking between the form the API uses and the one the engine
 * stores.
 *
 * <p>Split out of {@link IndexDefinitionMapper} because the same ranking
 * appears in two places: inside a definition, and on its own as the search
 * settings of an index. Both go through here, so the two can never read the
 * same ranking differently - and the round trip staying deterministic is part
 * of what {@link IndexDefinitionMapper#checkRepresentable} relies on.
 */
final class RankingMapper {
	private static final ErrorType INVALID_SIGNAL_SHAPE =
		ErrorType.withCode("index:ranking:signal:invalid_shape")
			.withMessage(
				"A ranking signal has to be exactly one shape - `saturation`, or `decay`"
			);

	private RankingMapper() {
	}

	/**
	 * Convert a ranking received over the API into one that can be stored.
	 *
	 * @param ranking
	 * @return
	 */
	static RankingConfig toStored(IndexDefinition.Ranking ranking) {
		var builder = RankingConfig.newBuilder();

		if(ranking.tieBreakers() != null) {
			for(var tieBreaker : ranking.tieBreakers()) {
				var stored = RankingConfig.TieBreaker.newBuilder();
				if(tieBreaker.field() != null) {
					stored.setField(tieBreaker.field());
				}
				if(tieBreaker.direction() != null) {
					stored.setDirection(
						switch(tieBreaker.direction()) {
							case ASCENDING ->
								RankingConfig.TieBreaker.Direction.DIRECTION_ASCENDING;
							case DESCENDING ->
								RankingConfig.TieBreaker.Direction.DIRECTION_DESCENDING;
						}
					);
				}
				builder.addTieBreakers(stored);
			}
		}

		if(ranking.signals() != null) {
			for(var signal : ranking.signals()) {
				builder.addSignals(toStored(signal));
			}
		}

		return builder.build();
	}

	private static RankingConfig.Signal toStored(IndexDefinition.Ranking.Signal signal) {
		if((signal.saturation() == null) == (signal.decay() == null)) {
			throw new EngineException(INVALID_SIGNAL_SHAPE);
		}

		var builder = RankingConfig.Signal.newBuilder();

		if(signal.field() != null) {
			builder.setField(signal.field());
		}

		if(signal.weight() != null) {
			builder.setWeight(signal.weight());
		}

		if(signal.saturation() != null) {
			var saturation = RankingConfig.Signal.Saturation.newBuilder();
			if(signal.saturation().pivot() != null) {
				saturation.setPivot(signal.saturation().pivot());
			}
			builder.setSaturation(saturation);
		}

		if(signal.decay() != null) {
			var decay = RankingConfig.Signal.Decay.newBuilder();
			if(signal.decay().halfLife() != null) {
				decay.setHalfLifeSeconds(signal.decay().halfLife());
			}
			builder.setDecay(decay);
		}

		return builder.build();
	}

	/**
	 * Convert a stored ranking into the form used by the API.
	 *
	 * @param ranking
	 * @return
	 */
	static IndexDefinition.Ranking toApi(RankingConfig ranking) {
		var tieBreakers = ranking.getTieBreakersList().stream()
			.map(tieBreaker -> new IndexDefinition.Ranking.TieBreaker(
				tieBreaker.hasField() ? tieBreaker.getField() : null,
				tieBreaker.hasDirection() ? toApi(tieBreaker.getDirection()) : null
			))
			.toList();

		/*
		 * Left out rather than rendered empty, so a definition that ranks
		 * by nothing but how well documents match reads the way it did
		 * before there were signals to declare.
		 */
		var signals = ranking.getSignalsList().isEmpty()
			? null
			: ranking.getSignalsList().stream()
				.map(RankingMapper::toApi)
				.toList();

		return new IndexDefinition.Ranking(tieBreakers, signals);
	}

	/**
	 * Convert a stored ranking signal into the form used by the API.
	 *
	 * A shape this version does not know reads as a signal with none, the way
	 * an unknown enum reads as unset - what the ranking needs is named in the
	 * features of whatever carries it, so one holding an unknown shape is
	 * refused before it is ever rendered.
	 *
	 * @param signal
	 * @return
	 */
	private static IndexDefinition.Ranking.Signal toApi(RankingConfig.Signal signal) {
		IndexDefinition.Ranking.Signal.Saturation saturation = null;
		if(signal.hasSaturation()) {
			saturation = new IndexDefinition.Ranking.Signal.Saturation(
				signal.getSaturation().hasPivot() ? signal.getSaturation().getPivot() : null
			);
		}

		IndexDefinition.Ranking.Signal.Decay decay = null;
		if(signal.hasDecay()) {
			decay = new IndexDefinition.Ranking.Signal.Decay(
				signal.getDecay().hasHalfLifeSeconds()
					? signal.getDecay().getHalfLifeSeconds()
					: null
			);
		}

		return new IndexDefinition.Ranking.Signal(
			signal.hasField() ? signal.getField() : null,
			saturation,
			decay,
			signal.hasWeight() ? signal.getWeight() : null
		);
	}

	/**
	 * Convert a stored tie breaker direction, treating one this version does
	 * not know as unset so that the rest still reads.
	 *
	 * @param direction
	 * @return
	 */
	private static IndexDefinition.Ranking.TieBreaker.Direction toApi(
		RankingConfig.TieBreaker.Direction direction
	) {
		return switch(direction) {
			case DIRECTION_ASCENDING -> IndexDefinition.Ranking.TieBreaker.Direction.ASCENDING;
			case DIRECTION_DESCENDING -> IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING;
			default -> null;
		};
	}
}
