package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** ToolCall 在 schema 校验前静默归一化模型参数的契约测试。 */
class ToolExecutionNormalizationTest {

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read",
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

  /** strict Provider 为原可选 offset 回传显式 null 时，应等价为缺省并返回不含该字段的执行调用。 */
  @Test
  void validateForRemovesOptionalNull() {
    ToolCall original = new ToolCall("call-1", "read", "{\"offset\":null,\"path\":\"a.txt\"}");

    ToolCall normalized = original.validateFor(DESCRIPTOR);

    assertEquals("{\"path\":\"a.txt\"}", normalized.argumentsJson());
    assertEquals("{\"offset\":null,\"path\":\"a.txt\"}", original.argumentsJson());
    assertNotSame(original, normalized);
  }

  /** required path 的 null 不是缺省值，必须继续由严格 schema 校验拒绝。 */
  @Test
  void validateForRejectsRequiredNull() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call-1", "read", "{\"path\":null}").validateFor(DESCRIPTOR));
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

  /** 工具名不赋予参数别名语义；每个历史内建名字都只能接受各自 descriptor schema 声明的字段。 */
  @ParameterizedTest
  @ValueSource(strings = {"read", "write", "edit", "find", "grep"})
  void validateForDoesNotRewriteFileForBuiltinToolNames(String toolName) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            toolName,
            "Tool " + toolName,
            toolName,
            new InputSchema(null, Map.of("path", new StringSchema(null)), Set.of("path"), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(10));

    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call-1", toolName, "{\"file\":\"test.txt\"}").validateFor(descriptor));
  }

  /** file、filePath、file_path 都不会被隐式搬到 schema 声明的 path。 */
  @ParameterizedTest
  @ValueSource(strings = {"file", "filePath", "file_path"})
  void validateForDoesNotRewriteUndeclaredParameterNames(String parameterName) {
    String argumentsJson = "{\"" + parameterName + "\":\"a.txt\"}";

    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call-1", "read", argumentsJson).validateFor(DESCRIPTOR));
  }

  /** 参数名由 schema 决定：同一个 read 工具明确声明 file 时，file 是合法 canonical 参数且保持不变。 */
  @Test
  void validateForAcceptsFileWhenDeclaredBySchema() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "read",
            "Read a file",
            "read",
            new InputSchema(null, Map.of("file", new StringSchema(null)), Set.of("file"), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(10));
    ToolCall call = new ToolCall("call-1", "read", "{\"file\":\"a.txt\"}");

    assertSame(call, call.validateFor(descriptor));
  }

  /** 缺失 schema 要求的 path 时，不猜测替代字段，继续由严格校验拒绝。 */
  @Test
  void validateForRejectsMissingRequiredPath() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call-1", "read", "{}").validateFor(DESCRIPTOR));
  }
}
