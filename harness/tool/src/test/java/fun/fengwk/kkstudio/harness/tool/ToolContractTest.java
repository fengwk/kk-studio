package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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

  /** 工具版本必须稳定，缺省 rendererKey 使用工具名，显式 key 则保持原值。 */
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
                "",
                "Search",
                null,
                schema(),
                ToolExecutionMode.CLOUD,
                ToolSideEffect.READ_ONLY,
                Duration.ZERO));
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

  /** 结果 details 必须是 JSON object，terminate 只作为 runtime 内存提示保留。 */
  @Test
  void validatesStructuredDetailsAndPreservesTerminateHint() {
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new TextToolContent("done")), false, "{\"exitCode\":0}", true);

    assertEquals("{\"exitCode\":0}", result.detailsJson());
    assertEquals(true, result.terminate());
    assertEquals("{}", new ToolResult("call-1", List.of(), false, null, false).detailsJson());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolResult("call-1", List.of(), false, "[]", false));
  }

  /** invocation 生命周期消息必须带 invocation ID，连接级消息则无需该字段。 */
  @Test
  void enforcesDaemonEnvelopeCorrelationAndJsonPayload() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonEnvelope(1, DaemonMessageType.INVOKE, "environment", null, 1, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonEnvelope(1, DaemonMessageType.HELLO, "environment", null, 0, "not-json"));

    DaemonEnvelope hello =
        new DaemonEnvelope(1, DaemonMessageType.HELLO, "environment", null, 0, "{}");
    assertEquals(DaemonMessageType.HELLO, hello.messageType());
  }

  private ToolDescriptor descriptor() {
    return descriptor(null);
  }

  private ToolDescriptor descriptor(String rendererKey) {
    return new ToolDescriptor(
        "search",
        "1.0.0",
        "Search the repository",
        rendererKey,
        schema(),
        ToolExecutionMode.CLOUD,
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
