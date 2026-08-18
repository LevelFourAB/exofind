package se.l4.exofind.engine.benchmark.grouping;

/**
 * What a grouping is allowed to assume about the documents it is handed, which
 * is what decides how much it has to remember.
 */
public enum By {
	/**
	 * Nothing. A group is looked up by its identifier in a map that grows as
	 * groups turn up, and every group seen is carried until the search ends.
	 */
	KEY,

	/**
	 * That the documents of a group arrive together, so a group is finished the
	 * moment a document of another one arrives and nothing has to be carried.
	 * Only sound where the layout keeps them together.
	 */
	RUN,

	/**
	 * That the groups are numbered from zero, so a group is looked up by
	 * indexing an array rather than by hashing. What a collapse over a global
	 * ordinal map does, and it costs an array as long as there are groups per
	 * search, whether the search matched one of them or all of them.
	 */
	ORDINAL
}
