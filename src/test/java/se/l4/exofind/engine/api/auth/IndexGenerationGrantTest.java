package se.l4.exofind.engine.api.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
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
import java.util.Optional;
import java.util.OptionalInt;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.api.v1alpha1.admin.IndexResource;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.Int64FieldDefinition;
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
 * What a key granted an index by its name alone, such as {@code products},
 * can see of the generations of that index. The key follows the index across
 * rollouts and cannot address a generation, but the index listing and the
 * index details still name the generations the index has.
 */
public class IndexGenerationGrantTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	SearchSettings searchSettings;
	IndexResource admin;
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
		if(name != null) {
			parameters.putSingle("name", name);
		}

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

	private static Method method(Class<?> type, String name, Class<?>... parameters) {
		try {
			return type.getMethod(name, parameters);
		} catch(NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The listing and the details of an index name its generations to a key
	 * granted the index alone. The key cannot address any of them.
	 */
	@Test
	void aKeyGrantedTheIndexAloneSeesItsGenerationsButCannotAddressThem() {
		// Given an index with a second generation
		admin.put("products", null, null, false, uriInfo, definition());
		admin.put("products@2", null, null, false, uriInfo, definition());

		// And a key granted indexes.read on the index name alone
		var principal = grantedOn("products", Permission.INDEXES_READ);
		var list = method(IndexResource.class, "list", String.class, String.class, String.class);
		var get = method(IndexResource.class, "get", String.class, String.class);

		// Then the key lists the index with its generations
		assertThat(filter(list, principal, null), is(true));
		var products = admin.list(null, null, null).indexes().getFirst();
		assertThat(products.name(), is("products"));
		assertThat(
			products.generations().stream().map(g -> g.name()).toList(),
			contains("1", "2")
		);

		// And it reads the index, but not a generation of it by name
		assertThat(filter(get, principal, "products"), is(true));
		assertThat(filter(get, principal, "products@2"), is(false));
	}
}
