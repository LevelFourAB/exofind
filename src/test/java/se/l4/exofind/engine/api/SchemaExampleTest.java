package se.l4.exofind.engine.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;

/**
 * The examples the OpenAPI document shows for a whole body, read back into the
 * model they are shown for.
 *
 * <p>An example is prose as far as the compiler is concerned, so a property
 * that is renamed or dropped leaves the example describing a request the
 * engine now rejects. Reading each one with unknown properties refused turns
 * that into a build failure.
 *
 * <p>Two places carry one: the {@link Schema} of a model, which a generated
 * client reads, and the {@link org.eclipse.microprofile.openapi.annotations.media.ExampleObject}
 * of a {@link RequestBody}, which the published pages show. The single values
 * on record components are left to the model they belong to.
 */
public class SchemaExampleTest {
	/**
	 * A plain mapper, which refuses unknown properties. An example naming a
	 * property its model no longer has fails to read.
	 */
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	public void everyExampleOfABodyIsOneTheModelAccepts() throws Exception {
		var failures = new ArrayList<String>();

		for(var example : examples()) {
			if(writeOnly(example.type())) {
				failures.addAll(unknownNames(example));
				continue;
			}

			try {
				mapper.readValue(example.json(), example.type());
			} catch(InvalidDefinitionException e) {
				failures.addAll(unknownNames(example));
			} catch(Exception e) {
				failures.add(example.where() + ": " + e.getMessage());
			}
		}

		if(!failures.isEmpty()) {
			throw new AssertionError(
				"These examples are not bodies their model accepts:\n  "
					+ String.join("\n  ", failures)
			);
		}
	}

	@Test
	public void theModelExamplesAreActuallyBeingLookedAt() throws Exception {
		assertThat(
			"No model carries a `@Schema` example, so half of this test checks nothing",
			examples().stream().filter(e -> e.where().startsWith("@Schema")).count(),
			greaterThan(0L)
		);
	}

	@Test
	public void theRequestBodyExamplesAreActuallyBeingLookedAt() throws Exception {
		assertThat(
			"No endpoint carries an `@ExampleObject`, so half of this test checks nothing",
			examples().stream().filter(e -> e.where().startsWith("@ExampleObject")).count(),
			greaterThan(0L)
		);
	}

	private List<Example> examples() throws Exception {
		var examples = new ArrayList<Example>();

		for(var type : ApiEndpoints.classes()) {
			var schema = type.getAnnotation(Schema.class);

			if(schema == null) {
				continue;
			}

			for(var json : schema.examples()) {
				examples.add(new Example(
					"@Schema of " + type.getSimpleName(),
					mapper.getTypeFactory().constructType(type),
					json
				));
			}
		}

		for(var endpoint : ApiEndpoints.endpoints()) {
			examples.addAll(bodyExamples(endpoint));
			examples.addAll(responseExamples(endpoint));
		}

		return examples;
	}

	/**
	 * The examples declared on the responses of one endpoint, each paired with
	 * the type the response is written from.
	 */
	private List<Example> responseExamples(Method endpoint) {
		var examples = new ArrayList<Example>();

		for(var response : endpoint.getAnnotationsByType(APIResponse.class)) {
			for(var content : response.content()) {
				for(var example : content.examples()) {
					examples.add(new Example(
						"@ExampleObject `" + example.name() + "` of "
							+ ApiEndpoints.describe(endpoint) + " "
							+ response.responseCode(),
						bodyType(content, endpoint.getGenericReturnType()),
						example.value()
					));
				}
			}
		}

		return examples;
	}

	/**
	 * The examples declared on the request body of one endpoint, each paired
	 * with the type the body is read as.
	 */
	private List<Example> bodyExamples(Method endpoint) {
		var examples = new ArrayList<Example>();

		for(var parameter : endpoint.getParameters()) {
			var body = parameter.getAnnotation(RequestBody.class);

			if(body == null) {
				continue;
			}

			for(var content : body.content()) {
				for(var example : content.examples()) {
					examples.add(new Example(
						"@ExampleObject `" + example.name() + "` of "
							+ ApiEndpoints.describe(endpoint),
						bodyType(content, parameter.getParameterizedType()),
						example.value()
					));
				}
			}
		}

		return examples;
	}

	/**
	 * Whether a model reaches the wire in a shape it cannot be read back
	 * from. A component that carries its own serializer says so: the engine
	 * type behind it is written as something else entirely, and the example
	 * shows what a client receives. The search response says it several
	 * records down, so the whole tree is walked.
	 */
	private boolean writeOnly(JavaType type) {
		return writeOnly(type, new HashSet<>());
	}

	private boolean writeOnly(JavaType type, Set<Class<?>> seen) {
		var raw = type.getRawClass();

		if(!seen.add(raw)) {
			return false;
		}

		for(var i = 0; i < type.containedTypeCount(); i++) {
			if(writeOnly(type.containedType(i), seen)) {
				return true;
			}
		}

		var components = raw.getRecordComponents();

		if(components == null) {
			return false;
		}

		for(var component : components) {
			if(find(raw, component, JsonSerialize.class) != null) {
				return true;
			}

			var held = mapper.getTypeFactory().constructType(component.getGenericType());

			if(writeOnly(held, seen)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * An annotation of a record component, wherever the compiler put it. An
	 * annotation that does not name {@code RECORD_COMPONENT} among its targets
	 * lands on the field and the accessor instead, and is invisible on the
	 * component itself.
	 */
	private static <A extends Annotation> A find(
		Class<?> model,
		RecordComponent component,
		Class<A> type
	) {
		var onComponent = component.getAnnotation(type);

		if(onComponent != null) {
			return onComponent;
		}

		var onAccessor = component.getAccessor().getAnnotation(type);

		if(onAccessor != null) {
			return onAccessor;
		}

		try {
			return model.getDeclaredField(component.getName()).getAnnotation(type);
		} catch(NoSuchFieldException e) {
			return null;
		}
	}

	/**
	 * The properties an example names that its model does not have, as
	 * failures. Only the outermost object is checked. A renamed property shows
	 * up there.
	 */
	private List<String> unknownNames(Example example) {
		var model = example.type().getRawClass();
		var components = model.getRecordComponents();

		if(components == null) {
			return List.of();
		}

		var known = Stream.of(components)
			.map(component -> {
				var property = find(model, component, JsonProperty.class);
				return property == null ? component.getName() : property.value();
			})
			.collect(Collectors.toSet());

		var failures = new ArrayList<String>();

		try {
			var root = mapper.readTree(example.json());

			root.fieldNames().forEachRemaining(name -> {
				if(!known.contains(name)) {
					failures.add(example.where() + ": `" + name + "` is not a property of "
						+ model.getSimpleName());
				}
			});
		} catch(Exception e) {
			failures.add(example.where() + ": " + e.getMessage());
		}

		return failures;
	}

	/**
	 * The type an example is read as: the one the content names, falling back
	 * to the type the endpoint receives the body as.
	 */
	private JavaType bodyType(Content content, java.lang.reflect.Type received) {
		var declared = content.schema().implementation();

		return mapper.getTypeFactory().constructType(
			declared == Void.class ? received : declared
		);
	}

	/**
	 * One example, the type it is read as, and where it was declared.
	 */
	private record Example(String where, JavaType type, String json) {
	}
}
