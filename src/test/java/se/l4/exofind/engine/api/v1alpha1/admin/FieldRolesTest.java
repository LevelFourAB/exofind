package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.v1alpha1.admin.model.AnalyzerDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition.Role;
import se.l4.exofind.engine.api.v1alpha1.admin.model.GeoPointFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ObjectFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.StringFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.TimestampFieldDefinition;
import se.l4.exofind.engine.errors.EngineException;

public class FieldRolesTest {
	private static StringFieldDefinition string(Role role) {
		return new StringFieldDefinition(
			role,
			null, null, null, null, null,
			null, null, null,
			null, null, null,
			null
		);
	}

	private static StringFieldDefinition expandString(FieldDefinition field) {
		return (StringFieldDefinition) expand(field);
	}

	private static FieldDefinition expand(FieldDefinition field) {
		return FieldRoles
			.expand(new IndexDefinition(null, null, Map.of("field", field), null, null, null))
			.fields()
			.get("field");
	}

	@Test
	public void testFieldWithoutRoleIsLeftAlone() {
		var field = string(null);

		assertThat(expand(field), is(field));
	}

	@Test
	public void testIdIsAKeyThatCanBeLookedUp() {
		var field = expandString(string(Role.ID));

		assertThat(field.role(), is(nullValue()));
		assertThat(field.primaryKey(), is(true));
		assertThat(field.required(), is(true));
		assertThat(field.stored(), is(true));
		assertThat(field.filter(), is(notNullValue()));
		assertThat(field.matching(), is(nullValue()));
	}

	@Test
	public void testTitleRanksAWholeValueMatchAboveAPartialOne() {
		var field = expandString(string(Role.TITLE));

		assertThat(field.stored(), is(true));
		assertThat(field.sort(), is(notNullValue()));
		assertThat(field.autocomplete(), is(notNullValue()));

		var matching = field.matching();
		assertThat(matching.weight(), is(3f));
		assertThat(matching.exact(), is(notNullValue()));
		assertThat(matching.highlight(), is(notNullValue()));
		assertThat(matching.typoTolerance(), is(notNullValue()));
		assertThat(
			matching.lengthNormalization(),
			is(StringFieldDefinition.TextUsage.LengthNormalization.STRONG)
		);

		/*
		 * Left to the engine, which builds the chain from the slot and the
		 * locale of the value - a chain named here would stop following the
		 * locale.
		 */
		assertThat(matching.analyzer(), is(nullValue()));
	}

	@Test
	public void testDescriptionDoesNotCountLengthAgainstALongerText() {
		var field = expandString(string(Role.DESCRIPTION));

		assertThat(field.stored(), is(true));
		assertThat(field.autocomplete(), is(nullValue()));

		var matching = field.matching();
		assertThat(matching.highlight(), is(notNullValue()));
		assertThat(matching.typoTolerance(), is(notNullValue()));
		assertThat(
			matching.lengthNormalization(),
			is(StringFieldDefinition.TextUsage.LengthNormalization.NONE)
		);
	}

	@Test
	public void testTagIsFoundBySearchingForTheLabel() {
		var field = expandString(string(Role.TAG));

		assertThat(field.filter(), is(notNullValue()));
		assertThat(field.facet(), is(notNullValue()));
		assertThat(
			field.matching().analyzer().preset(),
			is(AnalyzerDefinition.Preset.PRESERVE_TERMS)
		);
		assertThat(field.matching().weight(), is(nullValue()));
	}

	@Test
	public void testPathCountsOneLevelAtATime() {
		var field = expandString(string(Role.PATH));

		assertThat(field.filter(), is(notNullValue()));
		assertThat(field.facet(), is(notNullValue()));
		assertThat(field.hierarchy(), is(notNullValue()));
		assertThat(field.hierarchy().separator(), is(nullValue()));
	}

	@Test
	public void testCodeIsNotStemmedAndToleratesNoTypos() {
		var field = expandString(string(Role.CODE));

		assertThat(field.stored(), is(true));
		assertThat(field.filter(), is(notNullValue()));
		assertThat(
			field.matching().analyzer().preset(),
			is(AnalyzerDefinition.Preset.PRESERVE_TERMS)
		);
		assertThat(field.matching().typoTolerance(), is(nullValue()));

		/*
		 * The engine-built autocomplete chain is what adds the prefixes a
		 * search matches, so naming one here would leave the field with none.
		 */
		assertThat(field.autocomplete().analyzer(), is(nullValue()));
	}

	@Test
	public void testTimestampCanBeNarrowedOrderedAndCounted() {
		var field = (TimestampFieldDefinition) expand(
			new TimestampFieldDefinition(
				Role.TIMESTAMP,
				null, null, null, null, null,
				null, null, null
			)
		);

		assertThat(field.role(), is(nullValue()));
		assertThat(field.filter(), is(notNullValue()));
		assertThat(field.sort(), is(notNullValue()));
		assertThat(field.facet(), is(notNullValue()));
	}

	@Test
	public void testGeoCanBeNarrowedAndOrderedByDistance() {
		var field = (GeoPointFieldDefinition) expand(
			new GeoPointFieldDefinition(
				Role.GEO,
				null, null, null, null, null,
				null, null, null
			)
		);

		assertThat(field.role(), is(nullValue()));
		assertThat(field.filter(), is(notNullValue()));
		assertThat(field.sort(), is(notNullValue()));
		assertThat(field.facet(), is(nullValue()));
	}

	@Test
	public void testWhatIsGivenBesideARoleIsKept() {
		var field = expandString(new StringFieldDefinition(
			Role.TITLE,
			null, null, null,
			false,
			null,
			null, null, null,
			null,
			new StringFieldDefinition.TextUsage(
				null, 8f, null, null, null, null, null
			),
			null,
			null
		));

		assertThat(field.stored(), is(false));

		// The weight is the caller's, the rest of the usage is the role's
		assertThat(field.matching().weight(), is(8f));
		assertThat(field.matching().exact(), is(notNullValue()));
		assertThat(
			field.matching().lengthNormalization(),
			is(StringFieldDefinition.TextUsage.LengthNormalization.STRONG)
		);
	}

	@Test
	public void testRoleTheTypeCanNotAnswerForIsRefused() {
		var exception = assertThrows(
			EngineException.class,
			() -> expand(string(Role.TIMESTAMP))
		);

		assertThat(exception.getCode(), is("index:field:role:not_valid_for_type"));
	}

	@Test
	public void testRoleOfAnotherTypeOnATimestampIsRefused() {
		var exception = assertThrows(
			EngineException.class,
			() -> expand(new TimestampFieldDefinition(
				Role.TITLE,
				null, null, null, null, null,
				null, null, null
			))
		);

		assertThat(exception.getCode(), is("index:field:role:not_valid_for_type"));
	}

	/**
	 * An object field holds no primary key, so the role that would make one is
	 * refused rather than quietly expanding to something else.
	 */
	@Test
	public void testIdInsideAnObjectIsRefused() {
		var exception = assertThrows(
			EngineException.class,
			() -> expand(object(ObjectFieldDefinition.Mode.NESTED, string(Role.ID)))
		);

		assertThat(exception.getCode(), is("index:field:role:not_valid_in_object"));
	}

	@Test
	public void testRoleInsideAnObjectLeavesWhatAnObjectRefuses() {
		var object = (ObjectFieldDefinition) expand(
			object(ObjectFieldDefinition.Mode.NESTED, string(Role.TITLE))
		);
		var field = (StringFieldDefinition) object.fields().get("inner");

		assertThat(field.stored(), is(nullValue()));
		assertThat(field.matching().highlight(), is(nullValue()));

		// Sorting works inside a nested object, so the role keeps it
		assertThat(field.sort(), is(notNullValue()));
		assertThat(field.matching().weight(), is(3f));
	}

	@Test
	public void testRoleInsideAFlattenedListLeavesSorting() {
		var object = (ObjectFieldDefinition) expand(
			object(ObjectFieldDefinition.Mode.FLATTENED, string(Role.TITLE))
		);
		var field = (StringFieldDefinition) object.fields().get("inner");

		assertThat(field.sort(), is(nullValue()));
		assertThat(field.matching(), is(notNullValue()));
	}

	/**
	 * A single object is one unit whether or not it says so, so it orders like
	 * a nested one rather than like a flattened list.
	 */
	@Test
	public void testRoleInsideASingleObjectKeepsSorting() {
		var object = (ObjectFieldDefinition) expand(new ObjectFieldDefinition(
			null, null, null, null, null,
			null, null, null,
			null,
			Map.of("inner", string(Role.TITLE))
		));
		var field = (StringFieldDefinition) object.fields().get("inner");

		assertThat(field.sort(), is(notNullValue()));
	}

	/**
	 * Nothing stored carries a role, so a definition read back and sent again
	 * describes the same index.
	 */
	@Test
	public void testARoleNeverReachesStorage() {
		var stored = IndexDefinitionMapper.toStored(
			new IndexDefinition(
				null,
				null,
				Map.of("name", string(Role.TITLE)),
				null,
				null,
				null
			)
		);

		var api = IndexDefinitionMapper.toApi(stored);
		var field = (StringFieldDefinition) api.fields().get("name");

		assertThat(field.role(), is(nullValue()));
		assertThat(field.matching().weight(), is(3f));
		assertThat(IndexDefinitionMapper.toStored(api), is(stored));
	}

	private static ObjectFieldDefinition object(
		ObjectFieldDefinition.Mode mode,
		FieldDefinition inner
	) {
		return new ObjectFieldDefinition(
			null, null, true, null, null,
			null, null, null,
			mode,
			Map.of("inner", inner)
		);
	}
}
