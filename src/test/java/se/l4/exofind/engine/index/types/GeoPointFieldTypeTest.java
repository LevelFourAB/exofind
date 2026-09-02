package se.l4.exofind.engine.index.types;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.DistanceSortField;
import se.l4.exofind.engine.index.IndexEncounterImpl;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.GeoPointFieldTypeDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.SortConfig;

/**
 * Tests for which ordering a geo point field builds for a distance sort.
 */
public class GeoPointFieldTypeTest {
	/**
	 * The points hold the same coordinates as the doc values, so the order can
	 * be answered without reading every match.
	 */
	@Test
	public void testFilteredFieldOrdersThroughItsPoints() {
		var def = geoPoint()
			.setFilter(FilterConfig.getDefaultInstance())
			.setSort(SortConfig.getDefaultInstance());

		var sort = FieldTypes.forDef(def.getType())
			.orElseThrow()
			.createDistanceSortField(encounter(def), 59.325, 18.070);

		assertThat(sort, instanceOf(DistanceSortField.class));

		var distance = (DistanceSortField) sort;
		assertThat(distance.getField(), is("location:_:sort"));
		assertThat(distance.getPointsField(), is("location:_:filter"));
		assertThat(distance.getLatitude(), is(59.325));
		assertThat(distance.getLongitude(), is(18.070));
	}

	/**
	 * Without the {@code filter} usage there are no points to skip through,
	 * and the order comes from the doc values alone.
	 */
	@Test
	public void testSortOnlyFieldOrdersThroughItsDocValues() {
		var def = geoPoint().setSort(SortConfig.getDefaultInstance());

		var sort = FieldTypes.forDef(def.getType())
			.orElseThrow()
			.createDistanceSortField(encounter(def), 59.325, 18.070);

		assertThat(sort, is(not(instanceOf(DistanceSortField.class))));
		assertThat(sort.getField(), is("location:_:sort"));
	}

	private static IndexEncounterImpl encounter(FieldDef.Builder def) {
		var encounter = new IndexEncounterImpl(ResourcesDef.getDefaultInstance(), false);
		encounter.updateLocale(Locales.getDefault());
		encounter.updateValue("location", def.build());
		return encounter;
	}

	private static FieldDef.Builder geoPoint() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setGeoPoint(GeoPointFieldTypeDef.getDefaultInstance())
			);
	}
}
