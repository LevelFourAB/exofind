package se.l4.exofind.engine.logging;

/**
 * One key-value pair on its way out, carried as a parameter of the log record
 * so that {@link LogFieldJsonProvider} can write it as a field of its own.
 *
 * <p>Rendered as {@code key=value} by {@link #toString()}, which is what a
 * formatter that has no notion of fields falls back to.
 */
record LogField(String key, Object value) {
	@Override
	public String toString() {
		return key + "=" + value;
	}
}
