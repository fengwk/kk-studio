package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

/** DaemonEnvelope.environmentName 的 wire 安全边界测试（display-only 但必须 canonical 且有界）。 */
class DaemonEnvelopeTest {

  private static final String ENVIRONMENT_ID = "123e4567-e89b-12d3-a456-426614174000";

  private static DaemonEnvelope envelope(String environmentName) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_2,
        DaemonMessageType.READY,
        new EnvironmentId(ENVIRONMENT_ID),
        environmentName,
        null,
        0,
        "{}");
  }

  /** 常规与多字节名称可接受；UTF-8 编码在 256 字节边界内。 */
  @Test
  void acceptsCanonicalNamesAndBoundaryUtf8Length() {
    assertEquals("env", envelope("env").environmentName());
    assertEquals("我的环境", envelope("我的环境").environmentName());
    // 256 UTF-8 字节边界：85 个三字节汉字 + 1 个 ASCII 字符。
    assertEquals("中".repeat(85) + "x", envelope("中".repeat(85) + "x").environmentName());
  }

  /** 周边空白（含 Unicode 空白）与空白整体必须拒绝，防止展示字段污染 wire。 */
  @Test
  void rejectsSurroundingWhitespaceAndBlank() {
    assertThrows(IllegalArgumentException.class, () -> envelope(" env"));
    assertThrows(IllegalArgumentException.class, () -> envelope("env "));
    assertThrows(IllegalArgumentException.class, () -> envelope(" env "));
    assertThrows(IllegalArgumentException.class, () -> envelope("env\u2003"));
    assertThrows(IllegalArgumentException.class, () -> envelope(" "));
    assertThrows(IllegalArgumentException.class, () -> envelope(null));
  }

  /** ISO control（C0/C1）与未配对代理项必须在构造期拒绝。 */
  @Test
  void rejectsIsoControlsAndUnpairedSurrogates() {
    assertThrows(IllegalArgumentException.class, () -> envelope("a\tb"));
    assertThrows(IllegalArgumentException.class, () -> envelope("a\u0000b"));
    assertThrows(IllegalArgumentException.class, () -> envelope("a\u007Fb"));
    assertThrows(IllegalArgumentException.class, () -> envelope("a\u009Fb"));
    assertThrows(IllegalArgumentException.class, () -> envelope("a\uD800b"));
    assertThrows(IllegalArgumentException.class, () -> envelope("a\uDFFFb"));
  }

  /** UTF-8 编码超过 256 字节必须拒绝。 */
  @Test
  void rejectsNamesExceedingUtf8Bytes() {
    assertThrows(IllegalArgumentException.class, () -> envelope("中".repeat(85) + "xy"));
    assertThrows(IllegalArgumentException.class, () -> envelope("中".repeat(86)));
  }

  /** 其余 envelope 字段的构造期契约：版本、序号与 invocationId 规则。 */
  @Test
  void rejectsInvalidProtocolVersionSequenceAndInvocationId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                0,
                DaemonMessageType.READY,
                new EnvironmentId(ENVIRONMENT_ID),
                "env",
                null,
                0,
                "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION_2,
                DaemonMessageType.READY,
                new EnvironmentId(ENVIRONMENT_ID),
                "env",
                null,
                -1,
                "{}"));
    // 非调用消息不得携带空白 invocationId。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION_2,
                DaemonMessageType.READY,
                new EnvironmentId(ENVIRONMENT_ID),
                "env",
                " ",
                0,
                "{}"));
  }
}
