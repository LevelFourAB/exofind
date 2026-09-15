package se.l4.exofind.engine.patch;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * That the two families of patch error codes differ only in their prefix.
 *
 * <p>A change to a document and a change to search settings are written in the
 * same path syntax, so a client that handles {@code document:patch:*} handles
 * {@code settings:patch:*} by swapping the prefix. Each family declares its own
 * codes beside the code that throws them, and this test holds the declarations
 * to each other.
 *
 * <p>The declarations are read out of the source text, the same way
 * {@code ErrorCodeFilterTest} reads them, because a code is part of the API
 * whether or not a test ever reaches the line that throws it.
 */
public class PatchErrorFamilyTest {
	/** How a code of a family is declared, with everything the two share. */
	private static final Pattern DECLARED = Pattern.compile(
		"withCode\\(\"(document|settings):patch:([a-z_]+)\"\\)"
		+ "\\s*\\.withStatus\\((\\d+)\\)"
		+ "\\s*\\.withArguments\\(([^)]*)\\)"
		+ "\\s*\\.withMessage\\(([^;]*?)\\);",
		Pattern.DOTALL
	);

	/**
	 * The codes only one family can answer with, because only one side can
	 * reach the condition:
	 *
	 * <ul>
	 *   <li>{@code settings:patch:value_invalid} reports a value the settings
	 *   model cannot hold. A document reports the same thing per field type,
	 *   as {@code document:number:value_invalid} and its siblings
	 *   <li>{@code document:patch:missing_invalid} reports the {@code missing}
	 *   query parameter of a batch of changes. Search settings take no such
	 *   parameter, and neither code is about a path
	 * </ul>
	 */
	private static final Set<String> ONE_SIDED = Set.of("value_invalid", "missing_invalid");

	@Test
	void theTwoFamiliesHoldTheSameCodes() throws Exception {
		var declared = declared();
		var documents = new TreeSet<>(declared.get("document").keySet());
		var settings = new TreeSet<>(declared.get("settings").keySet());

		documents.removeAll(ONE_SIDED);
		settings.removeAll(ONE_SIDED);

		assertThat(documents, is(not(empty())));
		assertThat(settings, is(documents));
	}

	@Test
	void aCodeReadsTheSameInBothFamilies() throws Exception {
		var declared = declared();
		var documents = declared.get("document");
		var settings = declared.get("settings");
		var differ = new ArrayList<String>();

		for(var code : documents.keySet()) {
			if(ONE_SIDED.contains(code) || !settings.containsKey(code)) {
				continue;
			}

			if(!documents.get(code).equals(settings.get(code))) {
				differ.add(
					code + " is declared as " + documents.get(code)
						+ " for a document and as " + settings.get(code)
						+ " for search settings"
				);
			}
		}

		assertThat(differ, is(empty()));
	}

	/**
	 * The status, the arguments, and the message of each code, keyed by prefix
	 * and then by the last segment of the code.
	 */
	private static Map<String, Map<String, String>> declared() throws Exception {
		var declared = new TreeMap<String, Map<String, String>>();
		declared.put("document", new TreeMap<>());
		declared.put("settings", new TreeMap<>());

		try(Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
			for(var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				var matcher = DECLARED.matcher(Files.readString(file));

				while(matcher.find()) {
					declared.get(matcher.group(1)).put(
						matcher.group(2),
						matcher.group(3)
							+ " " + matcher.group(4).replaceAll("\\s+", " ").trim()
							+ " " + matcher.group(5).replaceAll("\\s+", " ").trim()
					);
				}
			}
		}

		if(declared.get("document").isEmpty() || declared.get("settings").isEmpty()) {
			throw new IllegalStateException(
				"A family was not found in the sources, so nothing was checked"
			);
		}

		return declared;
	}
}
