package se.l4.exofind.engine.index.schema;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * Tests for what a definition may say about a field refreshed in place: what
 * it can be combined with, what it asks of a node, and what changing it does
 * to the documents already indexed.
 */
public class SignalFieldSchemaTest {
	private static FieldDef.Builder number() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setDouble(DoubleFieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	private static IndexDef.Builder base() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build());
	}

	private static List<String> codes(IndexDef definition) {
		try {
			new IndexSchema().setDefinition(definition);
			return List.of();
		} catch(ValidationException e) {
			return e.getErrors().collect(ErrorMessage::getCode).toList();
		}
	}

	@Test
	public void aSignalOnANumberFieldIsAccepted() {
		var definition = base()
			.putFields(
				"popularity",
				number().setSignal(SignalConfig.getDefaultInstance()).build()
			)
			.build();

		assertThat(codes(definition), is(empty()));

		var schema = new IndexSchema();
		schema.setDefinition(definition);

		var field = schema.getField("popularity").orElseThrow();
		assertThat(field.isSignal(), is(true));
		assertThat(field.isSorted(), is(true));
		assertThat(field.isStored(), is(true));
		assertThat(schema.getSignalFields().collect(Field::getName), contains("popularity"));
	}

	@Test
	public void aSignalOnAStringFieldIsRefused() {
		var definition = base()
			.putFields(
				"label",
				string().setSignal(SignalConfig.getDefaultInstance()).build()
			)
			.build();

		assertThat(codes(definition), hasItem("index:field:signal_not_supported"));
	}

	@Test
	public void aSignalCanNotAlsoBeWhatARefreshWouldLeaveStale() {
		var definition = base()
			.putFields(
				"popularity",
				number()
					.setSignal(SignalConfig.getDefaultInstance())
					.setFilter(FilterConfig.getDefaultInstance())
					.setFacet(FacetConfig.getDefaultInstance())
					.setStored(true)
					.build()
			)
			.build();

		assertThat(
			codes(definition).stream().filter("index:field:signal:usage_conflict"::equals).count(),
			is(3L)
		);
	}

	@Test
	public void aSignalCanNotHoldSeveralValues() {
		var definition = base()
			.putFields(
				"popularity",
				number()
					.setSignal(SignalConfig.getDefaultInstance())
					.setMultiple(true)
					.build()
			)
			.build();

		assertThat(codes(definition), hasItem("index:field:invalid_sortable"));
	}

	@Test
	public void aSignalCanNotHaveAWildcardName() {
		var definition = base()
			.putFields(
				"score_*",
				number().setSignal(SignalConfig.getDefaultInstance()).build()
			)
			.build();

		assertThat(codes(definition), hasItem("index:field:signal:wildcard"));
	}

	@Test
	public void aSignalCanNotSitInsideAnObject() {
		var definition = base()
			.putFields(
				"variants",
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder()
							.setObject(
								ObjectFieldTypeDef.newBuilder()
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
									.putFields(
										"popularity",
										number().setSignal(SignalConfig.getDefaultInstance()).build()
									)
							)
					)
					.build()
			)
			.build();

		assertThat(codes(definition), hasItem("index:field:object:inner_usage_not_supported"));
	}

	@Test
	public void aSignalFieldAnswersForARankingSignalAndATieBreaker() {
		var definition = base()
			.putFields(
				"popularity",
				number().setSignal(SignalConfig.getDefaultInstance()).build()
			)
			.setRanking(
				RankingConfig.newBuilder()
					.addSignals(
						RankingConfig.Signal.newBuilder()
							.setField("popularity")
							.setLinear(RankingConfig.Signal.Linear.newBuilder().setCeiling(1))
					)
					.addTieBreakers(
						RankingConfig.TieBreaker.newBuilder().setField("popularity")
					)
			)
			.build();

		assertThat(codes(definition), is(empty()));
	}

	@Test
	public void aLinearSignalNeedsACeilingAboveZero() {
		var definition = base()
			.putFields(
				"popularity",
				number().setSort(SortConfig.getDefaultInstance()).build()
			)
			.setRanking(
				RankingConfig.newBuilder()
					.addSignals(
						RankingConfig.Signal.newBuilder()
							.setField("popularity")
							.setLinear(RankingConfig.Signal.Linear.newBuilder().setCeiling(0))
					)
			)
			.build();

		assertThat(codes(definition), hasItem("index:ranking:signal:invalid_ceiling"));
	}

	@Test
	public void aLinearSignalMeansNothingForATimestamp() {
		var definition = base()
			.putFields(
				"published",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setTimestamp(TimestampFieldTypeDef.getDefaultInstance())
					)
					.setSort(SortConfig.getDefaultInstance())
					.build()
			)
			.setRanking(
				RankingConfig.newBuilder()
					.addSignals(
						RankingConfig.Signal.newBuilder()
							.setField("published")
							.setLinear(RankingConfig.Signal.Linear.newBuilder().setCeiling(1))
					)
			)
			.build();

		assertThat(codes(definition), hasItem("index:ranking:signal:shape_not_supported"));
	}

	@Test
	public void theUsageAndTheShapeAreNamedAsFeatures() {
		var definition = base()
			.putFields(
				"popularity",
				number().setSignal(SignalConfig.getDefaultInstance()).build()
			)
			.setRanking(
				RankingConfig.newBuilder()
					.addSignals(
						RankingConfig.Signal.newBuilder()
							.setField("popularity")
							.setLinear(RankingConfig.Signal.Linear.newBuilder().setCeiling(1))
					)
			)
			.build();

		assertThat(
			IndexFeatures.requiredBy(definition).toList(),
			containsInAnyOrder(
				"type.string",
				"type.double",
				"field.signal",
				"index.ranking",
				"index.ranking_signals",
				"ranking.signal_linear"
			)
		);
	}

	/**
	 * Both directions move the value somewhere the earlier documents never
	 * wrote it: turning the usage on leaves it in their copy and out of the
	 * doc values a signal reads, turning it off the other way around.
	 */
	@Test
	public void turningTheUsageOnOrOffReachesTheDocumentsAlreadyIndexed() {
		var plain = base()
			.putFields("popularity", number().setSort(SortConfig.getDefaultInstance()).build())
			.build();
		var signal = base()
			.putFields("popularity", number().setSignal(SignalConfig.getDefaultInstance()).build())
			.build();

		assertThat(
			DefinitionCompatibility.check(plain, signal).collect(ErrorMessage::getCode).toList(),
			contains("index:definition:usage_added")
		);
		assertThat(
			DefinitionCompatibility.check(signal, plain).collect(ErrorMessage::getCode).toList(),
			contains("index:definition:setting_changed")
		);
		assertThat(
			DefinitionCompatibility.check(signal, signal).toList(),
			is(empty())
		);
	}

	@Test
	public void addingASortToASignalFieldIsCompatible() {
		var signal = base()
			.putFields("popularity", number().setSignal(SignalConfig.getDefaultInstance()).build())
			.build();
		var sorted = base()
			.putFields(
				"popularity",
				number()
					.setSignal(SignalConfig.getDefaultInstance())
					.setSort(SortConfig.getDefaultInstance())
					.build()
			)
			.build();

		assertThat(DefinitionCompatibility.check(signal, sorted).toList(), is(empty()));
	}

	@Test
	public void aDefinitionWithoutASignalRefusesNothingNew() {
		assertThrows(
			ValidationException.class,
			() -> new IndexSchema().setDefinition(
				base().putFields("bad-name", number().build()).build()
			)
		);
	}
}
