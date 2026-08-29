package se.l4.exofind.engine.index.decompound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds where a language allows a word to break, from a list of Liang
 * hyphenation patterns - the pattern format of TeX, which is where the
 * shipped grammars come from.
 *
 * A pattern is letters with digits between them, {@code 6t1b2}: matched
 * everywhere it occurs in the word, each digit bids for the position it sits
 * at, the highest bid at a position wins, and an odd winner is a break. The
 * {@code .} in a pattern anchors it to the start or end of the word.
 *
 * Built once from its patterns and read-only from there on, so an instance is
 * safe to use from several threads.
 */
public final class Hyphenator {
	/**
	 * A node of the pattern trie. The values of a pattern whose letters end
	 * at this node sit on the node; a digit at position {@code i} of a
	 * pattern is the bid for the gap before letter {@code i} of its key.
	 */
	private static final class Node {
		final Map<Character, Node> children = new HashMap<>();
		int[] values;
	}

	private final Node root = new Node();

	public Hyphenator(List<String> patterns) {
		for(var pattern : patterns) {
			add(pattern);
		}
	}

	private void add(String pattern) {
		var key = new StringBuilder(pattern.length());
		var values = new int[pattern.length() + 1];

		for(var i = 0; i < pattern.length(); i++) {
			var c = pattern.charAt(i);
			if(c >= '0' && c <= '9') {
				values[key.length()] = c - '0';
			} else {
				key.append(c);
			}
		}

		var node = root;
		for(var i = 0; i < key.length(); i++) {
			node = node.children.computeIfAbsent(key.charAt(i), c -> new Node());
		}

		/*
		 * The same letters can occur in several source files with different
		 * digits; the highest bid per position is kept, which is also what
		 * merging the patterns as text would mean.
		 */
		if(node.values == null) {
			node.values = new int[key.length() + 1];
		}
		for(var i = 0; i <= key.length() && i < values.length; i++) {
			node.values[i] = Math.max(node.values[i], values[i]);
		}
	}

	/**
	 * Get the positions the word may break at, in order, each between one
	 * and {@code length - 1}. The word is matched as it is - the patterns
	 * are lowercase, so the word has to be too.
	 *
	 * @param word
	 *   buffer holding the word
	 * @param length
	 *   the length of the word within the buffer
	 * @return
	 */
	List<Integer> breaks(char[] word, int length) {
		/*
		 * The word is wrapped in the anchor character before matching, so a
		 * pattern written against the start or end of a word finds it.
		 */
		var wrapped = new char[length + 2];
		wrapped[0] = '.';
		System.arraycopy(word, 0, wrapped, 1, length);
		wrapped[length + 1] = '.';

		var values = new int[wrapped.length + 1];
		for(var start = 0; start < wrapped.length; start++) {
			var node = root;
			for(var i = start; i < wrapped.length; i++) {
				node = node.children.get(wrapped[i]);
				if(node == null) {
					break;
				}
				if(node.values != null) {
					for(var v = 0; v < node.values.length; v++) {
						values[start + v] = Math.max(values[start + v], node.values[v]);
					}
				}
			}
		}

		var breaks = new ArrayList<Integer>();
		for(var position = 1; position < length; position++) {
			// Gap i of the original word is gap i + 1 of the wrapped one
			if((values[position + 1] & 1) == 1) {
				breaks.add(position);
			}
		}
		return breaks;
	}
}
