package fun.fengwk.kkstudio.web.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证官方 context propagation 在真实 OTel span 上的跨线程行为。
 *
 * <p>覆盖预热线程池、任务结束清理、空上下文 clearMissing、嵌套 scope 恢复与虚拟线程；不依赖 MDC 字符串伪造链路。
 */
class TracingContextPropagationTest {

  private static ConfigurableApplicationContext context;
  private static Tracer tracer;
  private static ContextSnapshotFactory contextSnapshotFactory;

  @BeforeAll
  static void startTracingContext() {
    SpringApplication application = new SpringApplication(PropagationProbeApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    context =
        application.run(
            "--spring.main.banner-mode=off",
            "--spring.autoconfigure.exclude="
                + String.join(",", probeExcludedAutoConfigurations()));
    tracer = context.getBean(Tracer.class);
    contextSnapshotFactory = context.getBean(ContextSnapshotFactory.class);
  }

  @AfterAll
  static void stopTracingContext() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void propagatesRealSpanAndStandardMdcIntoPrestartedWorkerThenCleansUp() throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      // 先预热 worker，证明传播依赖显式 wrap 而不是线程创建时继承。
      worker.submit(() -> null).get(5, TimeUnit.SECONDS);
      ExecutorService wrapped = ContextExecutorService.wrap(worker, contextSnapshotFactory);

      Span span = tracer.nextSpan().name("propagation-origin").start();
      String traceId;
      String spanId;
      try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
        traceId = span.context().traceId();
        spanId = span.context().spanId();
        assertEquals(traceId, MDC.get("traceId"));
        assertEquals(spanId, MDC.get("spanId"));
        String[] taskContext =
            wrapped
                .submit(
                    () ->
                        new String[] {
                          tracer.currentSpan().context().traceId(),
                          tracer.currentSpan().context().spanId(),
                          MDC.get("traceId"),
                          MDC.get("spanId")
                        })
                .get(5, TimeUnit.SECONDS);
        assertEquals(traceId, taskContext[0]);
        assertEquals(spanId, taskContext[1]);
        assertEquals(traceId, taskContext[2]);
        assertEquals(spanId, taskContext[3]);
      } finally {
        span.end();
      }

      assertNull(MDC.get("traceId"), "origin scope close must clear MDC");
      assertNull(wrapped.submit(() -> MDC.get("traceId")).get(5, TimeUnit.SECONDS));
      assertNull(wrapped.submit(() -> tracer.currentSpan()).get(5, TimeUnit.SECONDS));
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void clearMissingRemovesStaleWorkerContextForEmptySubmission() throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      worker.submit(() -> null).get(5, TimeUnit.SECONDS);
      // 模拟前一个未清理任务在 worker 上留下的上下文。
      worker
          .submit(
              () -> {
                MDC.put("traceId", "stale-trace");
                MDC.put("spanId", "stale-span");
              })
          .get(5, TimeUnit.SECONDS);

      ExecutorService wrapped = ContextExecutorService.wrap(worker, contextSnapshotFactory);
      String[] stale =
          wrapped
              .submit(
                  () ->
                      new String[] {
                        MDC.get("traceId"), MDC.get("spanId"), String.valueOf(tracer.currentSpan())
                      })
              .get(5, TimeUnit.SECONDS);
      assertNull(stale[0]);
      assertNull(stale[1]);
      assertEquals("null", stale[2]);
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void nestedScopeRestoresChildScopeAfterTaskReturns() throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      worker.submit(() -> null).get(5, TimeUnit.SECONDS);
      ExecutorService wrapped = ContextExecutorService.wrap(worker, contextSnapshotFactory);

      Span root = tracer.nextSpan().name("nested-root").start();
      try (Tracer.SpanInScope rootScope = tracer.withSpan(root)) {
        Span child = tracer.nextSpan().name("nested-child").start();
        try (Tracer.SpanInScope childScope = tracer.withSpan(child)) {
          String[] taskContext =
              wrapped
                  .submit(
                      () ->
                          new String[] {tracer.currentSpan().context().spanId(), MDC.get("spanId")})
                  .get(5, TimeUnit.SECONDS);
          assertEquals(child.context().spanId(), taskContext[0]);
          assertEquals(child.context().spanId(), taskContext[1]);
          assertEquals(child.context().spanId(), tracer.currentSpan().context().spanId());
          assertEquals(child.context().spanId(), MDC.get("spanId"));
        }
        assertEquals(root.context().spanId(), tracer.currentSpan().context().spanId());
        assertEquals(root.context().spanId(), MDC.get("spanId"));
      } finally {
        root.end();
      }
      assertNull(MDC.get("traceId"));
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void propagatesRealSpanAndMdcIntoVirtualThread() throws Exception {
    Span span = tracer.nextSpan().name("virtual-origin").start();
    AtomicReference<String[]> taskContext = new AtomicReference<>();
    try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
      ContextSnapshot snapshot = contextSnapshotFactory.captureAll();
      Thread thread =
          Thread.ofVirtual()
              .name("tracing-probe-virtual")
              .start(
                  snapshot.wrap(
                      () ->
                          taskContext.set(
                              new String[] {
                                tracer.currentSpan().context().traceId(),
                                tracer.currentSpan().context().spanId(),
                                MDC.get("traceId"),
                                MDC.get("spanId")
                              })));
      thread.join(5000);
      assertNotNull(taskContext.get());
      assertEquals(span.context().traceId(), taskContext.get()[0]);
      assertEquals(span.context().spanId(), taskContext.get()[1]);
      assertEquals(span.context().traceId(), taskContext.get()[2]);
      assertEquals(span.context().spanId(), taskContext.get()[3]);
    } finally {
      span.end();
    }
  }

  private static List<String> probeExcludedAutoConfigurations() {
    try (InputStream in =
        TracingContextPropagationTest.class.getResourceAsStream("/tracing-probe-excludes.txt")) {
      if (in == null) {
        throw new IllegalStateException("missing tracing probe exclude list");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::trim)
          .filter(line -> !line.isEmpty())
          .toList();
    } catch (IOException error) {
      throw new IllegalStateException("cannot read tracing probe exclude list", error);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(TracingConfiguration.class)
  static class PropagationProbeApplication {}
}
