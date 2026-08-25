package se.l4.exofind.engine.api.v1alpha1.search;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.query.FieldSort;
import se.l4.exofind.engine.query.GeoDistanceSort;
import se.l4.exofind.engine.query.ScoreSort;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.SortKey;

/**
 * A position in the results of a search, as the opaque token callers pass
 * back to move through them.
 *
 * A token is base64url over a version byte, a kind and a payload. An
 * {@link Offset} counts results and is what numbered pages are made of; a
 * {@link Keyset} carries the sort values of the hit a window ended at, so
 * following it costs the same at any depth - which is what lets
 * {@code next}/{@code previous} keep going where offsets are capped. The two
 * are told apart by the kind byte, so a client never sees the difference.
 *
 * Every token carries a fingerprint of the effective sort it was issued
 * under. A keyset cursor is refused under a different sort, as its values
 * only name a position in the order they were taken from; an offset still
 * counts the same whatever the order, so it deliberately keeps working. The
 * query is not part of the fingerprint either way - reusing a position while
 * tweaking filters is a feature.
 */
sealed interface SearchCursor {
	byte VERSION = 1;
	byte KIND_OFFSET = 'o';
	byte KIND_KEYSET = 'k';

	/**
	 * Get the fingerprint of the effective sort the token was issued under.
	 *
	 * @return
	 */
	int fingerprint();

	/**
	 * Write this cursor as the token handed to callers.
	 *
	 * @return
	 */
	String encode();

	/**
	 * A position that counts the results before it.
	 *
	 * @param fingerprint
	 *   fingerprint of the effective sort the token was issued under
	 * @param offset
	 *   how many results the position skips
	 */
	record Offset(int fingerprint, int offset) implements SearchCursor {
		@Override
		public String encode() {
			var buffer = ByteBuffer.allocate(10)
				.put(VERSION)
				.put(KIND_OFFSET)
				.putInt(fingerprint)
				.putInt(offset);

			return toToken(buffer);
		}
	}

	/**
	 * A position keyed by the hit it sits at - its sort values and doc tie
	 * break, as {@link SortKey} holds them.
	 *
	 * @param fingerprint
	 *   fingerprint of the effective sort the token was issued under
	 * @param key
	 *   where the hit sits in the order it came back in
	 */
	record Keyset(int fingerprint, SortKey key) implements SearchCursor {
		/*
		 * A value is written as a tag byte saying what it is, then its bytes.
		 * The tags cover what a Lucene sort can report a position as.
		 */
		private static final byte VALUE_NULL = 'n';
		private static final byte VALUE_FLOAT = 'f';
		private static final byte VALUE_DOUBLE = 'd';
		private static final byte VALUE_INT = 'i';
		private static final byte VALUE_LONG = 'l';
		private static final byte VALUE_BYTES = 'b';

		@Override
		public String encode() {
			var size = 1 + 1 + 4 + 4 + 1;
			for(var value : key.values()) {
				size += 1 + switch(value) {
					case null -> 0;
					case Float f -> 4;
					case Double d -> 8;
					case Integer i -> 4;
					case Long l -> 8;
					case byte[] bytes -> 4 + bytes.length;
					default -> throw new IllegalStateException(
						"A sort reported a position this cursor can not carry: "
							+ value.getClass().getName()
					);
				};
			}

			var buffer = ByteBuffer.allocate(size)
				.put(VERSION)
				.put(KIND_KEYSET)
				.putInt(fingerprint)
				.putInt(key.doc())
				.put((byte) key.values().size());

			for(var value : key.values()) {
				switch(value) {
					case null -> buffer.put(VALUE_NULL);
					case Float f -> buffer.put(VALUE_FLOAT).putFloat(f);
					case Double d -> buffer.put(VALUE_DOUBLE).putDouble(d);
					case Integer i -> buffer.put(VALUE_INT).putInt(i);
					case Long l -> buffer.put(VALUE_LONG).putLong(l);
					case byte[] bytes -> buffer.put(VALUE_BYTES).putInt(bytes.length).put(bytes);
					default -> throw new IllegalStateException();
				}
			}

			return toToken(buffer);
		}

		private static Keyset decode(ByteBuffer buffer) {
			var fingerprint = buffer.getInt();
			var doc = buffer.getInt();
			var count = buffer.get();
			if(doc < 0 || count <= 0) {
				throw new IllegalArgumentException("Not a cursor");
			}

			var values = Lists.mutable.empty();
			for(var i = 0; i < count; i++) {
				values.add(switch(buffer.get()) {
					case VALUE_NULL -> null;
					case VALUE_FLOAT -> buffer.getFloat();
					case VALUE_DOUBLE -> buffer.getDouble();
					case VALUE_INT -> buffer.getInt();
					case VALUE_LONG -> buffer.getLong();
					case VALUE_BYTES -> {
						var length = buffer.getInt();
						if(length < 0 || length > buffer.remaining()) {
							throw new IllegalArgumentException("Not a cursor");
						}

						var bytes = new byte[length];
						buffer.get(bytes);
						yield bytes;
					}
					default -> throw new IllegalArgumentException("Not a cursor");
				});
			}

			if(buffer.hasRemaining()) {
				throw new IllegalArgumentException("Not a cursor");
			}

			return new Keyset(fingerprint, new SortKey(values.toImmutable(), doc));
		}
	}

	private static String toToken(ByteBuffer buffer) {
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(buffer.array());
	}

	/**
	 * Read a token handed back by a caller.
	 *
	 * @param token
	 * @return
	 * @throws IllegalArgumentException
	 *   when the token is not one this engine issued
	 */
	static SearchCursor decode(String token) {
		byte[] bytes = Base64.getUrlDecoder().decode(token);

		try {
			var buffer = ByteBuffer.wrap(bytes);
			if(buffer.remaining() < 2 || buffer.get() != VERSION) {
				throw new IllegalArgumentException("Not a cursor this engine can read");
			}

			return switch(buffer.get()) {
				case KIND_OFFSET -> {
					var fingerprint = buffer.getInt();
					var offset = buffer.getInt();
					if(offset < 0 || buffer.hasRemaining()) {
						throw new IllegalArgumentException("Not a cursor");
					}

					yield new Offset(fingerprint, offset);
				}

				case KIND_KEYSET -> Keyset.decode(buffer);

				default -> throw new IllegalArgumentException(
					"Not a cursor this engine can read"
				);
			};
		} catch(BufferUnderflowException e) {
			throw new IllegalArgumentException("Not a cursor");
		}
	}

	/**
	 * Fingerprint the effective sort of a search - what an empty sort means,
	 * rather than the empty list itself, so writing the default out changes
	 * nothing.
	 *
	 * @param sort
	 * @return
	 */
	static int fingerprintOf(ListIterable<? extends SortBy> sort) {
		return fingerprintOf(sort, null);
	}

	/**
	 * Fingerprint the effective sort of a search together with what its hits
	 * stand for.
	 *
	 * A search whose hits are the values of an object field keys its
	 * positions on values, so a cursor taken there names nothing among
	 * documents and the other way around - even under a sort that reads the
	 * same. The path is part of the fingerprint so cursors never cross that
	 * line, and a search whose hits are documents fingerprints exactly as it
	 * did before there was a line to cross.
	 *
	 * @param sort
	 * @param hitsPath
	 *   the object field whose values the hits stand for, or {@code null} for
	 *   hits that are documents
	 * @return
	 */
	static int fingerprintOf(ListIterable<? extends SortBy> sort, String hitsPath) {
		var description = new StringBuilder();

		if(hitsPath != null) {
			// The separator below follows, so the prefix reads `values:<path>|`
			description.append("values:").append(hitsPath);
		}

		if(sort.isEmpty()) {
			sort = Lists.immutable.<SortBy>of(ScoreSort.INSTANCE);
		}

		for(var step : sort) {
			if(!description.isEmpty()) {
				description.append('|');
			}

			description.append(step.type());
			if(step instanceof FieldSort field) {
				description.append(':').append(field.field());
			}
			if(step instanceof GeoDistanceSort geo) {
				/*
				 * The origin is part of the order - a position taken at one
				 * origin names nothing in the distances from another.
				 */
				description.append(':').append(geo.field())
					.append(':').append(geo.latitude())
					.append(':').append(geo.longitude());
			}
			description.append(':').append(step.order());
		}

		var crc = new CRC32();
		crc.update(description.toString().getBytes(StandardCharsets.UTF_8));
		return (int) crc.getValue();
	}
}
