package se.l4.exofind.engine.api.v1alpha1.documents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.UpdateRequest;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Tests for changing one value of a field rather than the whole field - one
 * object value of a list, and one locale variant.
 */
public class DocumentPatchPathResourceTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	DocumentResource resource;

	@BeforeEach
	void setup() throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		var registry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		indexes = new Indexes(
			nodeState,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			storageDirectory,
			OptionalInt.empty(),
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			4,
			Duration.ofSeconds(10),
			0,
			Duration.ZERO,
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);

		resource = new DocumentResource(
			indexes,
			new ObjectMapper(),
			TestReindexJobs.create(nodeState, indexes, registry, storageDirectory)
		);
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	@Test
	public void aFieldOfOneObjectValueIsChangedAndTheOtherValuesAreLeft() throws IOException {
		var index = catalogue();

		update(document("id", "1", "variants[sku=V-2].price", 29.0));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(price(variant(stored, "V-1")), is(10.0));
		assertThat(price(variant(stored, "V-2")), is(29.0));
		assertThat(price(variant(stored, "V-3")), is(30.0));

		// The rest of the value it reached into is left as it was
		assertThat(variant(stored, "V-2").get("color"), is("green"));
	}

	@Test
	public void aValueChangedInPlaceKeepsItsPositionInTheList() throws IOException {
		var index = catalogue();

		update(document("id", "1", "variants[sku=V-2].price", 29.0));

		index.commit();

		assertThat(skus(index.getDocument("1")), contains("V-1", "V-2", "V-3"));
	}

	@Test
	public void namingOneObjectValueReplacesItWholeAndLeavesTheOthers() throws IOException {
		var index = catalogue();

		update(
			document(
				"id", "1",
				"variants[sku=V-2]", Map.of("sku", "V-2", "price", 29.0)
			)
		);

		index.commit();

		var stored = index.getDocument("1");
		assertThat(skus(stored), contains("V-1", "V-2", "V-3"));
		assertThat(price(variant(stored, "V-2")), is(29.0));

		// Replaced whole, so what the new value does not give is gone
		assertThat(variant(stored, "V-2").get("color"), is(nullValue()));
	}

	@Test
	public void anObjectValueNamedWithoutBeingGivenAnythingIsRemoved() throws IOException {
		var index = catalogue();

		update(document("id", "1", "variants[sku=V-2]", null));

		index.commit();

		assertThat(skus(index.getDocument("1")), contains("V-1", "V-3"));
	}

	@Test
	public void aValueIsAddedToTheOnesTheFieldHolds() throws IOException {
		var index = catalogue();

		update(
			document(
				"id", "1",
				"variants[]", Map.of("sku", "V-4", "price", 40.0, "color", "red")
			)
		);

		index.commit();

		assertThat(skus(index.getDocument("1")), contains("V-1", "V-2", "V-3", "V-4"));
	}

	@Test
	public void namingTheWholeObjectFieldStillReplacesEveryValue() throws IOException {
		var index = catalogue();

		update(
			document(
				"id", "1",
				"variants", List.of(Map.of("sku", "V-9", "price", 90.0))
			)
		);

		index.commit();

		assertThat(skus(index.getDocument("1")), contains("V-9"));
	}

	@Test
	public void aSelectorThatNamesSeveralValuesChangesAllOfThem() throws IOException {
		var index = catalogue();

		update(
			document(
				"id", "1",
				"variants[]", Map.of("sku", "V-4", "price", 40.0, "color", "blue")
			)
		);
		update(document("id", "1", "variants[color=blue].price", 99.0));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(price(variant(stored, "V-1")), is(99.0));
		assertThat(price(variant(stored, "V-4")), is(99.0));
		assertThat(price(variant(stored, "V-2")), is(20.0));
	}

	@Test
	public void aBackslashLetsASelectorHoldTheBracketThatWouldCloseIt() throws IOException {
		var index = catalogue();

		update(
			document(
				"id", "1",
				"variants[]", Map.of("sku", "V]4", "price", 40.0, "color", "red")
			)
		);
		update(document("id", "1", "variants[sku=V\\]4].price", 44.0));

		index.commit();

		assertThat(price(variant(index.getDocument("1"), "V]4")), is(44.0));
	}

	/**
	 * A field that declares a key takes the key on its own, which is the whole
	 * of what a caller has to know to point at a value.
	 */
	@Test
	public void aFieldOfOneObjectValueIsChangedByItsKeyAlone() throws IOException {
		var index = catalogue();

		update(document("id", "1", "variants[V-2].price", 29.0));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(price(variant(stored, "V-2")), is(29.0));
		assertThat(price(variant(stored, "V-1")), is(10.0));
		assertThat(variant(stored, "V-2").get("color"), is("green"));
	}

	@Test
	public void namingOneObjectValueByItsKeyReplacesItWhole() throws IOException {
		var index = catalogue();

		update(
			document(
				"id", "1",
				"variants[V-2]", Map.of("sku", "V-2", "price", 29.0, "color", "yellow")
			)
		);

		index.commit();

		var stored = index.getDocument("1");
		assertThat(variant(stored, "V-2").get("color"), is("yellow"));
		assertThat(skus(stored), contains("V-1", "V-2", "V-3"));
	}

	@Test
	public void anObjectValueNamedByItsKeyWithoutBeingGivenAnythingIsRemoved()
		throws IOException
	{
		var index = catalogue();

		update(document("id", "1", "variants[V-2]", null));

		index.commit();

		assertThat(skus(index.getDocument("1")), contains("V-1", "V-3"));
	}

	/**
	 * The key is compared as text, so the digits a number was written as are
	 * what a path names it by.
	 */
	@Test
	public void aKeyNamesNoMoreThanOneValue() throws IOException {
		var index = catalogue();

		update(
			document(
				"id", "1",
				"variants[]", Map.of("sku", "V-4", "price", 40.0, "color", "blue")
			)
		);
		update(document("id", "1", "variants[V-4].price", 99.0));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(price(variant(stored, "V-4")), is(99.0));
		assertThat(price(variant(stored, "V-1")), is(10.0));
	}

	@Test
	public void aKeyNamingNoValueOfTheDocumentIsRefused() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> update(document("id", "1", "variants[V-404].price", 1.0))
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:no_match"));
	}

	/**
	 * The path points at itself the way it was written, without the name of
	 * the key field the definition supplied.
	 */
	@Test
	public void aRefusedKeyPathIsNamedTheWayItWasWritten() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> update(document("id", "1", "variants[V-404].price", 1.0))
		);

		assertThat(e.getErrors().get(0).getArguments().get("path"), is("variants[V-404].price"));
	}

	@Test
	public void aKeyOnAFieldThatDeclaresNoneIsRefused() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> update(document("id", "1", "dimensions[W-1].width", 1.0))
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:key_not_declared"));
	}

	/**
	 * An escaped {@code =} is text, so a key holding one is still one word.
	 */
	@Test
	public void aBackslashLetsAKeyHoldTheEqualsThatWouldSplitIt() throws IOException {
		var index = catalogue();

		update(
			document(
				"id", "1",
				"variants[]", Map.of("sku", "V=4", "price", 40.0, "color", "red")
			)
		);
		update(document("id", "1", "variants[V\\=4].price", 44.0));

		index.commit();

		assertThat(price(variant(index.getDocument("1"), "V=4")), is(44.0));
	}

	@Test
	public void aSelectorThatNamesNoValueOfTheDocumentIsRefused() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> update(document("id", "1", "variants[sku=V-404].price", 1.0))
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:no_match"));
	}

	@Test
	public void aFieldInsideASingleObjectIsChangedAndTheRestOfItIsLeft() throws IOException {
		var index = catalogue();

		update(document("id", "1", "dimensions.width", 12.0));

		index.commit();

		var dimensions = (Document) index.getDocument("1").get("dimensions");
		assertThat(dimensions.get("width"), is(12.0));
		assertThat(dimensions.get("height"), is(4.0));
	}

	@Test
	public void aFieldInsideAListOfObjectsHasToSayWhichValue() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> update(document("id", "1", "variants.price", 1.0))
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:value_required"));
	}

	@Test
	public void oneLocaleOfAFieldIsChangedAndTheOthersAreLeft() throws IOException {
		var index = catalogue();

		update(document("id", "1", "title[sv]", "Blabarssylt II"));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(inLocale(stored, "title", "sv"), is("Blabarssylt II"));
		assertThat(inLocale(stored, "title", "en"), is("Blueberry jam"));
	}

	@Test
	public void aLocaleNamedWithoutBeingGivenAnythingIsRemoved() throws IOException {
		var index = catalogue();

		update(document("id", "1", "title[sv]", null));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(inLocale(stored, "title", "sv"), is(nullValue()));
		assertThat(inLocale(stored, "title", "en"), is("Blueberry jam"));
	}

	@Test
	public void namingTheWholeLocaleSpecificFieldStillReplacesEveryVariant() throws IOException {
		var index = catalogue();

		update(document("id", "1", "title", Map.of("sv", "Blabarssylt")));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(inLocale(stored, "title", "sv"), is("Blabarssylt"));
		assertThat(inLocale(stored, "title", "en"), is(nullValue()));
	}

	@Test
	public void aLocaleTheFieldHoldsNoVariantForIsRefused() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> update(document("id", "1", "title[de]", "Blaubeermarmelade"))
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:locale_unknown"));
	}

	@Test
	public void addingToAFieldThatHoldsASingleValueIsRefused() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> update(document("id", "1", "category[]", "jam"))
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:add_not_multiple"));
	}

	@Test
	public void namingOneValueOfAFieldThatHoldsNeitherLocalesNorObjectsIsRefused()
		throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> update(document("id", "1", "category[sv]", "jam"))
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:selector_not_supported"));
	}

	@Test
	public void aKeyThatIsNotAPathIsRefused() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> update(document("id", "1", "variants[sku=V-2", 1.0))
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:path_invalid"));
	}

	@Test
	public void aPathReachingIntoAFieldTheIndexDoesNotHaveIsRefused() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> update(document("id", "1", "nonexistent[sku=V-2]", 1.0))
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:path_unknown_field"));
	}

	@Test
	public void changesToOneDocumentApplyInTheOrderTheyAreGiven() throws IOException {
		var index = catalogue();

		update(
			document(
				"id", "1",
				"variants[sku=V-2].price", 29.0,
				"variants[sku=V-2].color", "blue"
			)
		);

		index.commit();

		var stored = index.getDocument("1");
		assertThat(price(variant(stored, "V-2")), is(29.0));
		assertThat(variant(stored, "V-2").get("color"), is("blue"));
	}

	@Test
	public void aDocumentTheChangeDoesNotNameIsLeftAsItWas() throws IOException {
		var index = catalogue();

		update(document("id", "1", "variants[sku=V-2].price", 29.0));

		index.commit();

		assertThat(skus(index.getDocument("2")), contains("W-1"));
	}

	/**
	 * A path means the same when the document is named by the path of the
	 * request rather than by a key in the change.
	 */
	@Test
	public void aChangeSentForOneDocumentTakesTheSamePaths() throws IOException {
		var index = catalogue();

		resource.patch(
			"catalogue",
			"1",
			document("variants[sku=V-2].price", 29.0, "title[sv]", "Blabarssylt II")
		);

		index.commit();

		var stored = index.getDocument("1");
		assertThat(price(variant(stored, "V-2")), is(29.0));
		assertThat(price(variant(stored, "V-1")), is(10.0));
		assertThat(inLocale(stored, "title", "sv"), is("Blabarssylt II"));
		assertThat(inLocale(stored, "title", "en"), is("Blueberry jam"));
	}

	/**
	 * A name a pattern stands for is the name the document holds its values
	 * by, so a path names the value it wrote rather than the pattern that
	 * accepted it.
	 */
	@Test
	public void aValueIsAddedToAFieldWhoseNameIsAPattern() throws IOException {
		var index = dynamic();

		updateIn("dynamic", document("id", "1", "attr.tags[]", "b"));

		index.commit();

		assertThat(index.getDocument("1").getAll("attr.tags"), contains("a", "b"));
	}

	@Test
	public void oneLocaleOfAFieldWhoseNameIsAPatternIsChanged() throws IOException {
		var index = dynamic();

		updateIn("dynamic", document("id", "1", "text.title[sv]", "Ny titel"));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(inLocale(stored, "text.title", "sv"), is("Ny titel"));
		assertThat(inLocale(stored, "text.title", "en"), is("A title"));
	}

	@Test
	public void aDynamicAttributeInsideOneObjectValueIsChangedByItsKey() throws IOException {
		var index = dynamic();

		updateIn("dynamic", document("id", "1", "variants[V-1].attr.color", "red"));

		index.commit();

		var variant = (Document) index.getDocument("1").getAll("variants").get(0);
		assertThat(variant.get("attr.color"), is("red"));
		assertThat(variant.get("sku"), is("V-1"));
	}

	@Test
	public void aFieldInsideAnObjectWhoseNameIsAPatternIsChanged() throws IOException {
		var index = dynamic();

		updateIn("dynamic", document("id", "1", "spec.weight.value", "200"));

		index.commit();

		var weight = (Document) index.getDocument("1").get("spec.weight");
		assertThat(weight.get("value"), is("200"));
		assertThat(weight.get("unit"), is("g"));
	}

	private void update(Map<String, Object> change) {
		updateIn("catalogue", change);
	}

	private void updateIn(String index, Map<String, Object> change) {
		resource.update(index, null, new UpdateRequest(List.of(change)));
	}

	/**
	 * Get the object value of {@code variants} holding a {@code sku}.
	 */
	private static Document variant(Document stored, String sku) {
		for(var value : stored.getAll("variants")) {
			var variant = (Document) value;
			if(sku.equals(variant.get("sku"))) {
				return variant;
			}
		}

		throw new AssertionError("No variant with the sku " + sku);
	}

	private static Object price(Document variant) {
		return variant.get("price");
	}

	/**
	 * Get the keys of the values of {@code variants}, in the order the document
	 * holds them.
	 */
	private static List<Object> skus(Document stored) {
		return stored.getAll("variants").stream()
			.map(value -> ((Document) value).get("sku"))
			.toList();
	}

	private static Object inLocale(Document stored, String name, String locale) {
		for(var value : stored.fields()) {
			if(value.name().equals(name) && locale.equals(value.locale())) {
				return value.value();
			}
		}

		return null;
	}

	private static Map<String, Object> document(Object... keysAndValues) {
		var result = new LinkedHashMap<String, Object>();
		for(var i = 0; i < keysAndValues.length; i += 2) {
			result.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}

		return result;
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	/**
	 * An index whose names are patterns wherever a name can be one - at the
	 * root, inside an object, and for the object itself.
	 */
	private Index dynamic() throws IOException {
		var index = indexes.create(
			"dynamic",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"attr.*",
					string()
						.setMultiple(true)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"text.*",
					string()
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
						)
						.build()
				)
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setMultiple(true)
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
									.setKey("sku")
									.putFields(
										"sku",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.setRequired(true)
											.build()
									)
									.putFields(
										"attr.*",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
							)
						)
						.build()
				)
				.putFields(
					"spec.*",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"value",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
									.putFields(
										"unit",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
							)
						)
						.build()
				)
				.build()
		);

		resource.add(
			"dynamic",
			new DocumentsRequest(
				List.of(
					document(
						"id", "1",
						"attr.tags", List.of("a"),
						"text.title", Map.of("en", "A title", "sv", "En titel"),
						"variants", List.of(Map.of("sku", "V-1", "attr.color", "blue")),
						"spec.weight", Map.of("value", "180", "unit", "g")
					)
				)
			)
		);

		return index;
	}

	private static FieldDef.Builder number() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.getDefaultInstance())
			);
	}

	private Index catalogue() throws IOException {
		var index = indexes.create(
			"catalogue",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"title",
					string()
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
						)
						.build()
				)
				.putFields(
					"category",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setMultiple(true)
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
									.setKey("sku")
									.putFields(
										"sku",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.setRequired(true)
											.build()
									)
									.putFields(
										"price",
										number()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
									.putFields(
										"color",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
							)
						)
						.build()
				)
				.putFields(
					"dimensions",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"width",
										number()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
									.putFields(
										"height",
										number()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
							)
						)
						.build()
				)
				.build()
		);

		resource.add(
			"catalogue",
			new DocumentsRequest(
				List.of(
					document(
						"id", "1",
						"title", Map.of("en", "Blueberry jam", "sv", "Blabarssylt"),
						"category", "preserves",
						"variants", List.of(
							Map.of("sku", "V-1", "price", 10.0, "color", "blue"),
							Map.of("sku", "V-2", "price", 20.0, "color", "green"),
							Map.of("sku", "V-3", "price", 30.0, "color", "red")
						),
						"dimensions", Map.of("width", 10.0, "height", 4.0)
					),
					document(
						"id", "2",
						"title", Map.of("en", "Rye bread"),
						"category", "bread",
						"variants", List.of(Map.of("sku", "W-1", "price", 12.0, "color", "brown"))
					)
				)
			)
		);

		return index;
	}
}
