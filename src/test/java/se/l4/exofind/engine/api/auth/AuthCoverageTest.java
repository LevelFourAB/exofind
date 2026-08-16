package se.l4.exofind.engine.api.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.auth.Permission;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.PathParam;

/**
 * That every endpoint says what reaching it takes.
 *
 * <p>The filter refuses a resource method carrying no {@link RequiresPermission},
 * so forgetting one closes an endpoint rather than opening it - but it closes it
 * at the first request rather than at the build, which is late to find out. This
 * finds it here instead, by looking at what was actually compiled rather than at
 * a list somebody has to remember to add to.
 */
public class AuthCoverageTest {
	private static final List<Class<? extends Annotation>> METHODS = List.of(
		GET.class, POST.class, PUT.class, DELETE.class, PATCH.class, HEAD.class, OPTIONS.class
	);

	/**
	 * Path parameter the filter reads the index from. An endpoint about one
	 * index that named it something else would be checked against no index at
	 * all.
	 */
	private static final String INDEX_PARAMETER = "name";

	@Test
	void everyEndpointSaysWhatItRequires() throws Exception {
		var missing = new ArrayList<String>();

		for(var endpoint : endpoints()) {
			if(endpoint.getAnnotation(RequiresPermission.class) == null) {
				missing.add(describe(endpoint));
			}
		}

		assertThat(missing, is(empty()));
	}

	@Test
	void everyEndpointAboutOneIndexNamesItTheWayTheFilterReadsIt() throws Exception {
		var wrong = new ArrayList<String>();

		for(var endpoint : endpoints()) {
			var required = endpoint.getAnnotation(RequiresPermission.class);

			if(
				required == null
					|| required.anyIndex()
					|| required.value().scope() != Permission.Scope.INDEX
			) {
				continue;
			}

			if(!namesAnIndex(endpoint)) {
				wrong.add(describe(endpoint));
			}
		}

		assertThat(wrong, is(empty()));
	}

	@Test
	void theEndpointsAreActuallyBeingLookedAt() throws Exception {
		// A scan that found nothing would pass the two tests above without them
		// having checked anything
		assertThat(endpoints(), is(not(empty())));
	}

	private static boolean namesAnIndex(Method endpoint) {
		for(var annotations : endpoint.getParameterAnnotations()) {
			for(var annotation : annotations) {
				if(
					annotation instanceof PathParam parameter
						&& INDEX_PARAMETER.equals(parameter.value())
				) {
					return true;
				}
			}
		}

		return false;
	}

	private static String describe(Method endpoint) {
		return endpoint.getDeclaringClass().getName() + "#" + endpoint.getName();
	}

	/**
	 * Every resource method of the compiled API, found by walking the classes
	 * rather than by listing them here.
	 */
	private static List<Method> endpoints() throws Exception {
		var endpoints = new ArrayList<Method>();

		for(var type : apiClasses()) {
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

	private static List<Class<?>> apiClasses() throws Exception {
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

				classes.add(Class.forName(name, false, AuthCoverageTest.class.getClassLoader()));
			}
		}

		return classes;
	}

	/**
	 * Where the main classes were compiled to, found from where the test
	 * classes were.
	 */
	private static Path classesDirectory() throws IOException {
		var location = AuthCoverageTest.class.getProtectionDomain()
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
