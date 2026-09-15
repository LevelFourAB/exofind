package se.l4.exofind.engine.api.errors;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A request body that stops at the size the node accepts.
 *
 * <p>Counts what is read and throws {@link RequestBodyTooLargeException} on the
 * read that would pass the limit, so the bytes past it are never handed on. The
 * failure is unchecked, so it travels out of a parser that reads the body and
 * reaches the endpoint as it is rather than as whatever that parser wraps an
 * {@link IOException} in.
 *
 * <p>Nothing here closes the connection. The caller is still sending, and
 * {@link EngineExceptionMapper} is what answers and shuts the connection down.
 */
class LimitedInputStream extends FilterInputStream {
	private final long limit;
	private long read;

	/**
	 * @param body
	 *   the body as it arrives
	 * @param limit
	 *   how many bytes the body may carry
	 */
	LimitedInputStream(InputStream body, long limit) {
		super(body);

		this.limit = limit;
	}

	@Override
	public int read() throws IOException {
		var value = in.read();

		if(value >= 0) {
			count(1);
		}

		return value;
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		var count = in.read(buffer, offset, length);

		if(count > 0) {
			count(count);
		}

		return count;
	}

	@Override
	public long skip(long count) throws IOException {
		var skipped = in.skip(count);

		if(skipped > 0) {
			count(skipped);
		}

		return skipped;
	}

	/**
	 * Add what one read took from the body, refusing the request where that
	 * puts it past the limit.
	 */
	private void count(long bytes) {
		read += bytes;

		if(read > limit) {
			throw new RequestBodyTooLargeException(limit);
		}
	}
}
