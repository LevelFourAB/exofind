package se.l4.exofind.engine.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.slf4j.spi.NOPLoggingEventBuilder;

/**
 * Logger whose key-value pairs reach the log output as data rather than as
 * text.
 *
 * <p>Used through SLF4J's fluent API, and returning SLF4J's own
 * {@link LoggingEventBuilder}, so a call site reads the same as it would
 * against {@link Logger}:
 *
 * {@snippet :
 * private static final Log logger = Log.of(Indexes.class);
 *
 * logger.atInfo()
 * 	.addKeyValue("index", name)
 * 	.addKeyValue("freedBytes", size)
 * 	.log("Removed the local copy of a cold index");
 * }
 *
 * <p>With JSON logging enabled every pair becomes a field of its own - the
 * example above writes {@code index} as a string and {@code freedBytes} as a
 * number, beside a {@code message} holding only the sentence. Integral numbers
 * are the only values written as anything but a string, so a boolean arrives
 * as {@code "true"} rather than as {@code true}. With JSON logging disabled
 * the pairs are appended to the message instead, since a text log has nowhere
 * else to put them.
 *
 * <p>A field takes the name it is given, beside the names the record already
 * writes - {@code message}, {@code level}, {@code timestamp},
 * {@code loggerName}, {@code threadName} and the rest of
 * {@link org.jboss.logmanager.formatters.StructuredFormatter.Key}. Naming a
 * field after one of those writes the key twice into the same JSON object,
 * and which one a reader keeps is up to its parser.
 *
 * <p>Safe for concurrent use. A level that is not enabled costs a check and no
 * allocation.
 *
 * <p>The pairs would not survive a plain SLF4J logger. Quarkus binds SLF4J to
 * {@code slf4j-jboss-logmanager}, which does not implement
 * {@link org.slf4j.spi.LoggingEventAware}, so SLF4J's own builder flattens
 * every pair into the message string before the log record exists. Nothing
 * downstream - neither the JSON formatter nor a
 * {@link io.quarkus.logging.json.runtime.JsonProvider} - can recover them
 * afterwards, which is why going through {@link LoggerFactory} here would turn
 * the fields back into prose without failing anywhere.
 */
public final class Log {
	private final Logger logger;

	private Log(Logger logger) {
		this.logger = logger;
	}

	/**
	 * Get a logger named after {@code type}.
	 */
	public static Log of(Class<?> type) {
		return new Log(LoggerFactory.getLogger(type));
	}

	public LoggingEventBuilder atTrace() {
		return at(Level.TRACE, logger.isTraceEnabled());
	}

	public LoggingEventBuilder atDebug() {
		return at(Level.DEBUG, logger.isDebugEnabled());
	}

	public LoggingEventBuilder atInfo() {
		return at(Level.INFO, logger.isInfoEnabled());
	}

	public LoggingEventBuilder atWarn() {
		return at(Level.WARN, logger.isWarnEnabled());
	}

	public LoggingEventBuilder atError() {
		return at(Level.ERROR, logger.isErrorEnabled());
	}

	private LoggingEventBuilder at(Level level, boolean enabled) {
		return enabled
			? new LogFieldBuilder(logger, level)
			: NOPLoggingEventBuilder.singleton();
	}
}
