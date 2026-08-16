package se.l4.exofind.engine.index;

import java.util.OptionalInt;

import org.apache.lucene.util.Version;

/**
 * How much longer the Lucene files of an index can be read.
 *
 * Lucene opens an index created by the current major version and the one
 * before it, and refuses anything older. An index here lives in object storage
 * for as long as it is useful, which is longer than that window, so the major
 * it was created with is recorded in the manifest and judged here - both to
 * refuse an index that has fallen out of the window, and to say so while there
 * is still time to do something about it.
 *
 * The creating major is set when the index is first written and never changes
 * afterwards, so it does not move on its own. What moves is the build reading
 * it: an index that is {@link #ENDING} today becomes {@link #UNREADABLE} the
 * moment this node is upgraded across a Lucene major, and reindexing it is the
 * only way back.
 */
public enum LuceneCompatibility {
	/**
	 * Nothing recorded which version created the index and the segments could
	 * not be asked either, which is what an index with no commit yet looks
	 * like. Says nothing about whether it can be read.
	 */
	UNKNOWN,
	/**
	 * Created by the major this build uses, so it survives the next Lucene
	 * major as well.
	 */
	CURRENT,
	/**
	 * Created by the major before this build's, which Lucene still reads. The
	 * next Lucene major drops it, so it has to be reindexed before this node is
	 * upgraded across one.
	 */
	ENDING,
	/**
	 * Created too far back for this build to open at all. Only reindexing the
	 * documents into a new index brings the data back.
	 */
	UNREADABLE;

	/**
	 * Judge a creating major version.
	 *
	 * @param createdMajor
	 *   major Lucene version the index was created with, or empty when nothing
	 *   recorded one
	 * @return
	 */
	public static LuceneCompatibility of(OptionalInt createdMajor) {
		return createdMajor.isPresent()
			? of(createdMajor.getAsInt())
			: UNKNOWN;
	}

	/**
	 * Judge a creating major version.
	 *
	 * @param createdMajor
	 *   major Lucene version the index was created with
	 * @return
	 */
	public static LuceneCompatibility of(int createdMajor) {
		if(createdMajor < Version.MIN_SUPPORTED_MAJOR) {
			return UNREADABLE;
		} else if(createdMajor == Version.MIN_SUPPORTED_MAJOR) {
			return ENDING;
		}

		return CURRENT;
	}

	/**
	 * Get whether an index in this state can be opened by this build.
	 *
	 * An unknown version reads as readable, because refusing on it would refuse
	 * every index written before the version was recorded. Lucene still has the
	 * final say when the files are opened.
	 *
	 * @return
	 */
	public boolean isReadable() {
		return this != UNREADABLE;
	}
}
