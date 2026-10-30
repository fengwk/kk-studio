package fun.fengwk.kkstudio.web.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 服务端 HTTP observation 收尾日志。
 *
 * <p>只记录操作、状态、耗时与链路标识；不记录 query、header、payload、baggage。handler 不创建、不结束 span，也不关闭 scope，官方 tracing
 * handler 继续负责这些生命周期。
 */
final class ServerRequestTraceCompletionHandler
    implements ObservationHandler<ServerRequestObservationContext> {

  private static final Logger TRACE = LoggerFactory.getLogger("TRACE");

  private static final Object START_NANOS = new Object();

  @Override
  public void onStart(ServerRequestObservationContext context) {
    context.put(START_NANOS, System.nanoTime());
  }

  @Override
  public void onStop(ServerRequestObservationContext context) {
    HttpServletRequest request = Objects.requireNonNull(context.getCarrier(), "request");
    HttpServletResponse response = Objects.requireNonNull(context.getResponse(), "response");
    Long startNanos = context.get(START_NANOS);
    long durationMs =
        startNanos == null ? -1L : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    Span span = currentSpan(context);
    TRACE.info(
        "request operation={} status={} durationMs={} traceId={} spanId={}",
        request.getMethod() + " " + request.getRequestURI(),
        response.getStatus(),
        durationMs,
        span == null ? "-" : span.context().traceId(),
        span == null ? "-" : span.context().spanId());
  }

  @Override
  public boolean supportsContext(Observation.Context context) {
    return context instanceof ServerRequestObservationContext;
  }

  private static Span currentSpan(Observation.Context context) {
    TracingObservationHandler.TracingContext tracingContext =
        context.get(TracingObservationHandler.TracingContext.class);
    return tracingContext == null ? null : tracingContext.getSpan();
  }
}
