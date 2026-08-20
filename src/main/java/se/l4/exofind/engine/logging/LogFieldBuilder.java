package se.l4.exofind.engine.logging;

import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.event.KeyValuePair;
import org.slf4j.event.Level;
import org.slf4j.event.LoggingEvent;
import org.slf4j.spi.DefaultLoggingEventBuilder;

/**
 * Builder that hands the key-value pairs of an event on as parameters of the
 * log record instead of letting SLF4J flatten them into the message.
 *
 * <p>The parameters reach the record whatever the message says, because the
 * SLF4J bridge renders the message itself and then calls
 * {@code ExtLogRecord.setParameters}. {@link LogFieldJsonProvider} reads them
 * back at formatting time.
 */
final class LogFieldBuilder extends DefaultLoggingEventBuilder {
	private static volatile Boolean fieldsInMessage;

	LogFieldBuilder(Logger logger, Level level) {
		super(logger, level);
	}

	@Override
	protected void log(LoggingEvent event) {
		var fields = event.getKeyValuePairs();
		if(fields == null || fields.isEmpty()) {
			super.log(event);
			return;
		}

		var message = message(event, fields);
		var parameters = parameters(event, fields);

		switch(event.getLevel()) {
			case TRACE -> logger.trace(message, parameters);
			case DEBUG -> logger.debug(message, parameters);
			case INFO -> logger.info(message, parameters);
			case WARN -> logger.warn(message, parameters);
			case ERROR -> logger.error(message, parameters);
		}
	}

	/**
	 * Arguments the event already had, then one {@link LogField} per pair, then
	 * the cause. The order is what the formatter reads placeholders against, so
	 * the arguments have to keep the positions they were added in, and the
	 * cause has to stay last for SLF4J to recognize it as the cause rather than
	 * as an argument.
	 */
	private static Object[] parameters(LoggingEvent event, List<KeyValuePair> fields) {
		var arguments = event.getArgumentArray();
		var argumentCount = arguments == null ? 0 : arguments.length;
		var thrown = event.getThrowable();

		var parameters = new Object[argumentCount + fields.size() + (thrown == null ? 0 : 1)];
		if(arguments != null) {
			System.arraycopy(arguments, 0, parameters, 0, argumentCount);
		}

		var index = argumentCount;
		for(var field : fields) {
			parameters[index++] = new LogField(field.key, field.value);
		}

		if(thrown != null) {
			parameters[index] = thrown;
		}

		return parameters;
	}

	private static String message(LoggingEvent event, List<KeyValuePair> fields) {
		var message = fieldsInMessage()
			? withFields(event.getMessage(), fields)
			: event.getMessage();

		var markers = event.getMarkers();
		if(markers == null || markers.isEmpty()) {
			return message;
		}

		var builder = new StringBuilder();
		for(var marker : markers) {
			builder.append(marker).append(' ');
		}

		return builder.append(message).toString();
	}

	/**
	 * The message as {@code key=value} pairs appended to {@code message}, which
	 * is what a formatter with no notion of fields is left with.
	 */
	static String withFields(String message, List<KeyValuePair> fields) {
		var builder = new StringBuilder().append(message);
		for(var field : fields) {
			builder.append(' ')
				.append(field.key)
				.append('=')
				.append(escapePlaceholder(String.valueOf(field.value)));
		}

		return builder.toString();
	}

	/**
	 * Whether the message has to carry the fields as text.
	 *
	 * <p>The JSON formatter writes them as fields of their own, so a message
	 * that repeats them says everything twice. A text formatter has no notion
	 * of fields at all, so there the message is the only place they can appear
	 * and leaving them out loses them.
	 *
	 * <p>Answered from the console handler on the grounds that it is the one
	 * handler always configured, and read once. A deployment that formats its
	 * file or socket handler differently from its console gets the console's
	 * answer, which repeats the fields rather than dropping them - as does a
	 * record written before the configuration can be read.
	 */
	private static boolean fieldsInMessage() {
		var resolved = fieldsInMessage;
		if(resolved == null) {
			resolved = ! jsonEnabled();
			fieldsInMessage = resolved;
		}

		return resolved;
	}

	private static boolean jsonEnabled() {
		try {
			return ConfigProvider.getConfig()
				.getOptionalValue("quarkus.log.console.json.enabled", Boolean.class)
				.orElse(Boolean.TRUE);
		} catch(RuntimeException e) {
			return false;
		}
	}

	/*
	 * A value holding {} would be read as a placeholder by the formatter and
	 * consume the parameter carrying the next field.
	 */
	private static String escapePlaceholder(String value) {
		return value.contains("{}") ? value.replace("{}", "\\{}") : value;
	}
}
