package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

/** DaemonEnvelope.environmentName 的 canonical 路由名称边界测试。 */
class DaemonEnvelopeTest {

  private static DaemonEnvelope envelope(String environmentName) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_2,
        DaemonMessageType.READY,
        new EnvironmentName(environmentName),
        null,
        0,
        "{}");
  }

  /** 常规小写路由名称可接受。 */
  @Test
  void acceptsCanonicalNames() {
    assertEquals("env", envelope("env").environmentName().value());
    assertEquals("env-1", envelope("env-1").environmentName().value());
    assertEquals("my-local-workspace", envelope("my-local-workspace").environmentName().value());
    assertEquals(
        "a".repeat(EnvironmentName.MAX_LENGTH),
        envelope("a".repeat(EnvironmentName.MAX_LENGTH)).environmentName().value());
  }

  /** 空白、大写、斜杠、非字母数字与非法分隔必须拒绝（无大小写折叠/路径歧义）。 */
  @Test
  void rejectsNonCanonicalNames() {
    assertThrows(IllegalArgumentException.class, () -> envelope(" env"));
    assertThrows(IllegalArgumentException.class, () -> envelope("env "));
    assertThrows(IllegalArgumentException.class, () -> envelope(" "));
    assertThrows(NullPointerException.class, () -> envelope(null));
    assertThrows(IllegalArgumentException.class, () -> envelope(""));
    assertThrows(IllegalArgumentException.class, () -> envelope("Env"));
    assertThrows(IllegalArgumentException.class, () -> envelope("ENV"));
    assertThrows(IllegalArgumentException.class, () -> envelope("my/env"));
    assertThrows(IllegalArgumentException.class, () -> envelope("my env"));
    assertThrows(IllegalArgumentException.class, () -> envelope("my_env"));
    assertThrows(IllegalArgumentException.class, () -> envelope("-env"));
    assertThrows(IllegalArgumentException.class, () -> envelope("env-"));
    assertThrows(IllegalArgumentException.class, () -> envelope("env--1"));
    assertThrows(IllegalArgumentException.class, () -> envelope("我的环境"));
  }

  /** 长度超出 {@link EnvironmentName#MAX_LENGTH} 必须拒绝。 */
  @Test
  void rejectsNamesExceedingMaxLength() {
    assertThrows(
        IllegalArgumentException.class, () -> envelope("a".repeat(EnvironmentName.MAX_LENGTH + 1)));
  }

  /** 其余 envelope 字段的构造期契约：版本、序号与 invocationId 规则。 */
  @Test
  void rejectsInvalidProtocolVersionSequenceAndInvocationId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                0, DaemonMessageType.READY, new EnvironmentName("env"), null, 0, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonEnvelope(
                DaemonProtocol.VERSION_2,
                DaemonMessageType.READY,
                new EnvironmentName("env"),
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
                new EnvironmentName("env"),
                " ",
                0,
                "{}"));
  }
}
