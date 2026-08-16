import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Converts the upstream language resources into the files the engine ships
 * under {@code src/main/resources/decompound/}: a list of Liang hyphenation
 * patterns, and a word list of compound parts.
 *
 * Run with a subcommand naming the source format:
 *
 * <pre>
 * java Convert.java tex        patterns.txt in.tex [more.tex ...]
 * java Convert.java cor        words.txt cor.tsv
 * java Convert.java ordbank    words.txt lemma.txt fullforms.txt
 * java Convert.java saldo      words.txt saldom.xml
 * java Convert.java wikidata   words.txt lexemes.json.gz language-item
 * java Convert.java wordlist   words.txt in.txt
 * </pre>
 *
 * Each word list keeps single words of three letters or more, spelled as the
 * source spells them - the engine folds them when it loads the list. Sources
 * with part-of-speech tags are cut down to nouns, adjectives and verbs, the
 * classes compounds are built from, and verbs also contribute the stem their
 * language compounds with.
 */
public class Convert {
	public static void main(String[] args) throws IOException {
		switch(args[0]) {
			case "tex" -> tex(args);
			case "cor" -> cor(args);
			case "ordbank" -> ordbank(args);
			case "saldo" -> saldo(args);
			case "wikidata" -> wikidata(args);
			case "wordlist" -> wordlist(args);
			default -> throw new IllegalArgumentException("Unknown subcommand: " + args[0]);
		}
	}

	/**
	 * TeX hyphenation patterns to a plain list, one pattern per line, the
	 * form the engine loads. Several inputs merge into one grammar, which is
	 * how the Norwegian wrapper files - a thin file around a shared pattern
	 * file - become one artifact; any {@code \input} lines are ignored, so
	 * the shared file is named explicitly instead. The {@code \hyphenation}
	 * exception lists are typesetting corrections for single words and are
	 * left out - a split is only kept when the word list confirms the parts,
	 * which is what those corrections would otherwise guard against.
	 */
	private static void tex(String[] args) throws IOException {
		var patterns = new TreeSet<String>();

		for(var i = 2; i < args.length; i++) {
			var text = stripComments(Files.readString(Path.of(args[i]), StandardCharsets.UTF_8));

			for(var block : blocks(text, "\\patterns{")) {
				for(var token : block.split("\\s+")) {
					if(!token.isEmpty()) {
						patterns.add(token);
					}
				}
			}
		}

		if(patterns.isEmpty()) {
			throw new IllegalArgumentException("No \\patterns block found in the inputs");
		}

		try(var out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
			for(var pattern : patterns) {
				out.write(pattern);
				out.write('\n');
			}
		}

		System.out.println(args[1] + ": " + patterns.size() + " patterns");
	}

	private static String stripComments(String text) {
		var result = new StringBuilder(text.length());
		for(var line : text.split("\n", -1)) {
			var comment = line.indexOf('%');
			result.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
		}
		return result.toString();
	}

	private static List<String> blocks(String text, String opener) {
		var blocks = new ArrayList<String>();
		var from = 0;
		while(true) {
			var start = text.indexOf(opener, from);
			if(start < 0) {
				return blocks;
			}
			var end = text.indexOf('}', start);
			blocks.add(text.substring(start + opener.length(), end));
			from = end + 1;
		}
	}

	/**
	 * The Danish COR index: tab separated, the lemma in the second column
	 * and the morphological tag in the fourth. The tag starts with the part
	 * of speech - {@code sb} nouns, {@code adj}, {@code vb}.
	 */
	private static void cor(String[] args) throws IOException {
		var words = new TreeSet<String>();

		try(var reader = Files.newBufferedReader(Path.of(args[2]), StandardCharsets.UTF_8)) {
			String line;
			while((line = reader.readLine()) != null) {
				var columns = line.split("\t");
				if(columns.length < 4) {
					continue;
				}

				var lemma = columns[1];
				var tag = columns[3];
				if(tag.startsWith("sb") || tag.startsWith("adj")) {
					add(words, lemma);
				} else if(tag.startsWith("vb")) {
					add(words, lemma);
					// Danish compounds with the verb stem: løbe -> løbesko or løb
					if(lemma.endsWith("e")) {
						add(words, lemma.substring(0, lemma.length() - 1));
					}
				}
			}
		}

		write(words, args[1]);
	}

	/**
	 * Norsk ordbank: the base forms live in the lemma file, the part of
	 * speech only in the full form list, joined by lemma id. Proper nouns
	 * are tagged {@code prop} and left out. The distribution is Latin-1.
	 */
	private static void ordbank(String[] args) throws IOException {
		var posByLemma = new HashMap<String, Set<String>>();

		try(var reader = Files.newBufferedReader(Path.of(args[3]), StandardCharsets.ISO_8859_1)) {
			String line;
			while((line = reader.readLine()) != null) {
				var columns = line.split("\t");
				if(columns.length < 4) {
					continue;
				}

				var tag = columns[3].trim();
				if(tag.contains("prop")) {
					continue;
				}

				var pos = tag.split("\\s+")[0];
				posByLemma.computeIfAbsent(columns[1], key -> new HashSet<>()).add(pos);
			}
		}

		var words = new TreeSet<String>();

		try(var reader = Files.newBufferedReader(Path.of(args[2]), StandardCharsets.ISO_8859_1)) {
			String line;
			while((line = reader.readLine()) != null) {
				var columns = line.split("\t");
				if(columns.length < 3) {
					continue;
				}

				var pos = posByLemma.get(columns[1]);
				if(pos == null) {
					continue;
				}

				var lemma = columns[2];
				if(pos.contains("subst") || pos.contains("adj")) {
					add(words, lemma);
				}
				if(pos.contains("verb")) {
					add(words, lemma);
					// Norwegian compounds with the verb stem: løpe -> løp
					if(lemma.endsWith("e")) {
						add(words, lemma.substring(0, lemma.length() - 1));
					}
				}
			}
		}

		write(words, args[1]);
	}

	private static final Pattern SALDO_GF = Pattern.compile("<gf>(.*)</gf>");
	private static final Pattern SALDO_POS = Pattern.compile("<pos>(.*)</pos>");

	/**
	 * The SALDO morphology: one {@code LexicalEntry} per word, the base form
	 * in {@code gf} and the part of speech in {@code pos} - {@code nn}
	 * nouns, {@code av} adjectives, {@code vb} verbs.
	 */
	private static void saldo(String[] args) throws IOException {
		var words = new TreeSet<String>();

		try(var reader = Files.newBufferedReader(Path.of(args[2]), StandardCharsets.UTF_8)) {
			String line;
			String baseForm = null;
			while((line = reader.readLine()) != null) {
				var gf = SALDO_GF.matcher(line);
				if(gf.find()) {
					baseForm = gf.group(1);
					continue;
				}

				var pos = SALDO_POS.matcher(line);
				if(pos.find() && baseForm != null) {
					switch(pos.group(1)) {
						case "nn", "av" -> add(words, baseForm);
						case "vb" -> {
							add(words, baseForm);
							// Swedish compounds with the verb stem: hoppa -> hopp
							if(baseForm.endsWith("a")) {
								add(words, baseForm.substring(0, baseForm.length() - 1));
							}
						}
						default -> {
							// Not a class compounds are built from
						}
					}
					baseForm = null;
				}

				if(line.contains("</LexicalEntry>")) {
					baseForm = null;
				}
			}
		}

		write(words, args[1]);
	}

	/**
	 * The lexical categories compounds are built from, as Wikidata items:
	 * noun, verb, adjective.
	 */
	private static final Set<String> WIKIDATA_CATEGORIES = Set.of("Q1084", "Q24905", "Q34698");

	private static final Pattern WIKIDATA_LEMMAS = Pattern.compile(
		"\"lemmas\":\\{(.*?)\\}[,}]"
	);
	private static final Pattern WIKIDATA_VALUE = Pattern.compile("\"value\":\"((?:[^\"\\\\]|\\\\.)*)\"");

	/**
	 * A Wikidata lexeme dump: one JSON lexeme per line, filtered to the
	 * given language item ({@code Q188} is German) and the categories above.
	 * The lemma values are pulled out textually rather than through a JSON
	 * parser, which the regular shape of the dump allows.
	 */
	private static void wikidata(String[] args) throws IOException {
		var language = "\"language\":\"" + args[3] + "\"";
		var verb = "\"lexicalCategory\":\"Q24905\"";

		var words = new TreeSet<String>();

		try(var reader = new BufferedReader(new InputStreamReader(
			new GZIPInputStream(Files.newInputStream(Path.of(args[2])), 1 << 16),
			StandardCharsets.UTF_8
		))) {
			String line;
			while((line = reader.readLine()) != null) {
				if(!line.contains(language)) {
					continue;
				}

				var category = false;
				for(var item : WIKIDATA_CATEGORIES) {
					if(line.contains("\"lexicalCategory\":\"" + item + "\"")) {
						category = true;
						break;
					}
				}
				if(!category) {
					continue;
				}

				var lemmas = WIKIDATA_LEMMAS.matcher(line);
				if(!lemmas.find()) {
					continue;
				}

				var value = WIKIDATA_VALUE.matcher(lemmas.group(1));
				while(value.find()) {
					var lemma = unescape(value.group(1));
					add(words, lemma);
					// German compounds with the verb stem: laufen -> Laufschuh
					if(line.contains(verb)) {
						if(lemma.endsWith("en")) {
							add(words, lemma.substring(0, lemma.length() - 2));
						} else if(lemma.endsWith("n")) {
							add(words, lemma.substring(0, lemma.length() - 1));
						}
					}
				}
			}
		}

		write(words, args[1]);
	}

	private static String unescape(String value) {
		if(value.indexOf('\\') < 0) {
			return value;
		}

		var result = new StringBuilder(value.length());
		for(var i = 0; i < value.length(); i++) {
			var c = value.charAt(i);
			if(c != '\\') {
				result.append(c);
			} else {
				var next = value.charAt(++i);
				if(next == 'u') {
					result.append((char) Integer.parseInt(value, i + 1, i + 5, 16));
					i += 4;
				} else {
					result.append(next);
				}
			}
		}
		return result.toString();
	}

	/**
	 * A flat word list, one word per line, kept as it is beyond the shared
	 * filtering.
	 */
	private static void wordlist(String[] args) throws IOException {
		var words = new TreeSet<String>();

		try(var reader = Files.newBufferedReader(Path.of(args[2]), StandardCharsets.UTF_8)) {
			String line;
			while((line = reader.readLine()) != null) {
				add(words, line.trim());
			}
		}

		write(words, args[1]);
	}

	/**
	 * Keep a word when it can be a compound part: a single word of three
	 * letters or more, nothing but letters, and short enough that it is a
	 * part rather than a compound of its own gone unnoticed.
	 */
	private static void add(Set<String> words, String word) {
		if(word.length() < 3 || word.length() > 30) {
			return;
		}
		if(!word.chars().allMatch(Character::isLetter)) {
			return;
		}
		words.add(word);
	}

	private static void write(TreeSet<String> words, String path) throws IOException {
		try(Writer out = Files.newBufferedWriter(Path.of(path), StandardCharsets.UTF_8)) {
			for(var word : words) {
				out.write(word);
				out.write('\n');
			}
		}

		System.out.println(path + ": " + words.size() + " words");
	}
}
