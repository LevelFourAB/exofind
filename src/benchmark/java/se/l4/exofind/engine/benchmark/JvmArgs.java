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

	private JvmArgs() {
	}
}
