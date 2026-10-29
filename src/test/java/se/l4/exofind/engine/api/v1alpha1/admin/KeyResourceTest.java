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

import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyListResponse;
import se.l4.exofind.engine.auth.AuthMode;
import se.l4.exofind.engine.auth.ForbiddenException;
import se.l4.exofind.engine.auth.Grant;
import se.l4.exofind.engine.auth.InMemoryKeyStorage;
import se.l4.exofind.engine.auth.Key;
import se.l4.exofind.engine.auth.KeyNotFoundException;
import se.l4.exofind.engine.auth.Keys;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * The keys API, called through the resource. What a key allows and how it is
 * stored is {@code KeysTest}; this holds the shape of the listing and which
 * keys a caller may create, rotate and revoke.
 */
public class KeyResourceTest {
	Keys keys;
	AuthContext auth;
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
		auth = new AuthContext();
		auth.set(Principal.root());
		resource = new KeyResource(keys, auth);
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

	@Test
	public void testAKeyManagerCannotCreateAKeyThatHoldsMoreThanItDoes() {
		// Given a caller that holds only keys.write
		auth.set(Principal.of(keyManager()));

		// When it creates an admin key over every index
		var e = assertThrows(
			ForbiddenException.class,
			() -> resource.create(
				definition(new KeyDefinition.GrantDefinition("admin", null, List.of("*")))
			)
		);

		// Then the first permission it does not hold is named, and no key is stored
		assertThat(e.getMessage(), containsString("search"));
		assertThat(keys.list().size(), is(1));
	}

	@Test
	public void testAKeyManagerCanCreateAKeyThatHoldsNoMoreThanItDoes() {
		auth.set(Principal.of(keyManager()));

		var response = resource.create(
			definition(new KeyDefinition.GrantDefinition(null, List.of("keys.write"), null))
		);

		assertThat(response.getStatus(), is(201));
	}

	/**
	 * An admin key scoped to one tenant holds keys.write, and must not be able
	 * to use it to reach the indexes of another tenant.
	 */
	@Test
	public void testAScopedAdminCannotCreateAKeyOutsideItsScope() {
		auth.set(Principal.of(stored(new Grant(
			Sets.immutable.of(Permission.values()),
			Lists.immutable.of("tenant-42-*")
		))));

		assertThrows(
			ForbiddenException.class,
			() -> resource.create(
				definition(new KeyDefinition.GrantDefinition("reader", null, List.of("tenant-43-books")))
			)
		);

		var response = resource.create(
			definition(new KeyDefinition.GrantDefinition("reader", null, List.of("tenant-42-books")))
		);
		assertThat(response.getStatus(), is(201));
	}

	@Test
	public void testAKeyManagerCannotTakeOverAKeyThatHoldsMoreThanItDoes() {
		// Given an admin key over every index
		var admin = keys.create(
			"ci",
			Lists.immutable.of(new Grant(Sets.immutable.of(Permission.values()), Lists.immutable.of("*"))),
			null
		).key();

		// And a caller that holds only keys.write
		auth.set(Principal.of(keyManager()));

		// When it replaces the credential of the admin key, then it is refused
		assertThrows(ForbiddenException.class, () -> resource.rotate(admin.id()));

		// And the credential stored for the admin key is unchanged
		var stored = keys.list().detect(key -> key.id().equals(admin.id()));
		assertThat(stored.secretHash(), is(admin.secretHash()));
	}

	@Test
	public void testAKeyManagerCannotRevokeAKeyThatHoldsMoreThanItDoes() {
		var reader = keys.create("reader", Lists.immutable.of(grant()), null).key();
		auth.set(Principal.of(keyManager()));

		assertThrows(ForbiddenException.class, () -> resource.delete(reader.id()));
		assertThat(keys.list().anySatisfy(key -> key.id().equals(reader.id())), is(true));
	}

	@Test
	public void testAKeyCanRotateAndRevokeAKeyThatHoldsNoMoreThanItDoes() {
		var reader = keys.create("reader", Lists.immutable.of(grant()), null).key();
		auth.set(Principal.of(stored(new Grant(
			Sets.immutable.of(Permission.SEARCH, Permission.KEYS_WRITE),
			Lists.immutable.of("books")
		))));

		var rotated = resource.rotate(reader.id());
		assertThat(rotated.key().id(), is(reader.id()));

		assertThat(resource.delete(reader.id()).getStatus(), is(204));
	}

	@Test
	public void testRotatingAnUnknownKeyIsNotFound() {
		auth.set(Principal.of(keyManager()));

		assertThrows(KeyNotFoundException.class, () -> resource.rotate("0123456789abcdef"));
	}

	/**
	 * Store a key that holds only keys.write and get it.
	 */
	private Key keyManager() {
		return stored(new Grant(Sets.immutable.of(Permission.KEYS_WRITE), Lists.immutable.empty()));
	}

	private Key stored(Grant grant) {
		return keys.create("caller", Lists.immutable.of(grant), null).key();
	}

	private static KeyDefinition definition(KeyDefinition.GrantDefinition grant) {
		return new KeyDefinition("a key", List.of(grant), null);
	}

	private static Grant grant() {
		return new Grant(Sets.immutable.of(Permission.SEARCH), Lists.immutable.of("books"));
	}

	private static List<String> ids(KeyListResponse listed) {
		return listed.keys().stream().map(KeyInfo::id).toList();
	}
}
