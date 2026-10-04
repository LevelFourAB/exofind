package se.l4.exofind.engine.api.v1alpha1.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

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
import se.l4.exofind.engine.index.IndexName;

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
			.withStatus(400)
			.withMessage("A key needs at least one grant, one with none could do nothing");

	private static final ErrorType PERMISSIONS_REQUIRED =
		ErrorType.withCode("auth:key:permissions_required")
			.withStatus(400)
			.withMessage("A grant needs a role or a list of permissions");

	private static final ErrorType UNKNOWN_ROLE = ErrorType.withCode("auth:key:role_unknown")
		.withStatus(400)
		.withArguments("role", "roles")
		.withMessage("There is no role `{{role}}`, it has to be one of {{roles}}");

	private static final ErrorType UNKNOWN_PERMISSION =
		ErrorType.withCode("auth:key:permission_unknown")
			.withStatus(400)
			.withArguments("permission")
			.withMessage("There is no permission `{{permission}}`");

	private static final ErrorType INDEXES_REQUIRED =
		ErrorType.withCode("auth:key:indexes_required")
			.withStatus(400)
			.withArguments("permissions")
			.withMessage(
				"{{permissions}} apply to one index each, so the grant has to say which"
					+ " indexes it covers"
			);

	private static final ErrorType INDEXES_NOT_USED =
		ErrorType.withCode("auth:key:indexes_unsupported")
			.withStatus(400)
			.withMessage(
				"No permission of this grant applies to one index, so `indexes` would not"
					+ " narrow anything the grant allows. Leave it out"
			);

	private static final ErrorType INVALID_INDEX_PATTERN =
		ErrorType.withCode("auth:key:index_pattern_invalid")
			.withStatus(400)
			.withArguments("pattern")
			.withMessage(
				"`{{pattern}}` is not an index name or a prefix followed by `*`"
			);

	private static final ErrorType INVALID_EXPIRY = ErrorType.withCode("auth:key:expiry_invalid")
		.withStatus(400)
		.withArguments("value")
		.withMessage("`{{value}}` is not an ISO-8601 timestamp");

	/**
	 * The latest expiry a key can have. The store holds an expiry as
	 * milliseconds since the epoch in a signed 64-bit integer.
	 */
	static final Instant LATEST_EXPIRY = Instant.ofEpochMilli(Long.MAX_VALUE);

	/** The same code as a timestamp that cannot be read, for one the store cannot hold. */
	private static final ErrorType EXPIRY_TOO_LATE = ErrorType.withCode("auth:key:expiry_invalid")
		.withStatus(400)
		.withArguments("value", "latest")
		.withMessage("`{{value}}` is later than the latest expiry a key can have, `{{latest}}`");

	private static final ErrorType EXPIRY_IN_PAST = ErrorType.withCode("auth:key:expiry_in_past")
		.withStatus(400)
		.withArguments("value")
		.withMessage(
			"`{{value}}` has already passed, so the key would be refused as lapsed from"
				+ " the moment it was created"
		);

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

			/*
			 * A `null` written where a grant goes says nothing about what the
			 * key may do, which is the same as a grant naming neither a role
			 * nor permissions.
			 */
			if(definition == null) {
				errors.add(PERMISSIONS_REQUIRED.toMessage(at));
				continue;
			}

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

			/*
			 * An empty list of permissions says as little about what the key may
			 * do as no list at all, so the two are refused alike rather than one
			 * of them creating a key that allows nothing.
			 */
			if(
				definition.role() == null
					&& (definition.permissions() == null || definition.permissions().isEmpty())
			) {
				errors.add(PERMISSIONS_REQUIRED.toMessage(at));
			}

			var indexes = toIndexes(definition.indexes(), at.forField("indexes"), errors);
			var perIndex = permissions.select(p -> p.scope() == Permission.Scope.INDEX);

			if(perIndex.notEmpty() && indexes.isEmpty()) {
				/*
				 * A grant of index-scoped permissions over no index allows
				 * nothing. Refused rather than stored, because the key would
				 * look right in a listing and answer every request with a
				 * refusal. Reported once for the grant, naming every permission
				 * it is about - one error per permission at the same path says
				 * the same thing several times over.
				 */
				errors.add(
					INDEXES_REQUIRED.toMessage(
						at.forField("indexes"),
						"permissions", perIndex.toSortedListBy(Permission::id)
							.collect(permission -> "`" + permission.id() + "`")
							.makeString(", ")
					)
				);
			} else if(
				perIndex.isEmpty()
					&& permissions.notEmpty()
					&& definition.indexes() != null
					&& !definition.indexes().isEmpty()
			) {
				/*
				 * Patterns over a grant that holds nothing they could apply to.
				 * Stored, they would come back in every listing and read as a
				 * limit on a grant that has none. Read off the definition rather
				 * than off what parsed, so a grant with an unusable pattern is
				 * told both things at once.
				 */
				errors.add(INDEXES_NOT_USED.toMessage(at.forField("indexes")));
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

			if(!canMatch(pattern)) {
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

	/**
	 * Whether a pattern is one that some index or generation name can match.
	 *
	 * <p>Only a trailing {@code *} is a wildcard. Anything else would have to
	 * be read to know what a key reaches, and what a key reaches has to be
	 * obvious. A pattern no name can match, such as one in uppercase or with
	 * a space, would give a key that looks right in a listing and is refused
	 * on every request.
	 */
	static boolean canMatch(String pattern) {
		if(pattern == null || pattern.isEmpty()) {
			return false;
		}

		if(!pattern.endsWith("*")) {
			return IndexName.tryParse(pattern).isPresent();
		}

		var prefix = pattern.substring(0, pattern.length() - 1);
		if(prefix.contains("*")) {
			return false;
		}

		var separator = prefix.indexOf(IndexName.SEPARATOR);
		if(separator < 0) {
			return isNameStart(prefix, IndexName.VALID_INDEX_PATTERN);
		}

		/*
		 * A prefix that reaches past the separator names the whole index, so
		 * that part has to be a full name. What follows is the start of a
		 * generation name.
		 */
		var index = prefix.substring(0, separator);
		var generation = prefix.substring(separator + 1);

		return IndexName.VALID_INDEX_PATTERN.matcher(index).matches()
			&& isNameStart(generation, IndexName.VALID_GENERATION_PATTERN);
	}

	/**
	 * Whether a name that the pattern accepts can start with a prefix. The
	 * empty prefix starts every name, and every other start of a name is a
	 * name itself.
	 */
	private static boolean isNameStart(String prefix, Pattern name) {
		return prefix.isEmpty() || name.matcher(prefix).matches();
	}

	private static Instant toInstant(
		String value,
		ObjectLocation location,
		MutableList<ErrorMessage> errors
	) {
		if(value == null || value.isBlank()) {
			return null;
		}

		Instant expiresAt;
		try {
			expiresAt = OffsetDateTime.parse(value).toInstant();
		} catch(DateTimeParseException e) {
			errors.add(INVALID_EXPIRY.toMessage(location, "value", value));
			return null;
		}

		if(expiresAt.isAfter(LATEST_EXPIRY)) {
			errors.add(
				EXPIRY_TOO_LATE.toMessage(location, "value", value, "latest", LATEST_EXPIRY)
			);
			return null;
		}

		/*
		 * A key is lapsed from the moment it is created once its expiry has
		 * passed, so the credential handed back would never work. Compared the
		 * way a request is checked, where an expiry exactly now has passed.
		 */
		if(!expiresAt.isAfter(Instant.now())) {
			errors.add(EXPIRY_IN_PAST.toMessage(location, "value", value));
			return null;
		}

		return expiresAt;
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
