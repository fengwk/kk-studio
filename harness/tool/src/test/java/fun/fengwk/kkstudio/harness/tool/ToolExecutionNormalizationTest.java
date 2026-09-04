package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** ToolCall 在 schema 校验前静默数字归一化参数的契约测试。 */
class ToolExecutionNormalizationTest {

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read",
          "1.0.0",
          "Read a file",
          "read",
          new InputSchema(
              null,
              Map.of(
                  "path", new StringSchema(null),
                  "offset", new IntegerSchema(null)),
              Set.of("path"),
              false),
          ToolSideEffect.READ_ONLY,
          Duration.ofSeconds(10));

  /** integer 字段的十进制数字字符串被写成 JSON integer，归一化后的 argumentsJson 不含数字字符串。 */
  @Test
  void validateForConvertsIntegerTextToInteger() {
    ToolCall call =
        new ToolCall("call-1", "read", "{\"offset\":\"10\",\"path\":\"a.txt\"}")
            .validateFor(DESCRIPTOR);

    assertEquals("{\"offset\":10,\"path\":\"a.txt\"}", call.argumentsJson());
  }

  /** validateFor 返回归一化后的新 ToolCall，原调用保持原始 JSON 与不可变性。 */
  @Test
  void validateForReturnsNormalizedCall() {
    ToolCall original = new ToolCall("call-1", "read", "{\"offset\":\"10\",\"path\":\"a.txt\"}");
    ToolCall normalized = original.validateFor(DESCRIPTOR);

    assertEquals("{\"offset\":\"10\",\"path\":\"a.txt\"}", original.argumentsJson());
    assertEquals("{\"offset\":10,\"path\":\"a.txt\"}", normalized.argumentsJson());
    assertEquals(original.id(), normalized.id());
    assertEquals(original.toolName(), normalized.toolName());
    assertNotSame(original, normalized);
  }

  /** 无需改写时 validateFor 返回同一实例，冻结 call identity 得以保留。 */
  @Test
  void validateForReturnsSameInstanceWhenUnchanged() {
    ToolCall original = new ToolCall("call-1", "read", "{\"path\":\"a.txt\"}");
    assertSame(original, original.validateFor(DESCRIPTOR));
  }

  /** 未知附加字段即使伴随有效参数，校验仍因 additionalProperties 严格失败。 */
  @Test
  void validateForRejectsAdditionalProperties() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "read", "{\"extra\":true,\"path\":\"a.txt\"}")
                .validateFor(DESCRIPTOR));
  }

  /** 非数字字符串不改写，校验仍以类型不匹配失败。 */
  @Test
  void validateForRejectsNonNumericIntegerText() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "read", "{\"offset\":\"ten\",\"path\":\"a.txt\"}")
                .validateFor(DESCRIPTOR));
  }
}
