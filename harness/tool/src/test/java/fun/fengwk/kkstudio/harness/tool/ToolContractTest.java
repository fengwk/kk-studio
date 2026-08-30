package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.ArraySchema;
import fun.fengwk.kkstudio.harness.common.schema.EnumSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tool schema、执行与结果契约测试。 */
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
            InputSchema.class,
            ToolSideEffect.class,
            Duration.class),
        Arrays.stream(components).map(RecordComponent::getType).toList());
  }

  /** 结果 details 必须是 JSON object；空白 details 归一为 {}，非 object 拒绝。 */
  @Test
  void validatesStructuredDetails() {
    ToolResult result =
        new ToolResult("call-1", List.of(new TextResultContent("done")), false, "{\"exitCode\":0}");

    assertEquals("{\"exitCode\":0}", result.detailsJson());
    assertEquals("{}", new ToolResult("call-1", List.of(), false, null).detailsJson());
    assertThrows(
        IllegalArgumentException.class, () -> new ToolResult("call-1", List.of(), false, "[]"));
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

  private InputSchema schema() {
    return new InputSchema(
        "search input",
        Map.of(
            "query",
            new StringSchema("search query"),
            "formats",
            new ArraySchema("output formats", new EnumSchema("format", List.of("text", "json")))),
        Set.of("query"),
        false);
  }
}
