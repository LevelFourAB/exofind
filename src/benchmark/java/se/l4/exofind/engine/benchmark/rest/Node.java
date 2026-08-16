package se.l4.exofind.engine.benchmark.rest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The node the API benchmarks send their requests to.
 *
 * <p>A node is a separate process, started and stopped outside the benchmark -
 * measuring a server from inside the JVM running it would have the two compete
 * for the same cores. What is measured here is therefore a round trip: the
 * request written, sent, answered and read back.
 *
 * <p>Every call throws on a status outside the 2xx range, so a benchmark stops
 * at the first request the node refuses rather than timing an error page.
 *
 * <p>Instances are safe for concurrent use.
 */
public final class Node {
	private final HttpClient client;
	private final URI base;
	private final String key;

	/**
	 * @param base
	 *   where the node answers, such as {@code http://localhost:8080}
	 * @param key
	 *   the credential to send as a bearer token, or empty for a node that
	 *   checks none
	 */
	public Node(String base, String key) {
		this.client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
		this.base = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
		this.key = key;
	}

	/**
	 * Send a body to a path and return what came back.
	 *
	 * @throws IllegalStateException
	 *   if the node answers with a status outside 2xx, carrying the body it
	 *   answered with
	 * @throws UncheckedIOException
	 *   if the request could not be sent or the answer could not be read
	 */
	public String post(String path, String contentType, String body) {
		return send("POST", path, contentType, body);
	}

	/**
	 * Replace what is at a path with a body. Errors are as {@link #post}.
	 */
	public String put(String path, String contentType, String body) {
		return send("PUT", path, contentType, body);
	}

	private String send(String method, String path, String contentType, String body) {
		var request = HttpRequest.newBuilder(base.resolve(path))
			.method(method, HttpRequest.BodyPublishers.ofString(body))
			.header("Content-Type", contentType);

		if(!key.isEmpty()) {
			request = request.header("Authorization", "Bearer " + key);
		}

		HttpResponse<String> response;
		try {
			response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for " + path, e);
		}

		if(response.statusCode() / 100 != 2) {
			throw new IllegalStateException(
				method + " " + path + " answered " + response.statusCode() + ": "
					+ response.body()
			);
		}

		return response.body();
	}
}
