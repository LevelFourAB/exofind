package se.l4.exofind.engine.benchmark;

/**
 * The options the JVM running a benchmark is started with, beyond what JMH
 * gives it.
 *
 * <p>JMH runs each benchmark in a JVM it forks itself, which inherits nothing
 * from the command line that started the run - so a benchmark measuring what a
 * node does has to name here whatever a node is run with. They are constants
 * because {@link org.openjdk.jmh.annotations.Fork#jvmArgsAppend()} takes
 * compile time constants, and every benchmark class appends both.
 */
public final class JvmArgs {
	/**
	 * The vector API, which Lucene uses for the distance and similarity
	 * arithmetic behind vector search when it is on the module path. Without it
	 * that arithmetic falls back to scalar code, and a benchmark of it measures
	 * something no deployment runs.
	 */
	public static final String VECTOR = "--add-modules=jdk.incubator.vector";

	/**
	 * Permission for the unnamed module to call native code, which is how
	 * Lucene reaches {@code madvise} and the other hints it gives the operating
	 * system about the files it reads.
	 */
	public static final String NATIVE_ACCESS = "--enable-native-access=ALL-UNNAMED";

	/**
	 * Turning off what the engine keeps per facet scope, for a benchmark that
	 * repeats one request against one reader. The second invocation of such a
	 * benchmark and every one after it would otherwise read the counts of the
	 * first out of a map, and the benchmark would report the cost of that
	 * lookup as the cost of counting. The per-segment caches stay on, as a node
	 * has those warm too.
	 */
	public static final String NO_FACET_SCOPE_CACHE = "-Dexofind.facets.scope-cache=false";

	private JvmArgs() {
	}
}
