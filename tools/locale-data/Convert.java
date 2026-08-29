import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.fst.ByteSequenceOutputs;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FSTCompiler;
import org.apache.lucene.util.fst.NoOutputs;
import org.apache.lucene.util.fst.Util;

import com.ibm.icu.text.Normalizer2;

/**
 * Converts the upstream language resources into the files the engine ships
 * under {@code locale-data/}: hyphenation grammars, word lists and the
 * lookups a locale reads instead of a stemmer Lucene ships.
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
 * java Convert.java bin-words     words.txt     SHsnid.csv
 * java Convert.java bin-stemming  stemming.txt  SHsnid.csv
 * java Convert.java bin-stopwords stopwords.txt SHsnid.csv
 * java Convert.java fst-words     words.fst     words.txt
 * java Convert.java fst-stemming  stemming.fst  stemming.txt
 * </pre>
 *
 * The {@code fst-} subcommands turn a list this converter already wrote into
 * the transducer the engine memory maps, and are the only ones needing
 * anything on the classpath - Lucene, to write the format, and ICU, to fold
 * the words the way the engine folds the tokens looked up in them. Both come
 * from the engine's own dependencies; the README says how to name them.
 *
 * Each word list keeps single words of three letters or more, spelled as the
 * source spells them - the engine folds them when it loads the list. Sources
 * with part-of-speech tags are cut down to nouns, adjectives and verbs, the
 * classes compounds are built from. What a language enters a compound as
 * decides what else is kept: a verb stem where the stem is what compounds,
 * inflected noun forms where both ends of a compound are inflected.
 *
 * A language whose stemming and stopwords come from locale data rather than
 * from Lucene has a converter per file, which is what the three {@code bin-}
 * subcommands are: one source, three of the files a data set holds.
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
			case "bin-words" -> binWords(args);
			case "bin-stemming" -> binStemming(args);
			case "bin-stopwords" -> binStopWords(args);
			case "fst-words" -> fstWords(args);
			case "fst-stemming" -> fstStemming(args);
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

	/**
	 * The word classes of BÍN a compound is built from - the three noun
	 * genders, adjectives and verbs.
	 */
	private static final Set<String> BIN_PARTS = Set.of("kk", "kvk", "hk", "lo", "so");

	/**
	 * The noun genders on their own. A compound is joined at an inflected
	 * noun and headed by one, so nouns contribute their forms and not only
	 * their dictionary form.
	 */
	private static final Set<String> BIN_NOUNS = Set.of("kk", "kvk", "hk");

	/**
	 * The closed word classes of BÍN - article, pronoun, personal pronoun,
	 * reflexive pronoun, preposition, conjunction and the infinitive marker.
	 * These are the words a stopword list is made of; the open classes carry
	 * meaning and adverbs sit close enough to them to be left in.
	 */
	private static final Set<String> BIN_CLOSED = Set.of(
		"gr", "fn", "pfn", "afn", "fs", "st", "nhm"
	);

	/**
	 * BÍN, the full form list of Icelandic: one line per word form, semicolon
	 * separated, holding the dictionary form, its identifier, the word class,
	 * the subject domain, the form itself and the grammatical tag of the form.
	 * A tag beginning {@code EF} is a genitive.
	 */
	private static void bin(String[] args, BinReader reader) throws IOException {
		try(var lines = Files.newBufferedReader(Path.of(args[2]), StandardCharsets.UTF_8)) {
			String line;
			while((line = lines.readLine()) != null) {
				var columns = line.split(";");
				if(columns.length < 6) {
					continue;
				}

				reader.accept(columns[0], columns[2], columns[4], columns[5]);
			}
		}
	}

	private interface BinReader {
		void accept(String lemma, String wordClass, String form, String tag);
	}

	/**
	 * The compound parts of Icelandic: the dictionary forms of the classes
	 * compounds are built from, and every form of a noun that is written
	 * without the definite article.
	 *
	 * Both ends of an Icelandic compound are inflected. It is joined at a
	 * genitive rather than at a bare stem - {@code tölva} enters
	 * {@code tölvupóstur} as {@code tölvu} - and its last part carries the
	 * case of the whole word, so {@code tölvupóst} ends in {@code póst}
	 * rather than in {@code póstur}. Dictionary forms alone would leave
	 * both of those in no list and the compound unsplit. The parts are
	 * reduced to their own dictionary form by the stemming that follows the
	 * split, so the inflected forms here do not reach the index.
	 *
	 * A form written with the definite article suffixed onto it is left out:
	 * it can only end a compound, and the whole word is indexed beside the
	 * parts anyway. Only nouns contribute forms - an adjective or a verb
	 * enters a compound as its dictionary form, and Icelandic adjectives
	 * inflect far enough that their forms would double the list for nothing.
	 */
	private static void binWords(String[] args) throws IOException {
		var words = new TreeSet<String>();

		bin(args, (lemma, wordClass, form, tag) -> {
			if(BIN_PARTS.contains(wordClass)) {
				add(words, lemma);
			}

			if(BIN_NOUNS.contains(wordClass) && !tag.contains("gr")) {
				add(words, form);
			}
		});

		write(words, args[1]);
	}

	/**
	 * The Icelandic word forms that differ from the form they are looked up
	 * under, written as the form, a tab and the form to answer with.
	 *
	 * A form several words share - 3% of them - is written once, and BÍN says
	 * nothing about which of those words is the more common. The noun reading
	 * is taken first, then the adjective, then the verb: an Icelandic verb is
	 * described by several times as many forms as a noun is, so the shape a
	 * collision takes is a rare form of a verb landing on a common form of a
	 * noun - {@code menn} is the plural of {@code maður} and also an
	 * imperative of the verb {@code menna}. Within a class the word BÍN
	 * describes more fully wins, which is what picks the computer
	 * {@code tölva} over the chessboard {@code talva} for {@code tölvu}, and
	 * words described equally fully are taken alphabetically.
	 */
	private static void binStemming(String[] args) throws IOException {
		var lemmas = new TreeMap<String, TreeSet<String>>();
		var readings = new HashMap<String, Reading>();

		bin(args, (lemma, wordClass, form, tag) -> {
			readings.merge(
				lemma,
				new Reading(classRank(wordClass), 1),
				(a, b) -> new Reading(Math.min(a.rank(), b.rank()), a.forms() + b.forms())
			);

			if(form.equals(lemma) || !isWord(form) || !isWord(lemma)) {
				return;
			}

			lemmas.computeIfAbsent(form, key -> new TreeSet<>()).add(lemma);
		});

		var preferred = Comparator
			.comparingInt((String lemma) -> readings.get(lemma).rank())
			.thenComparing(lemma -> readings.get(lemma).forms(), Comparator.reverseOrder())
			.thenComparing(Comparator.naturalOrder());

		try(Writer out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
			for(var entry : lemmas.entrySet()) {
				out.write(entry.getKey());
				out.write('\t');
				out.write(entry.getValue().stream().min(preferred).orElseThrow());
				out.write('\n');
			}
		}

		System.out.println(args[1] + ": " + lemmas.size() + " forms");
	}

	/**
	 * What is known about a word across the lines that describe it: the class
	 * it is preferred by and how many forms it is described with.
	 */
	private record Reading(int rank, int forms) {
	}

	private static int classRank(String wordClass) {
		if(BIN_NOUNS.contains(wordClass)) {
			return 0;
		}
		if(wordClass.equals("lo")) {
			return 1;
		}
		if(wordClass.equals("so")) {
			return 2;
		}
		return 3;
	}

	/**
	 * The Icelandic stopword list: every form of the closed word classes.
	 * BÍN lists compound prepositions and conjunctions as the several words
	 * they are written as - {@code eins og} - and those are dropped, because
	 * the list is matched against one token at a time.
	 */
	private static void binStopWords(String[] args) throws IOException {
		var words = new TreeSet<String>();

		bin(args, (lemma, wordClass, form, tag) -> {
			if(BIN_CLOSED.contains(wordClass) && isWord(form)) {
				words.add(form);
			}
		});

		write(words, args[1]);
	}

	private static boolean isWord(String value) {
		return !value.isEmpty() && value.chars().allMatch(Character::isLetter);
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

	// ------------------------------------------------------------ transducers

	/**
	 * A word list as the transducer the engine memory maps, accepting each
	 * word and carrying nothing on its arcs.
	 *
	 * The arcs are code points rather than UTF-8 bytes, which is what
	 * Lucene's own lookup over such a file walks; the two are the same file
	 * format and differ only in what a label means, so a transducer built
	 * over bytes is searched past its first non-ASCII letter and answers that
	 * the word is not there.
	 */
	private static void fstWords(String[] args) throws IOException {
		var words = new TreeSet<String>(Convert::byCodePoint);
		readLines(args[2], line -> words.add(fold(line)));

		var outputs = NoOutputs.getSingleton();
		var compiler = new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE4, outputs).build();
		var scratch = new IntsRefBuilder();

		for(var word : words) {
			compiler.add(Util.toUTF32(word, scratch), outputs.getNoOutput());
		}

		save(compiler, args[1]);
		System.out.println(args[1] + ": " + words.size() + " words");
	}

	/**
	 * A form-to-lemma lookup as the transducer the engine memory maps, with
	 * the dictionary form on the arcs as UTF-8.
	 */
	private static void fstStemming(String[] args) throws IOException {
		var lemmas = new TreeMap<String, String>(Convert::byCodePoint);

		readLines(args[2], line -> {
			var tab = line.indexOf('\t');
			if(tab <= 0 || tab == line.length() - 1) {
				return;
			}
			lemmas.putIfAbsent(fold(line.substring(0, tab)), fold(line.substring(tab + 1)));
		});

		var outputs = ByteSequenceOutputs.getSingleton();
		var compiler = new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE4, outputs).build();
		var scratch = new IntsRefBuilder();

		for(var entry : lemmas.entrySet()) {
			compiler.add(Util.toUTF32(entry.getKey(), scratch), new BytesRef(entry.getValue()));
		}

		save(compiler, args[1]);
		System.out.println(args[1] + ": " + lemmas.size() + " forms");
	}

	private static void save(FSTCompiler<?> compiler, String path) throws IOException {
		FST.fromFSTReader(compiler.compile(), compiler.getFSTReader()).save(Path.of(path));
	}

	/**
	 * Fold a word the way the analysis chain folds a token before the filter
	 * reading this data sees it. The text files are written as the language
	 * spells things and the engine folds them as it loads them; a transducer
	 * is searched by walking its arcs, so it has to be built folded instead.
	 */
	private static String fold(String value) {
		return Normalizer2.getNFKCCasefoldInstance().normalize(value);
	}

	/**
	 * Order two strings by code point, which is the order of their UTF-8
	 * bytes and so the order a transducer over those bytes has to be built in.
	 * {@link String#compareTo} orders by UTF-16 code unit and disagrees above
	 * the basic plane.
	 */
	private static int byCodePoint(String a, String b) {
		var i = 0;
		var j = 0;

		while(i < a.length() && j < b.length()) {
			var x = a.codePointAt(i);
			var y = b.codePointAt(j);
			if(x != y) {
				return Integer.compare(x, y);
			}
			i += Character.charCount(x);
			j += Character.charCount(y);
		}

		return Integer.compare(a.length() - i, b.length() - j);
	}

	/**
	 * Read a file this converter wrote, gzipped or not, so a transducer can be
	 * built either straight after the text or from the shipped data.
	 */
	private static void readLines(String path, Consumer<String> line) throws IOException {
		try(var raw = Files.newInputStream(Path.of(path))) {
			var stream = path.endsWith(".gz") ? new GZIPInputStream(raw, 1 << 16) : raw;

			try(var reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8), 1 << 16
			)) {
				String value;
				while((value = reader.readLine()) != null) {
					var trimmed = value.trim();
					if(!trimmed.isEmpty() && !trimmed.startsWith("#")) {
						line.accept(trimmed);
					}
				}
			}
		}
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
