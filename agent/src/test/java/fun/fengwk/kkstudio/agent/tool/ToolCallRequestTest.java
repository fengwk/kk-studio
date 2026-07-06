package fun.fengwk.kkstudio.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolStringSchema;

import java.util.List;
import java.util.Map;

/**
 * ToolCallRequest 的输入规范化测试。
 *
 * @author fengwk
 */
public class ToolCallRequestTest {

  /** 校验空参数字符串会被规范化为 JSON 空对象。 */
  @Test
  public void testNormalizeBlankArgumentsToEmptyJsonObject() {
    ToolCallRequest request = new ToolCallRequest("call_1", "echo", " ");

    assertEquals("{}", request.getArgumentsJson());
  }

  /** 校验 toolCallId 与 toolName 是工具调用请求的必要身份字段。 */
  @Test
  public void testRejectsBlankIdentity() {
    assertThrows(IllegalArgumentException.class, () -> new ToolCallRequest(null, "echo", "{}"));
    assertThrows(IllegalArgumentException.class, () -> new ToolCallRequest("call_1", " ", "{}"));
  }

  /** 校验参数必须是合法 JSON object。 */
  @Test
  public void testRejectsNonObjectOrMalformedArguments() {
    assertThrows(IllegalArgumentException.class, () -> new ToolCallRequest("call_1", "echo", "[]"));
    assertThrows(IllegalArgumentException.class, () -> new ToolCallRequest("call_1", "echo", "{"));
  }

  /** 校验参数满足 schema 时允许创建请求。 */
  @Test
  public void testAcceptsArgumentsMatchingSchema() {
    ToolCallRequest request =
        new ToolCallRequest(
            "call_1",
            "echo",
            "{\"text\":\"OK\",\"count\":1,"
                + "\"score\":1.5,\"enabled\":true,\"mode\":\"fast\",\"tags\":[\"a\"],"
                + "\"meta\":{\"traceId\":\"tr_1\"}}",
            structuredSchema());

    assertEquals("echo", request.getToolName());
  }

  /** 校验缺失必填字段、未知字段与类型错误会被 schema 拒绝。 */
  @Test
  public void testRejectsArgumentsViolatingObjectSchema() {
    assertSchemaError("{}", "$.text is required");
    assertSchemaError("{\"text\":\"OK\",\"extra\":1}", "$.extra is not allowed by inputSchema");
    assertSchemaError("{\"text\":1}", "$.text must be string");
    assertSchemaError("{\"text\":\"OK\",\"count\":1.2}", "$.count must be integer");
    assertSchemaError("{\"text\":\"OK\",\"enabled\":\"true\"}", "$.enabled must be boolean");
    assertSchemaError("{\"text\":\"OK\",\"mode\":\"slow\"}", "$.mode must be one of [fast]");
  }

  /** 校验数组元素与嵌套 object 会递归执行 schema 校验。 */
  @Test
  public void testRejectsArgumentsViolatingNestedSchema() {
    assertSchemaError("{\"text\":\"OK\",\"tags\":[1]}", "$.tags[0] must be string");
    assertSchemaError("{\"text\":\"OK\",\"meta\":{}}", "$.meta.traceId is required");
    assertSchemaError(
        "{\"text\":\"OK\",\"meta\":{\"traceId\":\"tr_1\",\"extra\":1}}",
        "$.meta.extra is not allowed by inputSchema");
  }

  /** 校验 schema 自身缺失 required 属性名时 fail-fast。 */
  @Test
  public void testRejectsInvalidSchemaRequiredName() {
    ToolParamsSchema schema = ToolParamsSchema.builder().required(List.of(" ")).build();

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ToolCallRequest("call_1", "echo", "{}", schema));

    assertEquals(
        "invalid tool inputSchema: required property name must not be blank: $",
        error.getMessage());
  }

  private void assertSchemaError(String argumentsJson, String expectedMessagePart) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ToolCallRequest("call_1", "echo", argumentsJson, structuredSchema()));
    assertTrue(error.getMessage().contains(expectedMessagePart), error.getMessage());
  }

  private ToolParamsSchema structuredSchema() {
    return ToolParamsSchema.builder()
        .properties(
            Map.of(
                "text", ToolStringSchema.builder().build(),
                "count", ToolIntegerSchema.builder().build(),
                "score", ToolNumberSchema.builder().build(),
                "enabled", ToolBooleanSchema.builder().build(),
                "mode", ToolEnumSchema.builder().enumValues(List.of("fast")).build(),
                "tags", ToolArraySchema.builder().items(ToolStringSchema.builder().build()).build(),
                "meta",
                    ToolObjectSchema.builder()
                        .properties(Map.of("traceId", ToolStringSchema.builder().build()))
                        .required(List.of("traceId"))
                        .additionalProperties(false)
                        .build()))
        .required(List.of("text"))
        .additionalProperties(false)
        .build();
  }
}
