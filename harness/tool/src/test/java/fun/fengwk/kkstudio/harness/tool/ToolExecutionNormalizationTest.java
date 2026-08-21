package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** 执行请求在 schema 校验前静默归一化参数的契约测试。 */
class ToolExecutionNormalizationTest {

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read",
          "1.0.0",
          ToolType.PLATFORM,
          "Read a file",
          "read",
          new ToolParamsSchema(
              null,
              Map.of(
                  "path", new ToolStringSchema(null),
                  "offset", new ToolIntegerSchema(null)),
              Set.of("path"),
              false),
          ToolSideEffect.READ_ONLY,
          Duration.ofSeconds(10));

  /** filePath 且无 path 时，执行请求持有归一化后的 ToolCall，argumentsJson 只含 path。 */
  @Test
  void executionRequestHoldsCallWithFilePathMappedToPath() {
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            DESCRIPTOR,
            new ToolCall("call-1", "read", "{\"filePath\":\"README.md\"}"),
            Duration.ZERO);

    assertEquals("{\"path\":\"README.md\"}", request.call().argumentsJson());
  }

  /** integer 字段的十进制数字字符串被写成 JSON integer，执行请求的 argumentsJson 不含数字字符串。 */
  @Test
  void executionRequestHoldsCallWithIntegerTextConverted() {
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            DESCRIPTOR,
            new ToolCall("call-1", "read", "{\"path\":\"a.txt\",\"offset\":\"10\"}"),
            Duration.ZERO);

    assertEquals("{\"path\":\"a.txt\",\"offset\":10}", request.call().argumentsJson());
  }

  /** validateFor 返回归一化后的新 ToolCall，原调用保持原始 JSON。 */
  @Test
  void validateForReturnsNormalizedCall() {
    ToolCall original = new ToolCall("call-1", "read", "{\"filePath\":\"a.txt\"}");
    ToolCall normalized = original.validateFor(DESCRIPTOR);

    assertEquals("{\"filePath\":\"a.txt\"}", original.argumentsJson());
    assertEquals("{\"path\":\"a.txt\"}", normalized.argumentsJson());
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

  /** path 与 filePath 同时存在时执行请求仍因 additionalProperties 失败，归一化不掩盖冲突。 */
  @Test
  void executionRequestRejectsFilePathAlongsidePath() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                DESCRIPTOR,
                new ToolCall("call-1", "read", "{\"filePath\":\"a.txt\",\"path\":\"b.txt\"}"),
                Duration.ZERO));
  }

  /** 非数字字符串不改写，执行请求仍以类型不匹配失败。 */
  @Test
  void executionRequestRejectsNonNumericIntegerText() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                DESCRIPTOR,
                new ToolCall("call-1", "read", "{\"path\":\"a.txt\",\"offset\":\"ten\"}"),
                Duration.ZERO));
  }
}
