package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tool schema、执行与 Daemon wire 契约测试。 */
class ToolContractTest {

  /** 递归 schema 校验必须拒绝缺失 required、未知字段和不匹配的数组元素。 */
  @Test
  void validatesNestedArgumentsAgainstDescriptor() {
    ToolDescriptor descriptor = descriptor();

    new ToolCall("call-1", "search", "{\"query\":\"harness\",\"formats\":[\"text\"]}")
        .validateFor(descriptor);

    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call-1", "search", "{\"formats\":[\"text\"]}").validateFor(descriptor));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "search", "{\"query\":\"x\",\"extra\":true}")
                .validateFor(descriptor));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "search", "{\"query\":\"x\",\"formats\":[\"image\"]}")
                .validateFor(descriptor));
  }

  /** 工具版本与 rendererKey 必须显式稳定。 */
  @Test
  void definesStableVersionAndRendererIdentity() {
    ToolDescriptor defaultRenderer = descriptor();
    ToolDescriptor customRenderer = descriptor("repository-search");

    assertEquals("1.0.0", defaultRenderer.version());
    assertEquals("search", defaultRenderer.rendererKey());
    assertEquals("repository-search", customRenderer.rendererKey());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolDescriptor(
                "search",
                "1.0.0",
                "Search",
                null,
                schema(),
                ToolSideEffect.READ_ONLY,
                Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolDescriptor(
                "search",
                "1.0.0",
                "Search",
                " ",
                schema(),
                ToolSideEffect.READ_ONLY,
                Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolDescriptor(
                "search", "", "Search", null, schema(), ToolSideEffect.READ_ONLY, Duration.ZERO));
  }

  /** 反射契约锁定 descriptor 只暴露模型字段及其稳定顺序，不得重新引入路由字段。 */
  @Test
  void exposesOnlyModelContractComponentsInStableOrder() {
    RecordComponent[] components = ToolDescriptor.class.getRecordComponents();

    assertEquals(
        List.of(
            "name",
            "version",
            "description",
            "rendererKey",
            "inputSchema",
            "sideEffect",
            "timeout"),
        Arrays.stream(components).map(RecordComponent::getName).toList());
    assertEquals(
        List.of(
            String.class,
            String.class,
            String.class,
            String.class,
            ToolParamsSchema.class,
            ToolSideEffect.class,
            Duration.class),
        Arrays.stream(components).map(RecordComponent::getType).toList());
  }

  /** 执行请求必须复用 descriptor 校验，并在没有覆盖时使用 descriptor 超时。 */
  @Test
  void validatesExecutionCallAndResolvesDefaultTimeout() {
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            descriptor(),
            new ToolCall("call-1", "search", "{\"query\":\"harness\"}"),
            Duration.ZERO);

    assertEquals(Duration.ofSeconds(10), request.effectiveTimeout());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                descriptor(), new ToolCall("call-1", "other", "{}"), Duration.ofSeconds(1)));
  }

  /** 5 参数 workdir 至少必须是 absolute path（canonical 化由 daemon 调用方完成，构造校验不做文件系统 IO）。 */
  @Test
  void requiresAbsoluteWorkdirWhenProvided() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                descriptor(),
                new ToolCall("call-1", "search", "{\"query\":\"harness\"}"),
                Duration.ZERO,
                null,
                Path.of("relative/workdir")));

    ToolExecutionRequest request =
        new ToolExecutionRequest(
            descriptor(),
            new ToolCall("call-1", "search", "{\"query\":\"harness\"}"),
            Duration.ZERO,
            null,
            Path.of("/absolute/workdir"));
    assertEquals(Path.of("/absolute/workdir"), request.workdir());
    assertEquals(Duration.ofSeconds(10), request.effectiveTimeout());
  }

  /** 结果 details 必须是 JSON object；空白 details 归一为 {}，非 object 拒绝。 */
  @Test
  void validatesStructuredDetails() {
    ToolResult result =
        new ToolResult("call-1", List.of(new TextToolContent("done")), false, "{\"exitCode\":0}");

    assertEquals("{\"exitCode\":0}", result.detailsJson());
    assertEquals("{}", new ToolResult("call-1", List.of(), false, null).detailsJson());
    assertThrows(
        IllegalArgumentException.class, () -> new ToolResult("call-1", List.of(), false, "[]"));
  }

  /** invocation 生命周期消息必须带 invocation ID，连接级消息则无需该字段。 */
  @Test
  void enforcesDaemonEnvelopeCorrelationAndJsonPayload() {
    EnvironmentName environmentName = new EnvironmentName("environment");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION, DaemonMessageType.INVOKE, environmentName, null, 1, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION,
                DaemonMessageType.LOAD_SKILL,
                environmentName,
                null,
                1,
                "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION,
                DaemonMessageType.HELLO,
                environmentName,
                null,
                0,
                "not-json"));

    DaemonEnvelope hello =
        new DaemonEnvelope(
            DaemonProtocol.VERSION, DaemonMessageType.HELLO, environmentName, null, 0, "{}");
    assertEquals(DaemonMessageType.HELLO, hello.messageType());
    assertEquals("environment", hello.environmentName().value());
  }

  private ToolDescriptor descriptor() {
    return descriptor("search");
  }

  private ToolDescriptor descriptor(String rendererKey) {
    return new ToolDescriptor(
        "search",
        "1.0.0",
        "Search the repository",
        rendererKey,
        schema(),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(10));
  }

  private ToolParamsSchema schema() {
    return new ToolParamsSchema(
        "search input",
        Map.of(
            "query",
            new ToolStringSchema("search query"),
            "formats",
            new ToolArraySchema(
                "output formats", new ToolEnumSchema("format", List.of("text", "json")))),
        Set.of("query"),
        false);
  }
}
