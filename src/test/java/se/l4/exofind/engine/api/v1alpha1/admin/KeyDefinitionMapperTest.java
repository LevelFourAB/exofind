package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyDefinition;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.errors.ValidationException;

public class KeyDefinitionMapperTest {
	private static KeyDefinition definition(KeyDefinition.GrantDefinition... grants) {
		return new KeyDefinition("a key", List.of(grants), null);
	}

	private static List<String> codesOf(ValidationException e) {
		return e.getErrors().collect(error -> error.getCode()).toList();
	}

	@Test
	void aRoleIsExpandedIntoThePermissionsItStandsFor() {
		var parsed = KeyDefinitionMapper.toEngine(
			definition(new KeyDefinition.GrantDefinition("reader", null, List.of("books")))
		);

		assertThat(
			parsed.grants().getFirst().permissions().toSortedListBy(Permission::id).toList(),
			contains(Permission.INDEXES_READ, Permission.SEARCH)
		);
	}

	@Test
	void permissionsAreAddedToWhateverTheRoleStandsFor() {
		var parsed = KeyDefinitionMapper.toEngine(
			definition(
				new KeyDefinition.GrantDefinition(
					"reader",
					List.of("documents.write"),
					List.of("books")
				)
			)
		);

		assertThat(
			parsed.grants().getFirst().permissions().toList(),
			containsInAnyOrder(
				Permission.SEARCH,
				Permission.INDEXES_READ,
				Permission.DOCUMENTS_WRITE
			)
		);
	}

	@Test
	void aWriterCannotChangeADefinition() {
		var parsed = KeyDefinitionMapper.toEngine(
			definition(new KeyDefinition.GrantDefinition("writer", null, List.of("*")))
		);

		var permissions = parsed.grants().getFirst().permissions();

		assertThat(permissions.contains(Permission.DOCUMENTS_WRITE), is(true));
		assertThat(permissions.contains(Permission.INDEXES_COMMIT), is(true));
		assertThat(permissions.contains(Permission.INDEXES_WRITE), is(false));
		assertThat(permissions.contains(Permission.KEYS_WRITE), is(false));
	}

	@Test
	void aKeyWithNoGrantsIsRefused() {
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(new KeyDefinition("a key", null, null))
		);

		assertThat(codesOf(failure), contains("auth:key:grants_required"));
	}

	@Test
	void aRoleThatStandsForNothingIsRefused() {
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				definition(new KeyDefinition.GrantDefinition("superuser", null, List.of("*")))
			)
		);

		assertThat(codesOf(failure), contains("auth:key:unknown_role"));
	}

	@Test
	void aPermissionThatStandsForNothingIsRefused() {
		/*
		 * Refused rather than dropped, because a key granted a name that means
		 * nothing looks right in a listing and refuses every request.
		 */
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				definition(
					new KeyDefinition.GrantDefinition(
						null,
						List.of("documents.teleport"),
						List.of("books")
					)
				)
			)
		);

		assertThat(codesOf(failure), contains("auth:key:unknown_permission"));
	}

	@Test
	void aGrantSayingNothingAboutWhatItAllowsIsRefused() {
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				definition(new KeyDefinition.GrantDefinition(null, null, List.of("books")))
			)
		);

		assertThat(codesOf(failure), contains("auth:key:permissions_required"));
	}

	@Test
	void aGrantOfIndexPermissionsOverNoIndexIsRefused() {
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				definition(new KeyDefinition.GrantDefinition("reader", null, null))
			)
		);

		/*
		 * Reader stands for two index-scoped permissions and the grant is one
		 * mistake, so it is reported once with both named.
		 */
		assertThat(codesOf(failure), contains("auth:key:indexes_required"));
		assertThat(
			failure.getErrors().getFirst().getMessage(),
			containsString("`indexes.read`, `search`")
		);
	}

	@Test
	void indexPatternsOnAGrantThatReachesNoIndexAreRefused() {
		/*
		 * Stored, the patterns would come back in every listing and read as a
		 * limit on a grant that has none.
		 */
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				definition(
					new KeyDefinition.GrantDefinition(
						null,
						List.of("keys.read", "keys.write"),
						List.of("books")
					)
				)
			)
		);

		assertThat(codesOf(failure), contains("auth:key:indexes_not_used"));
	}

	@Test
	void indexPatternsAreKeptOnAGrantThatMixesTheTwoScopes() {
		var parsed = KeyDefinitionMapper.toEngine(
			definition(
				new KeyDefinition.GrantDefinition(
					null,
					List.of("keys.read", "indexes.read"),
					List.of("books")
				)
			)
		);

		assertThat(parsed.grants().getFirst().indexes().toList(), contains("books"));
	}

	@Test
	void aGrantWithAnEmptyListOfPermissionsIsRefused() {
		/*
		 * An empty list says as little about what the key may do as no list at
		 * all, and a key that allows nothing refuses every request it is used
		 * for.
		 */
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				definition(new KeyDefinition.GrantDefinition(null, List.of(), List.of("books")))
			)
		);

		assertThat(codesOf(failure), contains("auth:key:permissions_required"));
	}

	@Test
	void aRoleNamedInAnotherCaseIsRefused() {
		/*
		 * A role name is a value a client writes, matched the same way a
		 * permission name is.
		 */
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				definition(new KeyDefinition.GrantDefinition("READER", null, List.of("books")))
			)
		);

		assertThat(codesOf(failure), contains("auth:key:unknown_role"));
	}

	@Test
	void managingKeysNeedsNoIndexes() {
		var parsed = KeyDefinitionMapper.toEngine(
			definition(
				new KeyDefinition.GrantDefinition(null, List.of("keys.write"), null)
			)
		);

		assertThat(parsed.grants().getFirst().allows(Permission.KEYS_WRITE), is(true));
	}

	@Test
	void anIndexPatternWithAStarAnywhereButTheEndIsRefused() {
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				definition(
					new KeyDefinition.GrantDefinition("reader", null, List.of("bo*ks"))
				)
			)
		);

		assertThat(codesOf(failure).contains("auth:key:invalid_index_pattern"), is(true));
	}

	@Test
	void anExpiryIsReadAsATimestamp() {
		var parsed = KeyDefinitionMapper.toEngine(
			new KeyDefinition(
				"a key",
				List.of(new KeyDefinition.GrantDefinition("reader", null, List.of("books"))),
				"2030-01-01T00:00:00Z"
			)
		);

		assertThat(parsed.expiresAt(), is(Instant.parse("2030-01-01T00:00:00Z")));
	}

	@Test
	void anExpiryThatIsNotATimestampIsRefused() {
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				new KeyDefinition(
					"a key",
					List.of(new KeyDefinition.GrantDefinition("reader", null, List.of("books"))),
					"next tuesday"
				)
			)
		);

		assertThat(codesOf(failure), contains("auth:key:invalid_expiry"));
	}

	@Test
	void anExpiryThatHasPassedIsRefused() {
		/*
		 * The credential handed back would be refused as lapsed by the very
		 * next request that presented it.
		 */
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				new KeyDefinition(
					"a key",
					List.of(new KeyDefinition.GrantDefinition("reader", null, List.of("books"))),
					"2020-01-01T00:00:00Z"
				)
			)
		);

		assertThat(codesOf(failure), contains("auth:key:expiry_in_past"));
	}

	@Test
	void everyProblemIsReportedRatherThanTheFirst() {
		var failure = assertThrows(
			ValidationException.class,
			() -> KeyDefinitionMapper.toEngine(
				new KeyDefinition(
					"a key",
					List.of(
						new KeyDefinition.GrantDefinition("superuser", null, List.of("books")),
						new KeyDefinition.GrantDefinition(
							null,
							List.of("documents.teleport"),
							List.of("movies")
						)
					),
					"next tuesday"
				)
			)
		);

		assertThat(
			codesOf(failure),
			contains(
				"auth:key:unknown_role",
				"auth:key:unknown_permission",
				"auth:key:invalid_expiry"
			)
		);
	}
}
