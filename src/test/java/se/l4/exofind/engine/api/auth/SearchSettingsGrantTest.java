package se.l4.exofind.engine.api.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.api.v1alpha1.admin.IndexResource;
import se.l4.exofind.engine.api.v1alpha1.admin.IndexSettingsResource;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.Int64FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.SearchSettingsDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.StringFieldDefinition;
import se.l4.exofind.engine.auth.ForbiddenException;
import se.l4.exofind.engine.auth.Grant;
import se.l4.exofind.engine.auth.Keys;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import se.l4.exofind.engine.storage.StorageMode;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/**
 * That a key held to the generations of an index cannot reach the search
 * settings of the index.
 *
 * <p>The settings endpoints accept a generation name, but the settings belong
 * to the index. A grant of {@code products@*} does not cover {@code products},
 * so it must not change the settings of {@code products} through the name
 * {@code products@2}. Each test sends a request through the filter and then to
 * the real resource.
 */
public class SearchSettingsGrantTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	SearchSettings searchSettings;
	IndexResource admin;
	IndexSettingsResource settings;
	UriInfo uriInfo;

	AuthContext context;
	AuthFilter filter;
	Keys keys;

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

		context = new AuthContext();
		context.set(Principal.unchecked());

		var reindexJobs = TestReindexJobs.create(nodeState, indexes, registry, storageDirectory);
		searchSettings = new SearchSettings(
			new InMemorySearchSettingsStorage(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			Duration.ofSeconds(10),
			Duration.ofMinutes(10)
		);

		admin = new IndexResource(
			indexes, context, new LocalIndexerOwnership(), reindexJobs, searchSettings
		);
		settings = new IndexSettingsResource(indexes, new ObjectMapper(), searchSettings);

		uriInfo = mock(UriInfo.class);
		when(uriInfo.getAbsolutePath())
			.thenReturn(URI.create("http://localhost/v1alpha1/admin/indexes/products"));
		when(uriInfo.getBaseUriBuilder())
			.thenAnswer(invocation -> UriBuilder.fromUri("http://localhost/"));

		keys = mock(Keys.class);
		filter = new AuthFilter(keys, context, RequestMetrics.none());
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	/**
	 * An id that is the primary key, and a count that a ranking can sort on.
	 */
	private static IndexDefinition definition() {
		var fields = new LinkedHashMap<String, FieldDefinition>();
		fields.put(
			"id",
			new StringFieldDefinition(
				null, true, null, null, null, null,
				new FieldDefinition.Filter(),
				null, null, null, null, null, null
			)
		);
		fields.put(
			"sales",
			new Int64FieldDefinition(
				null, null, null, true, null, null,
				new FieldDefinition.Sort(null, null), null, null, null, null
			)
		);

		return new IndexDefinition(null, null, fields, null, null, null, null);
	}

	private static SearchSettingsDefinition rankBySales() {
		return new SearchSettingsDefinition(
			new IndexDefinition.Ranking(
				List.of(
					new IndexDefinition.Ranking.TieBreaker(
						"sales",
						IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING
					)
				),
				null
			),
			null,
			null,
			null
		);
	}

	private static Principal grantedOn(String pattern, Permission... permissions) {
		return new Principal(
			"0123456789abcdef",
			Lists.immutable.of(
				new Grant(Sets.immutable.of(permissions), Lists.immutable.of(pattern))
			),
			false
		);
	}

	/**
	 * Run the filter as it runs for a request to one resource method.
	 *
	 * @return
	 *   whether the filter let the request through to the resource
	 */
	private boolean filter(Method endpoint, Principal principal, String name) {
		when(keys.resolve(any())).thenReturn(principal);

		var resourceInfo = mock(ResourceInfo.class);
		when(resourceInfo.getResourceMethod()).thenReturn(endpoint);
		filter.resourceInfo = resourceInfo;

		var parameters = new MultivaluedHashMap<String, String>();
		parameters.putSingle("name", name);

		var requestUri = mock(UriInfo.class);
		when(requestUri.getPathParameters()).thenReturn(parameters);

		var request = mock(ContainerRequestContext.class);
		when(request.getUriInfo()).thenReturn(requestUri);

		try {
			filter.filter(request);
			return true;
		} catch(IndexNotFoundException | ForbiddenException e) {
			return false;
		}
	}

	private static Method method(String name, Class<?>... parameters) {
		try {
			return IndexSettingsResource.class.getMethod(name, parameters);
		} catch(NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void aKeyConfinedToGenerationsCannotReplaceTheSettingsOfTheIndex() {
		// Given an index with a second generation and no settings
		admin.put("products", null, null, false, uriInfo, definition());
		admin.put("products@2", null, null, false, uriInfo, definition());

		// And a key granted settings.write on the generations alone
		var principal = grantedOn("products@*", Permission.SETTINGS_WRITE);

		// When the key replaces the settings through a generation name
		var endpoint = method("put", String.class, String.class, SearchSettingsDefinition.class);
		if(filter(endpoint, principal, "products@2")) {
			settings.put("products@2", null, rankBySales());
		}

		// Then the index still has no settings
		assertThat(searchSettings.read("products").isPresent(), is(false));
	}

	@Test
	void aKeyConfinedToGenerationsCannotRemoveTheSettingsOfTheIndex() {
		// Given an index with a second generation and settings
		admin.put("products", null, null, false, uriInfo, definition());
		admin.put("products@2", null, null, false, uriInfo, definition());
		settings.put("products", null, rankBySales());

		// And a key granted settings.write on the generations alone
		var principal = grantedOn("products@*", Permission.SETTINGS_WRITE);

		// When the key removes the settings through a generation name
		var endpoint = method("delete", String.class);
		if(filter(endpoint, principal, "products@2")) {
			settings.delete("products@2");
		}

		// Then the index keeps its settings
		assertThat(searchSettings.read("products").isPresent(), is(true));
	}

	@Test
	void aKeyOnTheIndexAndItsGenerationsCanReplaceTheSettingsThroughAGeneration() {
		// Given an index with a second generation and no settings
		admin.put("products", null, null, false, uriInfo, definition());
		admin.put("products@2", null, null, false, uriInfo, definition());

		// And a key granted settings.write on the index and its generations
		var principal = grantedOn("products*", Permission.SETTINGS_WRITE);

		// When the key replaces the settings through a generation name
		var endpoint = method("put", String.class, String.class, SearchSettingsDefinition.class);
		assertThat(filter(endpoint, principal, "products@2"), is(true));
		settings.put("products@2", null, rankBySales());

		// Then the index has the settings
		assertThat(searchSettings.read("products").isPresent(), is(true));
	}

	@Test
	void everySettingsEndpointIsCheckedAgainstTheWholeIndex() {
		/*
		 * A new settings endpoint that leaves out the flag opens the same path
		 * again, and nothing else reports it.
		 */
		for(var endpoint : IndexSettingsResource.class.getMethods()) {
			var required = endpoint.getAnnotation(RequiresPermission.class);
			if(required == null) continue;

			assertThat(endpoint.getName(), required.wholeIndex(), is(true));
		}
	}
}
