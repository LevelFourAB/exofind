package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
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

		assertThat(codesOf(failure), contains("auth:key:indexes_required", "auth:key:indexes_required"));
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
