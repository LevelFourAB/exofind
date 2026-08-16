package se.l4.exofind.engine.api.v1alpha1.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;

import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.KeyInfo;
import se.l4.exofind.engine.auth.Grant;
import se.l4.exofind.engine.auth.Key;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.auth.Role;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * Mapping between the key requests and responses of the API and the keys the
 * engine stores.
 *
 * <p>Roles are expanded here, so what reaches the store is permissions alone.
 * Everything a key can say is checked before it is created - a name that stands
 * for nothing would otherwise be stored and grant nothing, which looks the same
 * as a key that works until the day it is needed.
 */
public final class KeyDefinitionMapper {
	private static final ErrorType GRANTS_REQUIRED =
		ErrorType.withCode("auth:key:grants_required")
			.withMessage("A key needs at least one grant, one with none could do nothing");

	private static final ErrorType PERMISSIONS_REQUIRED =
		ErrorType.withCode("auth:key:permissions_required")
			.withMessage("A grant needs a role or a list of permissions");

	private static final ErrorType UNKNOWN_ROLE = ErrorType.withCode("auth:key:unknown_role")
		.withArguments("role", "roles")
		.withMessage("There is no role `{{role}}`, it has to be one of {{roles}}");

	private static final ErrorType UNKNOWN_PERMISSION =
		ErrorType.withCode("auth:key:unknown_permission")
			.withArguments("permission")
			.withMessage("There is no permission `{{permission}}`");

	private static final ErrorType INDEXES_REQUIRED =
		ErrorType.withCode("auth:key:indexes_required")
			.withArguments("permission")
			.withMessage(
				"`{{permission}}` is about one index, so the grant has to say which"
					+ " indexes it covers"
			);

	private static final ErrorType INVALID_INDEX_PATTERN =
		ErrorType.withCode("auth:key:invalid_index_pattern")
			.withArguments("pattern")
			.withMessage(
				"`{{pattern}}` is not an index name or a prefix followed by `*`"
			);

	private static final ErrorType INVALID_EXPIRY = ErrorType.withCode("auth:key:invalid_expiry")
		.withArguments("value")
		.withMessage("`{{value}}` is not an ISO-8601 timestamp");

	/**
	 * A key definition that has been checked, ready to be created.
	 */
	public record Parsed(
		String description,
		ListIterable<Grant> grants,
		Instant expiresAt
	) {
	}

	private KeyDefinitionMapper() {
	}

	/**
	 * Read a definition.
	 *
	 * @param definition
	 * @return
	 * @throws ValidationException
	 *   if anything in the definition names something that does not exist, with
	 *   every problem rather than the first
	 */
	public static Parsed toEngine(KeyDefinition definition) {
		var errors = Lists.mutable.<ErrorMessage>empty();
		var root = ObjectLocation.root();

		var grants = toGrants(definition.grants(), root.forField("grants"), errors);
		if(grants.isEmpty() && errors.isEmpty()) {
			errors.add(GRANTS_REQUIRED.toMessage(root.forField("grants")));
		}

		var expiresAt = toInstant(definition.expiresAt(), root.forField("expiresAt"), errors);

		if(errors.notEmpty()) {
			throw new ValidationException(errors.toImmutable());
		}

		return new Parsed(
			definition.description() == null ? "" : definition.description(),
			grants.toImmutable(),
			expiresAt
		);
	}

	private static MutableList<Grant> toGrants(
		List<KeyDefinition.GrantDefinition> definitions,
		ObjectLocation location,
		MutableList<ErrorMessage> errors
	) {
		var grants = Lists.mutable.<Grant>empty();
		if(definitions == null) {
			return grants;
		}

		for(int i = 0; i < definitions.size(); i++) {
			var at = location.forIndex(i);
			var definition = definitions.get(i);
			var permissions = Sets.mutable.<Permission>empty();

			if(definition.role() != null) {
				var role = Role.byId(definition.role()).orElse(null);

				if(role == null) {
					errors.add(
						UNKNOWN_ROLE.toMessage(
							at.forField("role"),
							"role", definition.role(),
							"roles", Lists.immutable.of(Role.values())
								.collect(Role::id)
								.makeString(", ")
						)
					);
				} else {
					permissions.addAll(role.permissions().toSet());
				}
			}

			if(definition.permissions() != null) {
				var names = definition.permissions();
				for(int p = 0; p < names.size(); p++) {
					var permission = Permission.byId(names.get(p)).orElse(null);

					if(permission == null) {
						errors.add(
							UNKNOWN_PERMISSION.toMessage(
								at.forField("permissions").forIndex(p),
								"permission", names.get(p)
							)
						);
					} else {
						permissions.add(permission);
					}
				}
			}

			if(definition.role() == null && definition.permissions() == null) {
				errors.add(PERMISSIONS_REQUIRED.toMessage(at));
			}

			var indexes = toIndexes(definition.indexes(), at.forField("indexes"), errors);

			/*
			 * A grant of index-scoped permissions over no index allows nothing.
			 * Refused rather than stored, because the key would look right in a
			 * listing and answer every request with a refusal.
			 */
			if(indexes.isEmpty()) {
				permissions.select(p -> p.scope() == Permission.Scope.INDEX)
					.toSortedListBy(Permission::id)
					.forEach(
						permission -> errors.add(
							INDEXES_REQUIRED.toMessage(
								at.forField("indexes"),
								"permission", permission.id()
							)
						)
					);
			}

			grants.add(new Grant(permissions, indexes.toImmutable()));
		}

		return grants;
	}

	private static MutableList<String> toIndexes(
		List<String> patterns,
		ObjectLocation location,
		MutableList<ErrorMessage> errors
	) {
		var indexes = Lists.mutable.<String>empty();
		if(patterns == null) {
			return indexes;
		}

		for(int i = 0; i < patterns.size(); i++) {
			var pattern = patterns.get(i);

			/*
			 * Only a trailing `*` is a wildcard. Anything else would have to be
			 * read to know what a key reaches, and what a key reaches has to be
			 * obvious.
			 */
			if(
				pattern == null
					|| pattern.isEmpty()
					|| pattern.substring(0, pattern.length() - 1).contains("*")
			) {
				errors.add(
					INVALID_INDEX_PATTERN.toMessage(
						location.forIndex(i),
						"pattern", String.valueOf(pattern)
					)
				);

				continue;
			}

			indexes.add(pattern);
		}

		return indexes;
	}

	private static Instant toInstant(
		String value,
		ObjectLocation location,
		MutableList<ErrorMessage> errors
	) {
		if(value == null || value.isBlank()) {
			return null;
		}

		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch(DateTimeParseException e) {
			errors.add(INVALID_EXPIRY.toMessage(location, "value", value));
			return null;
		}
	}

	/**
	 * Shape a key for a response, without anything that could be used as a
	 * credential.
	 *
	 * @param key
	 * @return
	 */
	public static KeyInfo toApi(Key key) {
		var grants = key.grants()
			.collect(
				grant -> new KeyInfo.Grant(
					grant.permissions()
						.toSortedListBy(Permission::id)
						.collect(Permission::id)
						.toList(),
					grant.indexes().toList()
				)
			)
			.toList();

		return new KeyInfo(
			key.id(),
			key.description(),
			grants,
			key.createdAt().toString(),
			key.expiresAt() == null ? null : key.expiresAt().toString()
		);
	}
}
