package se.l4.exofind.engine;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The name the rest of the deployment knows this node by. It is the name the
 * node competes under in the leadership table, the one the record of a
 * reindex job names as the node running it, and the one the log lines about
 * the indexes it writes carry. One bean gives every use the same name, so an
 * operator reading a job record finds the node in the indexer listing.
 *
 * <p>Configured with {@code exofind.node.id}. Without one, the name is the
 * hostname with a random suffix, which is different on every start: the
 * hostname carries meaning in most deployments, and the suffix keeps two
 * nodes that happen to share one apart.
 */
@ApplicationScoped
public class NodeIdentity {
	private final String id;

	@Inject
	public NodeIdentity(
		@ConfigProperty(name = "exofind.node.id") Optional<String> configured
	) {
		this(configured.orElseGet(NodeIdentity::generate));
	}

	public NodeIdentity(String id) {
		this.id = id;
	}

	/**
	 * The name of this node.
	 */
	public String id() {
		return id;
	}

	/**
	 * The name to use when none is configured.
	 */
	private static String generate() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		} catch(UnknownHostException e) {
			host = "node";
		}

		return host + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xffffffffL);
	}
}
