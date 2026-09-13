package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyListResponse;
import se.l4.exofind.engine.auth.AuthMode;
import se.l4.exofind.engine.auth.Grant;
import se.l4.exofind.engine.auth.InMemoryKeyStorage;
import se.l4.exofind.engine.auth.Keys;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * The key listing, read through the resource. What a key allows and how it
 * is stored is {@code KeysTest}; this holds the shape of the listing.
 */
public class KeyResourceTest {
	Keys keys;
	KeyResource resource;

	@BeforeEach
	void setup() {
		keys = new Keys(
			new InMemoryKeyStorage(),
			AuthMode.KEYS,
			Optional.of("root-credential-for-the-test"),
			Optional.empty(),
			Duration.ofSeconds(30)
		);
		resource = new KeyResource(keys);
	}

	/**
	 * Key IDs are generated, so the pages are checked against the order the
	 * whole listing answers in rather than against names chosen up front.
	 */
	@Test
	public void testListIsPagedByLimitAndAfter() {
		for(var i = 0; i < 3; i++) {
			keys.create("key " + i, Lists.immutable.of(grant()), null);
		}

		var all = ids(resource.list(null, null, null));
		assertThat(all.size(), is(3));

		var first = resource.list(null, null, "2");
		assertThat(ids(first), contains(all.get(0), all.get(1)));
		assertThat(first.next(), is(all.get(1)));

		var second = resource.list(null, first.next(), "2");
		assertThat(ids(second), contains(all.get(2)));
		assertThat(second.next(), is(nullValue()));
	}

	@Test
	public void testListKeepsThePrefixAsked() {
		keys.create("a key", Lists.immutable.of(grant()), null);
		keys.create("another key", Lists.immutable.of(grant()), null);

		var all = ids(resource.list(null, null, null));
		var wanted = all.get(0);

		var listed = ids(resource.list(wanted.substring(0, 4), null, null));
		assertThat(listed.contains(wanted), is(true));
		assertThat(
			listed.stream().allMatch(id -> id.startsWith(wanted.substring(0, 4))),
			is(true)
		);
	}

	/**
	 * The node configuration is answered on every page: a caller reading the
	 * second page still learns whether a root key exists.
	 */
	@Test
	public void testEveryPageCarriesTheNodeConfiguration() {
		keys.create("a key", Lists.immutable.of(grant()), null);
		keys.create("another key", Lists.immutable.of(grant()), null);

		var first = resource.list(null, null, "1");
		var second = resource.list(null, first.next(), "1");

		assertThat(first.rootKeyConfigured(), is(true));
		assertThat(second.rootKeyConfigured(), is(true));
	}

	@Test
	public void testListWithAnInvalidLimitIsRefused() {
		var e = assertThrows(ValidationException.class, () -> resource.list(null, null, "0"));
		assertThat(e.getMessage(), containsString("request:limit_out_of_range"));
	}

	private static Grant grant() {
		return new Grant(Sets.immutable.of(Permission.SEARCH), Lists.immutable.of("books"));
	}

	private static List<String> ids(KeyListResponse listed) {
		return listed.keys().stream().map(KeyInfo::id).toList();
	}
}
