package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import org.apache.lucene.util.Version;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.BooleanFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSync;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.query.SearchRequest;

public class IndexTest {
	@TempDir
	Path indexRoot;

	private List<Index> indexes = new ArrayList<>();

	@AfterEach
	void cleanup() throws IOException {
		for(var idx : indexes) {
			idx.close();
		}
	}

	/**
	 * Node state as it looks once ownership has settled: a candidate that has
	 * been granted the indexer role, or a node that never competes for it.
	 */
	private static NodeState nodeState(boolean indexer) {
		var state = new NodeState(indexer);
		state.updateOwnership(indexer);
		return state;
	}

	private Index create(String name) throws IOException {
		var path = indexRoot.resolve(name);
		Files.createDirectories(path);

		var index = new Index(nodeState(true), name, path, new NoopSync());
		indexes.add(index);
		return index;
	}

	private Index create(String name, IndexDef.Builder def) throws IOException {
		var index = create(name);
		index.pull();
		index.updateDefinition(def.build());
		return index;
	}

	private Index create(IndexDef.Builder def) throws IOException {
		return create("test", def);
	}

	/**
	 * Open an index over the given node state and synchronization, for a test
	 * that is about what one of them says rather than about the index itself.
	 */
	private Index create(
		String name,
		NodeState nodeState,
		StateSync sync,
		IndexDef.Builder def
	) throws IOException {
		var path = indexRoot.resolve(name);
		Files.createDirectories(path);

		var index = new Index(nodeState, name, path, sync);
		indexes.add(index);

		index.pull();
		index.updateDefinition(def.build());
		return index;
	}

	@Test
	public void testDefinitionStoredOnDisk() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"field1",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
								.build()
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);
		assertThat(index.getFields().size(), is(1));
		assertThat(index.getFields().get(0).getName(), is("field1"));
		// assertThat(index.getFields().get(0).isIndexed(), is(true));

		// Read the schema from disk
		try(
			var in = Files.newInputStream(
				indexRoot.resolve("test").resolve(Index.DEFINITION_FILE)
			)
		) {
			var def = IndexDef.parseFrom(in);
			assertThat(def.getFieldsCount(), is(1));
			var fields = def.getFieldsMap();
			assertThat(fields.get("field1").getType().hasBoolean(), is(true));
			assertThat(fields.get("field1").hasFilter(), is(true));
		}
	}

	/**
	 * A definition holding one string field, and the same one with the field
	 * turned into something a search can filter on - a change that writes a
	 * Lucene field nothing already indexed has.
	 */
	private static IndexDef.Builder oneStringField(boolean filterable) {
		var field = FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			)
			.setPrimaryKey(true);

		if(filterable) {
			field.setFilter(FilterConfig.getDefaultInstance());
		}

		return IndexDef.newBuilder().putFields("id", field.build());
	}

	@Test
	public void testDefinitionChangeIsAcceptedWhileNothingIsIndexed() throws IOException {
		var index = create(oneStringField(false));

		index.updateDefinition(oneStringField(true).build());

		assertThat(index.getField("id").get().isFiltered(), is(true));
	}

	@Test
	public void testDefinitionChangeReachingNoIndexedDocumentIsRefused() throws IOException {
		var index = create(oneStringField(false));
		index.addDocument(new Document(new Document.Value("id", "1")));
		index.commit();

		var e = assertThrows(
			IndexDefinitionIncompatibleException.class,
			() -> index.updateDefinition(oneStringField(true).build())
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:definition:usage_added"));
		assertThat(e.getErrors().get(0).getLocation().describe(), is("id"));

		// The definition the documents were indexed under is what the index keeps
		assertThat(index.getField("id").get().isFiltered(), is(false));
	}

	/**
	 * A document that has been written but not committed was written under the
	 * definition about to be replaced the same way a committed one was.
	 */
	@Test
	public void testDefinitionChangeIsRefusedForUncommittedDocuments() throws IOException {
		var index = create(oneStringField(false));
		index.addDocument(new Document(new Document.Value("id", "1")));

		assertThrows(
			IndexDefinitionIncompatibleException.class,
			() -> index.updateDefinition(oneStringField(true).build())
		);
	}

	@Test
	public void testDefinitionChangeIsAcceptedWhenStaleDocumentsAreAllowed() throws IOException {
		var index = create(oneStringField(false));
		index.addDocument(new Document(new Document.Value("id", "1")));
		index.commit();

		index.updateDefinition(oneStringField(true).build(), null, true);

		assertThat(index.getField("id").get().isFiltered(), is(true));
	}

	/**
	 * Nothing is left to be stale once the documents are gone, so the index
	 * takes the definition it refused while it held them.
	 */
	@Test
	public void testDefinitionChangeIsAcceptedAfterTheDocumentsAreRemoved() throws IOException {
		var index = create(oneStringField(false));
		index.addDocument(new Document(new Document.Value("id", "1")));
		index.commit();

		index.deleteDocument("1");
		index.commit();

		index.updateDefinition(oneStringField(true).build());

		assertThat(index.getField("id").get().isFiltered(), is(true));
	}

	@Test
	public void testAddDocumentWithValidFields() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.newBuilder().build()).build()
						)
						.setPrimaryKey(true)
						.build()
				)
				.putFields(
					"field1",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
								.build()
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);

		var doc = new Document(
			new Document.Value("id", "123"),
			new Document.Value("field1", true)
		);
		index.addDocument(doc);
		index.commit();

		var doc2 = index.getDocument("123");
		assertThat(doc2, is(notNullValue()));
		assertThat(doc2.get("id"), is("123"));
	}

	/**
	 * Writes count toward the node's write load under the bare index name,
	 * whichever generation they land in - that figure is what decides which
	 * index a node over its fair share hands over.
	 */
	@Test
	public void testWritesCountTowardTheNodeWriteLoad() throws IOException {
		var nodeState = nodeState(true);

		var path = indexRoot.resolve("books@1");
		Files.createDirectories(path);
		var index = new Index(nodeState, "books@1", path, new NoopSync());
		indexes.add(index);
		index.pull();
		index.updateDefinition(
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.newBuilder().build()).build()
						)
						.setPrimaryKey(true)
						.build()
				)
				.build()
		);

		index.addDocument(new Document(new Document.Value("id", "1")));
		index.addDocument(new Document(new Document.Value("id", "2")));

		assertThat(nodeState.writeLoad("books"), closeTo(2d, 0.01));
		assertThat(nodeState.writeLoad("games"), is(0d));
	}

	@Test
	public void testAddDocumentWithMissingRequiredField() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"required_field",
					FieldDef.newBuilder()
						.setRequired(true)
						.setType(
							FieldTypeDef.newBuilder()
								.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
								.build()
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);

		var doc = new Document();
		assertThrows(ValidationException.class, () -> {
			index.addDocument(doc);
		});
	}

	@Test
	public void testAddDocumentWithInvalidField() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"field1",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
								.build()
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);

		var doc = new Document(
			new Document.Value("non_existent_field", true)
		);
		assertThrows(ValidationException.class, () -> {
			index.addDocument(doc);
		});
	}

	@Test
	public void testAddDocumentWithSeveralValuesForSingleValuedField() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"tag",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
								.build()
						)
						.build()
				)
		);

		var doc = new Document(
			new Document.Value("tag", "nature"),
			new Document.Value("tag", "classic")
		);
		var e = assertThrows(ValidationException.class, () -> index.addDocument(doc));
		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:not_multiple"));
	}

	@Test
	public void testAddDocumentWithSeveralValuesForMultipleField() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
								.build()
						)
						.setPrimaryKey(true)
						.build()
				)
				.putFields(
					"tag",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
								.build()
						)
						.setMultiple(true)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "123"),
				new Document.Value("tag", "nature"),
				new Document.Value("tag", "classic")
			)
		);
		index.commit();

		var doc = index.getDocument("123");
		assertThat(doc, is(notNullValue()));
		assertThat(doc.getAll("tag"), is(List.of("nature", "classic")));
	}

	/**
	 * A locale specific field holds one variant per locale, so a single valued
	 * one still takes a value per translation - what it refuses is a second
	 * value in the same locale.
	 */
	@Test
	public void testSingleValuedLocaleFieldTakesOneValuePerLocale() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"name",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
								.build()
						)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("name", "shoes", "en"),
				new Document.Value("name", "skor", "sv")
			)
		);

		var doc = new Document(
			new Document.Value("name", "shoes", "en"),
			new Document.Value("name", "sneakers", "en")
		);
		var e = assertThrows(ValidationException.class, () -> index.addDocument(doc));
		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:not_multiple_in_locale"));
	}

	/**
	 * A wildcard pattern stands for several concrete fields, and each of them
	 * has its own count - one value each is fine, two for the same name is
	 * what a single valued pattern refuses.
	 */
	@Test
	public void testSingleValuedWildcardFieldCountsPerConcreteName() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"*",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
								.build()
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("color", "red"),
				new Document.Value("size", "42")
			)
		);

		var doc = new Document(
			new Document.Value("color", "red"),
			new Document.Value("color", "blue")
		);
		var e = assertThrows(ValidationException.class, () -> index.addDocument(doc));
		assertThat(e.getErrors().size(), is(1));
		assertThat(e.getErrors().get(0).getCode(), is("index:update:not_multiple"));
	}

	@Test
	public void testIndexStateAfterSync() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"field1",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
								.build()
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);

		var doc = new Document(
			new Document.Value("field1", true)
		);
		index.addDocument(doc);
		assertThat(index.getState(), is(IndexState.MODIFIED));

		index.commit();
		assertThat(index.getState(), is(IndexState.USABLE));
	}

	/**
	 * An index whose definition needs something this build does not have is
	 * left closed. Opening it would index and answer without whatever is
	 * missing, which looks like it worked.
	 */
	@Test
	public void testIndexNeedingUnknownFeaturesIsNotOpened() throws IOException {
		var path = indexRoot.resolve("test");
		Files.createDirectories(path);

		var definition = IndexDef.newBuilder()
			.putFields(
				"field1",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.addRequiredFeatures("type.boolean")
			.addRequiredFeatures("field.geo")
			.build();

		Files.write(path.resolve(Index.DEFINITION_FILE), definition.toByteArray());

		var index = new Index(nodeState(true), "test", path, new NoopSync());
		indexes.add(index);
		index.pull();

		assertThat(index.getState(), is(IndexState.UNSUPPORTED));

		assertThrows(
			IndexOutOfDateException.class,
			() -> index.addDocument(new Document(new Document.Value("field1", true)))
		);
	}

	@Test
	public void testRequiredFeaturesAreStoredWithTheDefinition() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"title",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);

		assertThat(
			index.getDefinition().getRequiredFeaturesList(),
			containsInAnyOrder("index.source", "type.string", "field.filter")
		);
	}

	@Test
	public void testReadOnlyMode() throws IOException {
		var index =
			new Index(nodeState(false), "test", indexRoot.resolve("test"), new NoopSync());
		indexes.add(index);

		var doc = new Document(
			new Document.Value("field1", true)
		);
		assertThrows(IndexReadonlyException.class, () -> {
			index.addDocument(doc);
		});
	}

	/**
	 * Every way of changing an index says the same thing on a node that is not
	 * the indexer, as that is what points the caller at the node that is.
	 */
	@Test
	public void testReadOnlyModeRefusesEveryChange() throws IOException {
		var index =
			new Index(nodeState(false), "test", indexRoot.resolve("test"), new NoopSync());
		indexes.add(index);

		assertThrows(IndexReadonlyException.class, () -> index.deleteDocument("1"));
		assertThrows(
			IndexReadonlyException.class,
			() -> index.deleteByQuery(
				org.eclipse.collections.impl.factory.Lists.immutable.empty(),
				null
			)
		);
		assertThrows(IndexReadonlyException.class, () -> index.commit());
	}

	/**
	 * An index that has never been committed holds no segments, which a node
	 * that is not the indexer sees between the definition being created and the
	 * first commit reaching it. It opens as an index with nothing in it rather
	 * than failing.
	 */
	@Test
	public void testReadOnlyModeOpensAnIndexWithoutACommit() throws IOException {
		var path = indexRoot.resolve("test");
		Files.createDirectories(path);

		var index = new Index(nodeState(false), "test", path, new NoopSync());
		indexes.add(index);

		index.pull();

		assertThat(index.getState(), is(IndexState.USABLE));

		var result = index.search(SearchRequest.create().build());
		assertThat(result.total().count(), is(0L));
		assertThat(result.hits().isEmpty(), is(true));
	}

	/**
	 * Losing the indexer role reopens the index read-only, and gaining it
	 * back makes it writable again - the role can change hands without the
	 * process restarting.
	 */
	@Test
	public void testIndexFollowsTheIndexerRole() throws IOException {
		var state = nodeState(true);
		var path = indexRoot.resolve("test");
		Files.createDirectories(path);

		var index = new Index(state, "test", path, new NoopSync());
		indexes.add(index);
		index.pull();
		index.updateDefinition(
			IndexDef.newBuilder()
				.putFields(
					"field1",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
						)
						.build()
				)
				.build()
		);

		index.addDocument(new Document(new Document.Value("field1", true)));
		index.commit();

		state.updateOwnership(false);
		index.reopen();

		assertThat(index.getState(), is(IndexState.USABLE));
		assertThrows(
			IndexReadonlyException.class,
			() -> index.addDocument(new Document(new Document.Value("field1", true)))
		);

		state.updateOwnership(true);
		index.reopen();

		index.addDocument(new Document(new Document.Value("field1", false)));
		index.commit();
	}

	/**
	 * The definition a handover test writes: one boolean field, enough for a
	 * document to be indexed and counted.
	 */
	private static IndexDef oneBooleanField() {
		return IndexDef.newBuilder()
			.putFields(
				"field1",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();
	}

	/**
	 * A handover the node chose flushes first: documents that were answered
	 * but not yet committed are committed and pushed for the successor,
	 * rather than dropped by the reopen.
	 */
	@Test
	public void testHandingOverFlushesUncommittedDocuments() throws IOException {
		var state = nodeState(true);
		var path = indexRoot.resolve("test");
		Files.createDirectories(path);

		var index = new Index(state, "test", path, new NoopSync());
		indexes.add(index);
		index.pull();
		index.updateDefinition(oneBooleanField());

		index.addDocument(new Document(new Document.Value("field1", true)));

		state.updateOwnership(false);
		index.reopen(true);

		assertThat(index.getState(), is(IndexState.USABLE));
		assertThat(index.search(SearchRequest.create().build()).total().count(), is(1L));
	}

	/**
	 * Losing an index without the chance to hand it over may not push - the
	 * successor may already be writing - so the reopen drops what was never
	 * committed.
	 */
	@Test
	public void testReopeningWithoutFlushDropsUncommittedDocuments() throws IOException {
		var state = nodeState(true);
		var path = indexRoot.resolve("test");
		Files.createDirectories(path);

		var index = new Index(state, "test", path, new NoopSync());
		indexes.add(index);
		index.pull();
		index.updateDefinition(oneBooleanField());

		index.addDocument(new Document(new Document.Value("field1", true)));

		state.updateOwnership(false);
		index.reopen();

		assertThat(index.getState(), is(IndexState.USABLE));
		assertThat(index.search(SearchRequest.create().build()).total().count(), is(0L));
	}

	/**
	 * Reopening an index that is already open the way the node holds it
	 * changes nothing - an ownership change about other indexes must not roll
	 * back what this one has answered but not yet committed.
	 */
	@Test
	public void testReopeningInTheSameModeKeepsUncommittedDocuments() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"field1",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
						)
						.build()
				)
		);

		index.addDocument(new Document(new Document.Value("field1", true)));
		index.reopen();
		index.commit();

		assertThat(index.search(SearchRequest.create().build()).total().count(), is(1L));
	}

	/**
	 * Between gaining an index and the reopen that gives it a writer, a write
	 * is refused as out of date rather than reaching the writer that is not
	 * there - the caller retries and finds the index writable.
	 */
	@Test
	public void testGainedIndexRefusesWritesUntilReopened() throws IOException {
		var state = new NodeState(true);
		var path = indexRoot.resolve("test");
		Files.createDirectories(path);

		var index = new Index(state, "test", path, new NoopSync());
		indexes.add(index);
		index.pull();

		assertThat(index.getState(), is(IndexState.USABLE));

		state.updateOwnership("test", true);
		assertThrows(
			IndexOutOfDateException.class,
			() -> index.addDocument(new Document(new Document.Value("field1", true)))
		);

		index.reopen();
		index.updateDefinition(oneBooleanField());
		index.addDocument(new Document(new Document.Value("field1", true)));
		index.commit();
	}

	@Test
	public void testMultipleFieldValues() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"tags",
					FieldDef.newBuilder()
						.setMultiple(true)
						.setType(
							FieldTypeDef.newBuilder()
								.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
								.build()
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);

		var doc = new Document(
			new Document.Value("tags", true),
			new Document.Value("tags", false)
		);
		index.addDocument(doc);
		index.commit();
	}

	/**
	 * A pull that fails has changed nothing locally, so the index is left
	 * where the next pull can pick it up. Leaving it mid-pull would mean
	 * nothing ever tries again.
	 */
	@Test
	public void testFailedPullCanBeRetried() throws IOException {
		var path = indexRoot.resolve("test");
		Files.createDirectories(path);

		var sync = new UnreachableSync();
		var index = new Index(nodeState(true), "test", path, sync);
		indexes.add(index);

		index.pull();
		assertThat(index.getState(), is(IndexState.NEEDS_PULL));

		sync.reachable = true;
		index.pull();
		assertThat(index.getState(), is(IndexState.USABLE));
	}

	/**
	 * A read-only node can be asked for an index before anything has been
	 * indexed into it, leaving it with nothing to open. The first commit is
	 * picked up by a later pull even though nothing about the local copy
	 * changed in between.
	 */
	@Test
	public void testReadOnlyIndexWithNothingToOpenCanBePulledAgain() throws IOException {
		var path = indexRoot.resolve("test");
		Files.createDirectories(path);

		var reader = new Index(nodeState(false), "test", path, new NoopSync());
		indexes.add(reader);

		reader.pull();
		assertThat(reader.getState(), is(IndexState.USABLE));

		// The indexer creates the index the read-only node was asked for
		var indexer = new Index(nodeState(true), "test", path, new NoopSync());
		indexer.pull();
		indexer.updateDefinition(
			IndexDef.newBuilder()
				.putFields(
					"field1",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
						)
						.build()
				)
				.build()
		);
		indexer.close();

		reader.pull();
		assertThat(reader.getState(), is(IndexState.USABLE));
		assertThat(reader.getDefinition().containsFields("field1"), is(true));
	}

	/**
	 * Closing is final for an instance: repeating it changes nothing, and a
	 * pull arriving afterwards - a stale refresh, a caller that held on to the
	 * instance - does not bring it back.
	 */
	@Test
	public void testCloseCanBeRepeated() throws IOException {
		var index = create("test", IndexDef.newBuilder());

		index.close();
		index.close();
		index.pull();

		assertThat(index.getState(), is(IndexState.CLOSED));
	}

	/**
	 * A caller that held on to an instance across its close is refused with an
	 * error that says to ask for the index again, rather than failing on
	 * whatever was torn down first.
	 */
	@Test
	public void testUseAfterCloseIsRefused() throws IOException {
		var index = create("test", IndexDef.newBuilder());

		index.close();

		assertThrows(IndexClosedException.class, () -> index.getDocument("1"));
		assertThrows(
			IndexClosedException.class,
			() -> index.updateDefinition(IndexDef.getDefaultInstance())
		);
	}

	/**
	 * An index that has never been committed has not settled on a Lucene
	 * version, and saying nothing about it is the honest answer.
	 */
	@Test
	public void testLuceneVersionUnknownBeforeFirstCommit() throws IOException {
		var index = create("test");
		index.pull();

		assertThat(index.getLuceneCreatedMajor(), is(OptionalInt.empty()));
		assertThat(index.getLuceneCompatibility(), is(LuceneCompatibility.UNKNOWN));
	}

	/**
	 * Committing settles which version created the index, which is what a later
	 * build needs to know to tell whether it can still open the files.
	 */
	@Test
	public void testLuceneVersionRecordedOnFirstCommit() throws IOException {
		var index = create(
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.newBuilder().build()).build()
						)
						.setPrimaryKey(true)
						.build()
				)
		);

		index.addDocument(new Document(new Document.Value("id", "123")));
		index.commit();

		assertThat(
			index.getLuceneCreatedMajor(),
			is(OptionalInt.of(Version.LATEST.major))
		);

		/*
		 * An index this build just created is as far from the end of the window
		 * as an index gets.
		 */
		assertThat(index.getLuceneCompatibility(), is(LuceneCompatibility.CURRENT));
	}

	/**
	 * Sync that fails every pull until the remote is said to be reachable,
	 * standing in for object storage that is briefly unavailable.
	 */
	/**
	 * A claim that lapses while a commit runs is only noticed a round later,
	 * and by then a successor has taken the index over from the manifest the
	 * remote holds. Pushing anyway can still win the manifest race and leave
	 * that successor giving up documents it has already answered for, so the
	 * push asks again rather than trusting the answer the commit started from.
	 */
	@Test
	public void testCommitPushesNothingWhenTheIndexWasLostWhileItRan() throws IOException {
		var nodeState = new LapsingNodeState();
		var sync = new RecordingSync();
		var index = create("test", nodeState, sync, oneStringField(false));

		index.addDocument(new Document(new Document.Value("id", "1")));

		var pushesBefore = sync.pushes;
		nodeState.lapseAfterNextAnswer();

		assertThrows(IndexReadonlyException.class, index::commit);

		assertThat(sync.pushes, is(pushesBefore));
	}

	/**
	 * A handover this node chose is the other way round: the node has already
	 * stopped holding the index by the time the flush runs, which is the order
	 * that keeps a successor from pulling before the flush lands. What the
	 * index holds has to reach the remote regardless of what the node state
	 * says now, or every rebalance would drop the documents it answered for.
	 */
	@Test
	public void testClosingPushesEvenAfterTheIndexWasHandedOver() throws IOException {
		var nodeState = nodeState(true);
		var sync = new RecordingSync();
		var index = create("test", nodeState, sync, oneStringField(false));

		index.addDocument(new Document(new Document.Value("id", "1")));

		var pushesBefore = sync.pushes;

		// The listener hears about the loss before the flush is asked for
		nodeState.updateOwnership(false);
		index.close(true);

		assertThat(sync.pushes, is(pushesBefore + 1));
	}

	/**
	 * Node state standing for a claim that lapses while a commit is running.
	 * The index is still held the first time it is asked after
	 * {@link #lapseAfterNextAnswer()}, which is the answer the commit starts
	 * from, and gone every time after that.
	 */
	private static final class LapsingNodeState extends NodeState {
		private boolean arming;
		private boolean lapsed;

		LapsingNodeState() {
			super(true);
			updateOwnership(true);
		}

		void lapseAfterNextAnswer() {
			this.arming = true;
		}

		@Override
		public boolean isIndexer(String index) {
			if(lapsed) {
				return false;
			}

			if(arming) {
				this.arming = false;
				this.lapsed = true;
				return true;
			}

			return super.isIndexer(index);
		}
	}

	/**
	 * Counts what reached the remote, for telling a push that was made from
	 * one that was refused.
	 */
	private static class RecordingSync extends NoopSync {
		int pushes;

		@Override
		public void push(Set<String> files) throws IOException {
			pushes++;
			super.push(files);
		}
	}

	private static class UnreachableSync implements StateSync {
		boolean reachable;

		@Override
		public boolean pull() throws IOException {
			if(!reachable) {
				throw new IOException("simulated pull failure");
			}

			return false;
		}

		@Override
		public void push(Set<String> files) throws IOException {
		}

		@Override
		public OptionalInt luceneCreatedMajor() {
			return OptionalInt.empty();
		}

		@Override
		public OptionalLong syncedVersion() {
			return OptionalLong.empty();
		}
	}
}
