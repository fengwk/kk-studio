package fun.fengwk.kkstudio.web.tracing;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地链路追踪的上下文装配。
 *
 * <p>span 与 observation 的生命周期完全交给 Boot/Micrometer 的官方 tracing handler；这里只准备跨 executor 的 {@link
 * ContextSnapshotFactory}，并限定 MDC 只传播标准 {@code traceId}/{@code spanId} 两个键。
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {

  @Bean
  public ContextSnapshotFactory contextSnapshotFactory(Tracer tracer) {
    ContextRegistry registry = ContextRegistry.getInstance();
    // ObservationThreadLocalAccessor 由 micrometer-observation 通过 ServiceLoader 先行注册；
    // span accessor 必须在其后，只有手工 span 没有 observation 时才接管。
    registry.registerThreadLocalAccessor(new Slf4jThreadLocalAccessor("traceId", "spanId"));
    registry.registerThreadLocalAccessor(new ObservationAwareSpanThreadLocalAccessor(tracer));
    return ContextSnapshotFactory.builder().contextRegistry(registry).clearMissing(true).build();
  }

  @Bean
  public ServerRequestTraceCompletionHandler serverRequestTraceCompletionHandler() {
    return new ServerRequestTraceCompletionHandler();
  }
}
