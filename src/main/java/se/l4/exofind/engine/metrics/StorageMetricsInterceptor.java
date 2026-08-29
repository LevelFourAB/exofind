package se.l4.exofind.engine.metrics;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

/**
 * Times every request the engine makes to object storage.
 *
 * <p>Records under {@link Meters#STORAGE_OPERATION}, tagged with the operation
 * the SDK names and with the HTTP status it was answered with. A request that
 * never reached a response is tagged {@code none}.
 *
 * <p>The status is worth reading rather than only the outcome: a refused
 * conditional write is answered {@code 412}, and that is how a node learns
 * another node wrote first.
 *
 * <p>Safe for concurrent use.
 */
public class StorageMetricsInterceptor implements ExecutionInterceptor {
	private static final ExecutionAttribute<Long> STARTED =
		new ExecutionAttribute<>("exofind.metrics.started");

	private static final String TAG_STATUS = "status";
	private static final String NO_STATUS = "none";

	private final MeterRegistry registry;

	public StorageMetricsInterceptor(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void beforeExecution(
		Context.BeforeExecution context,
		ExecutionAttributes attributes
	) {
		attributes.putAttribute(STARTED, System.nanoTime());
	}

	@Override
	public void afterExecution(
		Context.AfterExecution context,
		ExecutionAttributes attributes
	) {
		record(
			attributes,
			String.valueOf(context.httpResponse().statusCode()),
			Meters.OUTCOME_SUCCESS
		);
	}

	@Override
	public void onExecutionFailure(
		Context.FailedExecution context,
		ExecutionAttributes attributes
	) {
		var status = context.httpResponse()
			.map(response -> String.valueOf(response.statusCode()))
			.orElse(NO_STATUS);

		record(attributes, status, Meters.OUTCOME_ERROR);
	}

	private void record(ExecutionAttributes attributes, String status, String outcome) {
		var started = attributes.getAttribute(STARTED);
		if(started == null) {
			return;
		}

		var operation = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);

		registry.timer(
				Meters.STORAGE_OPERATION,
				Meters.TAG_OPERATION, operation == null ? "unknown" : operation,
				Meters.TAG_OUTCOME, outcome,
				TAG_STATUS, status
			)
			.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
	}
}
