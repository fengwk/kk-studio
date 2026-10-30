package fun.fengwk.kkstudio.web.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 用真实 Boot MVC 容器验证官方 tracing 装配：W3C 继承、MDC、无外部导出、server span 与唯一收尾日志。
 *
 * <p>测试显式清空 {@code SpringApplication} 的 initializers，避免 convention {@code TracerInitializer}
 * 在测试中生效； 这不改变生产启动路径，只证明新配置可以独立工作。
 */
class TracingWiringIntegrationTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
  private static final String TRACE_PARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";

  private static ConfigurableApplicationContext context;
  private static int port;
  private static ListAppender<ILoggingEvent> traceAppender;

  @BeforeAll
  static void startApplication() {
    SpringApplication application = new SpringApplication(ProbeApplication.class);
    // 显式隔离 convention 的 SpringApplicationInitializer；生产启动保持原路径，待数据切片合并后删除旧 starter。
    application.setInitializers(List.of());
    context =
        application.run(
            "--server.port=0",
            "--spring.main.banner-mode=off",
            "--logging.level.root=warn",
            "--spring.autoconfigure.exclude="
                + String.join(",", probeExcludedAutoConfigurations()));
    port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    Logger traceLogger = (Logger) LoggerFactory.getLogger("TRACE");
    traceAppender = new ListAppender<>();
    traceAppender.setContext(traceLogger.getLoggerContext());
    traceAppender.start();
    traceLogger.addAppender(traceAppender);
  }

  @AfterAll
  static void stopApplication() {
    if (traceAppender != null) {
      ((Logger) LoggerFactory.getLogger("TRACE")).detachAppender(traceAppender);
    }
    if (context != null) {
      context.close();
    }
  }

  @Test
  void propagatesW3cTraceAndWritesStandardMdc() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/tracing/probe?secret=query"))
                .header("traceparent", TRACE_PARENT)
                .header("X-Secret-Header", "header-secret")
                .header("baggage", "secret=baggage-secret")
                .GET()
                .build());

    assertEquals(200, response.statusCode());
    Map<String, String> fields = fields(response.body());
    assertEquals(TRACE_ID, fields.get("traceId"));
    assertNotEquals(PARENT_SPAN_ID, fields.get("spanId"));
    assertEquals(fields.get("traceId"), fields.get("mdcTraceId"));
    assertEquals(fields.get("spanId"), fields.get("mdcSpanId"));
  }

  @Test
  void startsANewW3cTraceWithoutLegacyHeaders() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/tracing/probe"))
                .header("X-Trace-ID", "legacy-trace")
                .header("X-Span-ID", "legacy-span")
                .GET()
                .build());

    assertEquals(200, response.statusCode());
    Map<String, String> fields = fields(response.body());
    assertNotEquals("legacy-trace", fields.get("traceId"));
    assertEquals(fields.get("traceId"), fields.get("mdcTraceId"));
    assertEquals(fields.get("spanId"), fields.get("mdcSpanId"));
  }

  @Test
  void writesOneStructuredCompletionLogWithoutSensitiveRequestData() throws Exception {
    traceAppender.list.clear();
    send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/tracing/probe?secret=query-secret"))
            .header("X-Secret-Header", "header-secret")
            .header("baggage", "secret=baggage-secret")
            .GET()
            .build());

    awaitTraceLog();
    assertEquals(1, traceAppender.list.size());
    String message = traceAppender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("operation=GET /tracing/probe"), message);
    assertTrue(message.contains("status=200"), message);
    assertTrue(message.matches(".*durationMs=\\d+.*"), message);
    assertFalse(message.contains("query-secret"), message);
    assertFalse(message.contains("header-secret"), message);
    assertFalse(message.contains("baggage-secret"), message);
  }

  @Test
  void logsFailedRequestAndClearsObservation() throws Exception {
    traceAppender.list.clear();
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/tracing/fail"))
                .header("traceparent", TRACE_PARENT)
                .GET()
                .build());

    assertEquals(500, response.statusCode());
    awaitTraceLog();
    assertEquals(1, traceAppender.list.size());
    String message = traceAppender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("operation=GET /tracing/fail"), message);
    assertTrue(message.contains("status=500"), message);
    assertNull(MDC.get("traceId"), "test thread must not retain request MDC");
  }

  @Test
  void usesOtelBridgeWithoutAnyExternalExporter() {
    assertNotNull(context.getBean(ContextSnapshotFactory.class));
    assertTrue(
        context.getBeansOfType(ObservationHandler.class).values().stream()
            .anyMatch(ServerRequestTraceCompletionHandler.class::isInstance),
        () -> "registered handlers: " + context.getBeansOfType(ObservationHandler.class));
    assertNotNull(context.getBean(Tracer.class));
    assertTrue(context.getBean(Tracer.class) instanceof OtelTracer);
    assertTrue(context.getBeansOfType(SpanExporter.class).isEmpty());
    assertFalse(
        ClassUtils.isPresent(
            "io.opentelemetry.exporter.otlp.http." + "trace.OtlpHttpSpanExporter",
            getClass().getClassLoader()));
  }

  private static List<String> probeExcludedAutoConfigurations() {
    try (InputStream in =
        TracingWiringIntegrationTest.class.getResourceAsStream("/tracing-probe-excludes.txt")) {
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

  private static HttpResponse<String> send(HttpRequest request) throws Exception {
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static Map<String, String> fields(String body) {
    return body.lines()
        .map(line -> line.split("=", 2))
        .filter(parts -> parts.length == 2)
        .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
  }

  private static void awaitTraceLog() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (traceAppender.list.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    // AsyncAppender 之外的普通 appender 是同步的；该 sleep 只吸收最后一次异步转发。
    Thread.sleep(50);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({ProbeController.class, TracingConfiguration.class})
  static class ProbeApplication {}

  @RestController
  @RequestMapping("/tracing")
  static class ProbeController {

    private final Tracer tracer;

    ProbeController(Tracer tracer) {
      this.tracer = tracer;
    }

    @GetMapping(value = "/probe", produces = "text/plain")
    String probe() {
      Span span = tracer.currentSpan();
      assertNotNull(span);
      return "traceId="
          + span.context().traceId()
          + "\nspanId="
          + span.context().spanId()
          + "\nmdcTraceId="
          + MDC.get("traceId")
          + "\nmdcSpanId="
          + MDC.get("spanId")
          + "\n";
    }

    @GetMapping(value = "/fail", produces = "text/plain")
    String fail() {
      throw new IllegalStateException("probe failure");
    }
  }
}
