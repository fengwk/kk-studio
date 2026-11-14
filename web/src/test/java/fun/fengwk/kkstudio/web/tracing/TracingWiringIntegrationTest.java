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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 用真实 Boot MVC 容器验证官方 tracing 装配：W3C 继承、MDC、无外部导出、server span 与唯一收尾日志。 */
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
  void clientSuppliedTraceHeadersAreNotTrusted() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/tracing/probe"))
                .header("X-Trace-ID", "untrusted-trace")
                .header("X-Span-ID", "untrusted-span")
                .GET()
                .build());

    assertEquals(200, response.statusCode());
    Map<String, String> fields = fields(response.body());
    assertNotEquals("untrusted-trace", fields.get("traceId"));
    assertEquals(fields.get("traceId"), fields.get("mdcTraceId"));
    assertEquals(fields.get("spanId"), fields.get("mdcSpanId"));
  }

  @Test
  void writesOneStructuredCompletionLogWithoutSensitiveRequestData() throws Exception {
    String traceId = freshTraceId();
    send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/tracing/probe?secret=query-secret"))
            .header("traceparent", traceParent(traceId))
            .header("X-Secret-Header", "header-secret")
            .header("baggage", "secret=baggage-secret")
            .GET()
            .build());

    List<String> logs = awaitTraceLogs(traceId);
    assertEquals(1, logs.size(), logs::toString);
    String message = logs.get(0);
    assertTrue(message.contains("operation=GET /tracing/probe"), message);
    assertTrue(message.contains("status=200"), message);
    assertTrue(message.matches(".*durationMs=\\d+.*"), message);
    assertFalse(message.contains("query-secret"), message);
    assertFalse(message.contains("header-secret"), message);
    assertFalse(message.contains("baggage-secret"), message);
  }

  @Test
  void logsFailedRequestAndClearsObservation() throws Exception {
    String traceId = freshTraceId();
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/tracing/fail"))
                .header("traceparent", traceParent(traceId))
                .GET()
                .build());

    assertEquals(500, response.statusCode());
    List<String> logs = awaitTraceLogs(traceId);
    assertEquals(1, logs.size(), logs::toString);
    String message = logs.get(0);
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

  private static String freshTraceId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static String traceParent(String traceId) {
    return "00-" + traceId + "-" + PARENT_SPAN_ID + "-01";
  }

  /** 收尾日志在响应发出后写入，其他用例的日志可能晚到；只统计本次请求 trace 的条目。等到第一条后再稍等， 便于发现同一请求被重复记录。 */
  private static List<String> awaitTraceLogs(String traceId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (traceLogs(traceId).isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    Thread.sleep(50);
    return traceLogs(traceId);
  }

  private static List<String> traceLogs(String traceId) {
    List<ILoggingEvent> events;
    // ListAppender 在服务线程上持有自身锁追加，读取时同样加锁取快照。
    synchronized (traceAppender) {
      events = new ArrayList<>(traceAppender.list);
    }
    return events.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.contains("traceId=" + traceId))
        .toList();
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
