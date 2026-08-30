package se.l4.exofind.engine.api;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;

/**
 * The resource methods of the compiled API, found by walking the classes
 * rather than by a list somebody has to remember to add to. What the coverage
 * tests check every endpoint for is their own; that they see every endpoint
 * is this.
 */
public final class ApiEndpoints {
	private static final List<Class<? extends Annotation>> METHODS = List.of(
		GET.class, POST.class, PUT.class, DELETE.class, PATCH.class, HEAD.class, OPTIONS.class
	);

	/**
	 * HTTP methods that read rather than change, which the routing filter
	 * serves wherever they land without asking where they run.
	 */
	private static final List<Class<? extends Annotation>> READS = List.of(
		GET.class, HEAD.class, OPTIONS.class
	);

	private ApiEndpoints() {
	}

	/**
	 * Every resource method of the compiled API.
	 */
	public static List<Method> endpoints() throws Exception {
		var endpoints = new ArrayList<Method>();

		for(var type : classes()) {
			if(type.getAnnotation(jakarta.ws.rs.Path.class) == null) {
				continue;
			}

			for(var method : type.getDeclaredMethods()) {
				if(METHODS.stream().anyMatch(m -> method.getAnnotation(m) != null)) {
					endpoints.add(method);
				}
			}
		}

		return endpoints;
	}

	/**
	 * Whether an endpoint reads rather than changes, judged by its HTTP
	 * method.
	 */
	public static boolean isRead(Method endpoint) {
		return READS.stream().anyMatch(m -> endpoint.getAnnotation(m) != null);
	}

	public static String describe(Method endpoint) {
		return endpoint.getDeclaringClass().getName() + "#" + endpoint.getName();
	}

	/**
	 * Every compiled class of the API, resources and models alike, in the
	 * order they were walked in.
	 */
	public static List<Class<?>> classes() throws Exception {
		var root = classesDirectory();
		var api = root.resolve("se/l4/exofind/engine/api");

		if(!Files.isDirectory(api)) {
			throw new IllegalStateException(
				"No compiled API classes under " + api + ", so nothing could be checked"
			);
		}

		var classes = new ArrayList<Class<?>>();
		try(Stream<Path> files = Files.walk(api)) {
			for(var file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
				var name = root.relativize(file).toString()
					.replace(java.io.File.separatorChar, '.')
					.replaceAll("\\.class$", "");

				classes.add(Class.forName(name, false, ApiEndpoints.class.getClassLoader()));
			}
		}

		return classes;
	}

	/**
	 * Where the main classes were compiled to, found from where the test
	 * classes were.
	 */
	private static Path classesDirectory() throws IOException {
		var location = ApiEndpoints.class.getProtectionDomain()
			.getCodeSource()
			.getLocation();

		var testClasses = Path.of(location.getPath());
		var classes = testClasses.resolveSibling("classes");

		if(!Files.isDirectory(classes)) {
			throw new IOException("No compiled classes next to " + testClasses);
		}

		return classes;
	}
}
