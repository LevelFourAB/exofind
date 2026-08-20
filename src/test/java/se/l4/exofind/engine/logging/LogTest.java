package se.l4.exofind.engine.logging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import io.quarkus.logging.json.runtime.JsonFormatter;
import io.quarkus.logging.json.runtime.JsonLogConfig.JsonConfig.LogFormat;

public class LogTest {
	private org.jboss.logmanager.Logger backing;
	private Capture capture;

	@BeforeEach
	void setup() {
		backing = org.jboss.logmanager.Logger.getLogger(LogTest.class.getName());
		backing.setLevel(Level.INFO);

		capture = new Capture();
		backing.addHandler(capture);
	}

	@AfterEach
	void teardown() {
		backing.removeHandler(capture);
	}

	@Test
	void fieldsReachTheRecordAsParameters() {
		var logger = Log.of(LogTest.class);

		logger.atInfo()
			.addKeyValue("index", "books@2")
			.addKeyValue("freedBytes", 12L)
			.log("Freed disk space");

		var record = capture.single();
		assertThat(record.getParameters(), is(arrayContaining(
			new LogField("index", "books@2"),
			new LogField("freedBytes", 12L)
		)));
		assertThat(record.getFormattedMessage(), startsWith("Freed disk space"));
	}

	@Test
	void causeIsKeptBesideTheFields() {
		var cause = new IllegalStateException("boom");
		var logger = Log.of(LogTest.class);

		logger.atWarn()
			.addKeyValue("index", "books@2")
			.setCause(cause)
			.log("Could not push the index");

		var record = capture.single();
		assertThat(record.getThrown(), is(sameInstance(cause)));
		assertThat(record.getParameters(), is(arrayContaining(
			new LogField("index", "books@2"),
			cause
		)));
	}

	@Test
	void anEventWithoutFieldsIsLeftAlone() {
		Log.of(LogTest.class).atInfo().log("Acquired the indexer role");

		var record = capture.single();
		assertThat(record.getFormattedMessage(), is("Acquired the indexer role"));
	}

	@Test
	void aDisabledLevelIsNotBuilt() {
		Log.of(LogTest.class).atDebug()
			.addKeyValue("index", "books@2")
			.log("Refreshed");

		assertThat(capture.records, hasSize(0));
	}

	@Test
	void fieldsBecomeJsonFieldsOfTheirOwnType() throws Exception {
		var record = new ExtLogRecord(
			Level.INFO,
			"Freed disk space",
			ExtLogRecord.FormatStyle.NO_FORMAT,
			LogTest.class.getName()
		);

		record.setParameters(new Object[] {
			new LogField("index", "books@2"),
			new LogField("freedBytes", 12L),
			new LogField("attempts", 3),
			new LogField("indexer", true),
			new LogField("idle", Duration.ofMinutes(5))
		});

		var json = format(record);

		assertThat(json, containsString("\"index\":\"books@2\""));
		assertThat(json, containsString("\"freedBytes\":12"));
		assertThat(json, containsString("\"attempts\":3"));

		/*
		 * Quoted, because the generator of the formatter has no boolean type
		 * and writes one as the string it converts to.
		 */
		assertThat(json, containsString("\"indexer\":\"true\""));
		assertThat(json, containsString("\"idle\":\"PT5M\""));
		assertThat(json, containsString("\"message\":\"Freed disk space\""));
	}

	@Test
	void aRecordWithoutFieldsIsFormattedUnchanged() throws Exception {
		var record = new ExtLogRecord(
			Level.INFO,
			"Started",
			ExtLogRecord.FormatStyle.NO_FORMAT,
			LogTest.class.getName()
		);

		assertThat(format(record), containsString("\"message\":\"Started\""));
	}

	@Test
	void aTextMessageCarriesTheFields() {
		var message = LogFieldBuilder.withFields("Freed disk space", List.of(
			new KeyValuePair("index", "books@2"),
			new KeyValuePair("freedBytes", 12L)
		));

		assertThat(message, is("Freed disk space index=books@2 freedBytes=12"));
	}

	@Test
	void aPlaceholderInAValueIsEscaped() {
		var message = LogFieldBuilder.withFields("Read the object", List.of(
			new KeyValuePair("url", "http://host/{}"),
			new KeyValuePair("index", "books@2")
		));

		assertThat(message, is("Read the object url=http://host/\\{} index=books@2"));
	}

	private static String format(ExtLogRecord record) {
		var formatter = new JsonFormatter();
		formatter.setLogFormat(LogFormat.DEFAULT);
		formatter.setExcludedKeys(Set.of());
		formatter.setDiscoveredProviders(List.of(new LogFieldJsonProvider()));

		return formatter.format(record);
	}

	private static class Capture extends Handler {
		private final List<ExtLogRecord> records = new ArrayList<>();

		@Override
		public void publish(LogRecord record) {
			records.add((ExtLogRecord) record);
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}

		ExtLogRecord single() {
			assertThat(records, hasSize(1));

			return records.get(0);
		}
	}
}
