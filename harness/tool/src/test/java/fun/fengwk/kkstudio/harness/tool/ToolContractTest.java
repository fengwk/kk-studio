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

  /** invocation 生命周期消息必须带 invocation ID，连接级消息则无需该字段。 */
  @Test
  void enforcesDaemonEnvelopeCorrelationAndJsonPayload() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                1, DaemonMessageType.INVOKE, "workspace", "environment", null, 1, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                1, DaemonMessageType.HELLO, "workspace", "environment", null, 0, "not-json"));

    DaemonEnvelope hello =
        new DaemonEnvelope(1, DaemonMessageType.HELLO, "workspace", "environment", null, 0, "{}");
    assertEquals(DaemonMessageType.HELLO, hello.messageType());
  }

  private ToolDescriptor descriptor() {
    ToolParamsSchema schema =
        new ToolParamsSchema(
            "search input",
            Map.of(
                "query",
                new ToolStringSchema("search query"),
                "formats",
                new ToolArraySchema(
                    "output formats", new ToolEnumSchema("format", List.of("text", "json")))),
            Set.of("query"),
            false);
    return new ToolDescriptor(
        "search",
        "Search the workspace",
        schema,
        ToolExecutionMode.CLOUD,
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(10));
  }
}
