package se.l4.exofind.engine.api.v1alpha1.search.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.Document;

/**
 * Tests that a document serializes into the shape the API answers with - and
 * in the order the fields were given, which is why these compare written JSON
 * rather than parsed trees.
 */
public class DocumentSerializerTest {
	private static final ObjectMapper mapper = new ObjectMapper()
		.registerModule(
			new SimpleModule().addSerializer(Document.class, new DocumentSerializer())
		);

	private static String write(Document document) throws JsonProcessingException {
		return mapper.writeValueAsString(document);
	}

	@Test
	public void testScalars() throws JsonProcessingException {
		var json = write(new Document(
			new Document.Value("name", "Silent Spring"),
			new Document.Value("published", true),
			new Document.Value("pages", 368)
		));

		assertThat(json, is("""
			{"name":"Silent Spring","published":true,"pages":368}"""));
	}

	@Test
	public void testSeveralValuesBecomeAnArray() throws JsonProcessingException {
		var json = write(new Document(
			new Document.Value("tags", "nature"),
			new Document.Value("name", "Silent Spring"),
			new Document.Value("tags", "classic")
		));

		// The array gathers the values, keyed where the first one was given
		assertThat(json, is("""
			{"tags":["nature","classic"],"name":"Silent Spring"}"""));
	}

	@Test
	public void testLocalizedValuesBecomeAnObject() throws JsonProcessingException {
		var json = write(new Document(
			new Document.Value("title", "Tyst vår", "sv"),
			new Document.Value("title", "Silent Spring", "en"),
			new Document.Value("title", "Stille Frühling", "de"),
			new Document.Value("title", "Der stumme Frühling", "de")
		));

		assertThat(json, is("""
			{"title":{"sv":"Tyst vår","en":"Silent Spring",\
			"de":["Stille Frühling","Der stumme Frühling"]}}"""));
	}

	@Test
	public void testNestedDocumentsBecomeObjects() throws JsonProcessingException {
		var json = write(new Document(
			new Document.Value("author", new Document(
				new Document.Value("name", "Rachel Carson"),
				new Document.Value("born", 1907)
			)),
			new Document.Value("name", "Silent Spring")
		));

		assertThat(json, is("""
			{"author":{"name":"Rachel Carson","born":1907},"name":"Silent Spring"}"""));
	}

	@Test
	public void testNullValueIsWritten() throws JsonProcessingException {
		var json = write(new Document(
			new Document.Value("name", null)
		));

		assertThat(json, is("""
			{"name":null}"""));
	}
}
