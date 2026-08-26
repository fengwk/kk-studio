package fun.fengwk.kkstudio.harness.tool.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;

/** Environment Capability identity 的 canonical 语法、长度和独立 namespace 契约测试。 */
class EnvironmentCapabilityIdTest {

  /** 验证分层、分隔和数字后缀身份都能保持原值。 */
  @Test
  void acceptsCanonicalExamples() {
    assertEquals("base.read", new EnvironmentCapabilityId("base.read").value());
    assertEquals("base.apply-patch", new EnvironmentCapabilityId("base.apply-patch").value());
    assertEquals(
        "mcp.github.create-issue", new EnvironmentCapabilityId("mcp.github.create-issue").value());
    assertEquals(
        "studio.canvas.generate2",
        new EnvironmentCapabilityId("studio.canvas.generate2").toString());
  }

  /** 验证 null、空值和超过 128 字符的身份被拒绝。 */
  @Test
  void rejectsNullEmptyAndOverlongValues() {
    assertThrows(NullPointerException.class, () -> new EnvironmentCapabilityId(null));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId(""));
    assertEquals(
        "a".repeat(EnvironmentCapabilityId.MAX_LENGTH),
        new EnvironmentCapabilityId("a".repeat(EnvironmentCapabilityId.MAX_LENGTH)).value());
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityId("a".repeat(EnvironmentCapabilityId.MAX_LENGTH + 1)));
  }

  /** 验证大小写、下划线、空白和非法分隔符不会形成第二套 canonical 语法。 */
  @Test
  void rejectsNonCanonicalValues() {
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("Base.read"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("base_read"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("base read"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId(".base"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("base."));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("base..read"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("base.-read"));
  }

  /** 验证 Capability identity 与同值 Agent Tool identity 仍是不同的 namespace/type。 */
  @Test
  void remainsIndependentFromAgentToolId() {
    assertNotEquals(new AgentToolId("base.read"), new EnvironmentCapabilityId("base.read"));
  }
}
