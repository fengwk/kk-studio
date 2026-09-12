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

  private static final ToolDescriptor NON_ALLOWLIST_DESCRIPTOR =
      new ToolDescriptor(
          "search",
          "1.0.0",
          "Search repository",
          "search",
          new InputSchema(null, Map.of("path", new StringSchema(null)), Set.of("path"), false),
          ToolSideEffect.READ_ONLY,
          Duration.ofSeconds(10));

  /** 单个 file 别名在 path 缺失时被成功归一化为 canonical path。 */
  @Test
  void validateForRewritesFileAliasToCanonicalPath() {
    ToolCall call = new ToolCall("call-1", "read", "{\"file\":\"a.txt\"}").validateFor(DESCRIPTOR);
    assertEquals("{\"path\":\"a.txt\"}", call.argumentsJson());
  }

  /** 单个 filePath 别名在 path 缺失时被成功归一化为 canonical path。 */
  @Test
  void validateForRewritesFilePathAliasToCanonicalPath() {
    ToolCall call =
        new ToolCall("call-1", "read", "{\"filePath\":\"a.txt\"}").validateFor(DESCRIPTOR);
    assertEquals("{\"path\":\"a.txt\"}", call.argumentsJson());
  }

  /** 单个 file_path 别名在 path 缺失时被成功归一化为 canonical path。 */
  @Test
  void validateForRewritesSnakeCaseFilePathAliasToCanonicalPath() {
    ToolCall call =
        new ToolCall("call-1", "read", "{\"file_path\":\"a.txt\"}").validateFor(DESCRIPTOR);
    assertEquals("{\"path\":\"a.txt\"}", call.argumentsJson());
  }

  /** canonical path 已存在时伴随 alias 不会改写，并由 schema additionalProperties=false 严格拒绝。 */
  @Test
  void validateForRejectsCanonicalPathWithAlias() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "read", "{\"file\":\"b.txt\",\"path\":\"a.txt\"}")
                .validateFor(DESCRIPTOR));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "read", "{\"filePath\":\"b.txt\",\"path\":\"a.txt\"}")
                .validateFor(DESCRIPTOR));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "read", "{\"file_path\":\"b.txt\",\"path\":\"a.txt\"}")
                .validateFor(DESCRIPTOR));
  }

  /** 多个 alias 同时出现时不作猜测，保留原样并由 schema 严格拒绝。 */
  @Test
  void validateForRejectsMultipleAliases() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "read", "{\"file\":\"a.txt\",\"filePath\":\"b.txt\"}")
                .validateFor(DESCRIPTOR));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "read", "{\"file\":\"a.txt\",\"file_path\":\"b.txt\"}")
                .validateFor(DESCRIPTOR));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "read", "{\"filePath\":\"a.txt\",\"file_path\":\"b.txt\"}")
                .validateFor(DESCRIPTOR));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall(
                    "call-1",
                    "read",
                    "{\"file\":\"a.txt\",\"filePath\":\"b.txt\",\"file_path\":\"c.txt\"}")
                .validateFor(DESCRIPTOR));
  }

  /** 别名字段值为非字符串时原样移至 path，随后由 path 的 string schema 严格拒绝。 */
  @Test
  void validateForRejectsNonStringAliasValueMovedToPath() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call-1", "read", "{\"file\":123}").validateFor(DESCRIPTOR));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call-1", "read", "{\"filePath\":true}").validateFor(DESCRIPTOR));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "read", "{\"file_path\":[\"a.txt\"]}").validateFor(DESCRIPTOR));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call-1", "read", "{\"file\":null}").validateFor(DESCRIPTOR));
  }

  /** 非 allowlist 工具不执行别名重写，缺失 path 或残留未定义字段仍被严格拒绝。 */
  @Test
  void validateForDoesNotRewriteAliasForNonAllowlistTool() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall("call-1", "search", "{\"file\":\"a.txt\"}")
                .validateFor(NON_ALLOWLIST_DESCRIPTOR));
  }

  /** 别名归一化与数字文本转换以及可缺省 null 移除能够正确协同工作。 */
  @Test
  void validateForCombinesAliasRewriteWithNumericAndNullNormalization() {
    ToolCall call1 =
        new ToolCall("call-1", "read", "{\"file\":\"a.txt\",\"offset\":\"10\"}")
            .validateFor(DESCRIPTOR);
    assertEquals("{\"offset\":10,\"path\":\"a.txt\"}", call1.argumentsJson());

    ToolCall call2 =
        new ToolCall("call-2", "read", "{\"file\":\"a.txt\",\"offset\":null}")
            .validateFor(DESCRIPTOR);
    assertEquals("{\"path\":\"a.txt\"}", call2.argumentsJson());
  }

  /** 全部十个内建文件工具（Environment 与 Cloud FS）均支持单 alias 归一化。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "read",
        "write",
        "edit",
        "find",
        "grep",
        "cloud_read",
        "cloud_write",
        "cloud_edit",
        "cloud_find",
        "cloud_grep"
      })
  void validateForRewritesAliasAcrossAllAllowlistTools(String toolName) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            toolName,
            "1.0.0",
            "Tool " + toolName,
            toolName,
            new InputSchema(null, Map.of("path", new StringSchema(null)), Set.of("path"), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(10));

    ToolCall call =
        new ToolCall("call-1", toolName, "{\"file\":\"test.txt\"}").validateFor(descriptor);
    assertEquals("{\"path\":\"test.txt\"}", call.argumentsJson());
  }

  /** 别名原值完整保留，不进行 trim 或内容修改。 */
  @Test
  void validateForPreservesRawAliasValueWithoutTrimming() {
    ToolCall call =
        new ToolCall("call-1", "read", "{\"file\":\"  a.txt  \"}").validateFor(DESCRIPTOR);
    assertEquals("{\"path\":\"  a.txt  \"}", call.argumentsJson());
  }

  /** 无 path 且无可用 alias 时不作改写，继续由 schema 严格拒绝缺失 required 字段。 */
  @Test
  void validateForRejectsMissingPathWhenNoAliasProvided() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCall("call-1", "read", "{}").validateFor(DESCRIPTOR));
  }
}
