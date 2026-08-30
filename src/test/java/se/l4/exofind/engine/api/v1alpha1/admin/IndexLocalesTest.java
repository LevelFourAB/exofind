package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.ObjectFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.StringFieldDefinition;
import se.l4.exofind.engine.errors.EngineException;

public class IndexLocalesTest {
	private static final IndexDefinition.Locales DECLARED =
		new IndexDefinition.Locales("en", List.of("sv", "de"));

	private static StringFieldDefinition string(FieldDefinition.Locales locales) {
		return new StringFieldDefinition(
			null,
			null, null, null, null, locales,
			null, null, null,
			null, null, null,
			null
		);
	}

	private static ObjectFieldDefinition object(
		FieldDefinition.Locales locales,
		Map<String, FieldDefinition> fields
	) {
		return new ObjectFieldDefinition(
			null, null, null, null, locales,
			null, null, null,
			null, null, fields
		);
	}

	private static IndexDefinition definition(
		IndexDefinition.Locales declared,
		Map<String, FieldDefinition> fields
	) {
		return new IndexDefinition(null, null, fields, null, null, declared, null);
	}

	private static FieldDefinition.Locales expand(
		IndexDefinition.Locales declared,
		FieldDefinition.Locales locales
	) {
		return IndexLocales
			.expand(definition(declared, Map.of("field", string(locales))))
			.fields()
			.get("field")
			.locales();
	}

	private static EngineException refused(
		IndexDefinition.Locales declared,
		Map<String, FieldDefinition> fields
	) {
		return assertThrows(
			EngineException.class,
			() -> IndexLocales.expand(definition(declared, fields))
		);
	}

	@Test
	public void testAnIndexWithoutADeclarationIsLeftAlone() {
		var definition = definition(null, Map.of("name", string(null)));

		assertThat(IndexLocales.expand(definition), is(definition));
	}

	@Test
	public void testAFieldWithoutLocalesStaysUnlocalized() {
		var expanded = IndexLocales.expand(definition(DECLARED, Map.of("sku", string(null))));

		assertThat(expanded.fields().get("sku").locales(), is(nullValue()));
	}

	@Test
	public void testAnEmptyConfigTakesEveryDeclaredLocale() {
		var locales = expand(DECLARED, new FieldDefinition.Locales(null, null, null, null));

		assertThat(locales.defaultLocale(), is("en"));
		assertThat(locales.locales(), is(List.of("sv", "de")));
	}

	@Test
	public void testOnlyNarrowsToTheLocalesItNames() {
		var locales = expand(
			DECLARED,
			new FieldDefinition.Locales(null, null, List.of("en", "sv"), null)
		);

		assertThat(locales.defaultLocale(), is("en"));
		assertThat(locales.locales(), is(List.of("sv")));
	}

	/**
	 * A field holding only the default locale has no other variant to list.
	 */
	@Test
	public void testNarrowingToTheDefaultAloneLeavesNoOtherLocale() {
		var locales = expand(
			DECLARED,
			new FieldDefinition.Locales(null, null, List.of("en"), null)
		);

		assertThat(locales.defaultLocale(), is("en"));
		assertThat(locales.locales(), is(nullValue()));
	}

	@Test
	public void testAFieldKeepsItsOwnDefaultLocale() {
		var locales = expand(
			DECLARED,
			new FieldDefinition.Locales("sv", null, null, null)
		);

		assertThat(locales.defaultLocale(), is("sv"));
		assertThat(locales.locales(), is(List.of("en", "de")));
	}

	@Test
	public void testFallbackIsCarriedThrough() {
		var locales = expand(
			DECLARED,
			new FieldDefinition.Locales(
				null,
				null,
				null,
				FieldDefinition.Locales.Fallback.DISABLED
			)
		);

		assertThat(locales.fallback(), is(FieldDefinition.Locales.Fallback.DISABLED));
	}

	@Test
	public void testTagsAreExpandedCanonically() {
		var locales = expand(
			new IndexDefinition.Locales("EN", List.of("zh-hant", "PT-br")),
			new FieldDefinition.Locales(null, null, List.of("en", "ZH-Hant"), null)
		);

		assertThat(locales.defaultLocale(), is("en"));
		assertThat(locales.locales(), is(List.of("zh-Hant")));
	}

	/**
	 * An index that mixes varieties of one language declares both and gives
	 * each field the one it holds.
	 */
	@Test
	public void testTwoFieldsCanHoldDifferentVarietiesOfOneLanguage() {
		var expanded = IndexLocales.expand(
			definition(
				new IndexDefinition.Locales("no", List.of("nb")),
				Map.of(
					"title",
					string(new FieldDefinition.Locales(null, null, List.of("no"), null)),
					"summary",
					string(new FieldDefinition.Locales("nb", null, List.of("nb"), null))
				)
			)
		);

		assertThat(expanded.fields().get("title").locales().defaultLocale(), is("no"));
		assertThat(expanded.fields().get("title").locales().locales(), is(nullValue()));
		assertThat(expanded.fields().get("summary").locales().defaultLocale(), is("nb"));
		assertThat(expanded.fields().get("summary").locales().locales(), is(nullValue()));
	}

	@Test
	public void testFieldsInsideAnObjectAreExpanded() {
		var expanded = IndexLocales.expand(
			definition(
				DECLARED,
				Map.of(
					"author",
					object(
						null,
						Map.of("name", string(new FieldDefinition.Locales(null, null, null, null)))
					)
				)
			)
		);

		var author = (ObjectFieldDefinition) expanded.fields().get("author");
		var name = author.fields().get("name").locales();

		assertThat(name.defaultLocale(), is("en"));
		assertThat(name.locales(), is(List.of("sv", "de")));
	}

	/**
	 * Nothing below the expansion sees the declaration, so a field always
	 * carries the locales it holds.
	 */
	@Test
	public void testTheDeclarationIsRemoved() {
		var expanded = IndexLocales.expand(
			definition(DECLARED, Map.of("name", string(null)))
		);

		assertThat(expanded.locales(), is(nullValue()));
	}

	@Test
	public void testADeclarationWithoutADefaultLocaleIsRefused() {
		var e = refused(
			new IndexDefinition.Locales(null, List.of("sv")),
			Map.of("name", string(null))
		);

		assertThat(e.getCode(), is("index:locales:default_locale_required"));
	}

	@Test
	public void testNarrowingToAnUndeclaredLocaleIsRefused() {
		var e = refused(
			DECLARED,
			Map.of(
				"name",
				string(new FieldDefinition.Locales(null, null, List.of("en", "fr"), null))
			)
		);

		assertThat(e.getCode(), is("index:field:locales:not_declared"));
		assertThat(e.getArguments().get("locale"), is("fr"));
	}

	@Test
	public void testAnUndeclaredDefaultLocaleIsRefused() {
		var e = refused(
			DECLARED,
			Map.of("name", string(new FieldDefinition.Locales("fr", null, null, null)))
		);

		assertThat(e.getCode(), is("index:field:locales:not_declared"));
		assertThat(e.getArguments().get("locale"), is("fr"));
	}

	@Test
	public void testNarrowingPastTheDefaultLocaleIsRefused() {
		var e = refused(
			DECLARED,
			Map.of(
				"name",
				string(new FieldDefinition.Locales(null, null, List.of("sv"), null))
			)
		);

		assertThat(e.getCode(), is("index:field:locales:default_not_in_only"));
		assertThat(e.getArguments().get("locale"), is("en"));
	}

	/**
	 * Naming a default the narrowed set holds is how a field drops the default
	 * of the index.
	 */
	@Test
	public void testNarrowingPastTheDefaultLocaleIsAllowedWithOwnDefault() {
		var locales = expand(
			DECLARED,
			new FieldDefinition.Locales("sv", null, List.of("sv"), null)
		);

		assertThat(locales.defaultLocale(), is("sv"));
		assertThat(locales.locales(), is(nullValue()));
	}

	@Test
	public void testAFieldListingItsOwnLocalesIsRefused() {
		var e = refused(
			DECLARED,
			Map.of(
				"name",
				string(new FieldDefinition.Locales("en", List.of("sv"), null, null))
			)
		);

		assertThat(e.getCode(), is("index:field:locales:list_with_declaration"));
		assertThat(e.getArguments().get("name"), is("name"));
	}

	@Test
	public void testNarrowingWithoutADeclarationIsRefused() {
		var e = refused(
			null,
			Map.of(
				"name",
				string(new FieldDefinition.Locales(null, null, List.of("en"), null))
			)
		);

		assertThat(e.getCode(), is("index:field:locales:only_without_declaration"));
		assertThat(e.getArguments().get("name"), is("name"));
	}

	@Test
	public void testAFieldInsideAnObjectIsNamedByItsPath() {
		var e = refused(
			DECLARED,
			Map.of(
				"author",
				object(
					null,
					Map.of(
						"name",
						string(new FieldDefinition.Locales("fr", null, null, null))
					)
				)
			)
		);

		assertThat(e.getArguments().get("name"), is("author.name"));
	}
}
