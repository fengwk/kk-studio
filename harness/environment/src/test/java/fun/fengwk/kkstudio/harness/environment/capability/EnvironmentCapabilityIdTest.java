package fun.fengwk.kkstudio.harness.environment.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;

/** Environment Capability identity 的 canonical 语法、长度和独立 namespace 契约测试。 */
class EnvironmentCapabilityIdTest {

  /** 验证原子 capability namespace 的稳定身份和标准操作名都能保持原值。 */
  @Test
  void acceptsCanonicalExamples() {
    assertEquals("fs.read", new EnvironmentCapabilityId("fs.read").value());
    assertEquals("fs.apply-patch", new EnvironmentCapabilityId("fs.apply-patch").value());
    assertEquals("process.exec", new EnvironmentCapabilityId("process.exec").value());
    assertEquals("lsp.goto-definition", new EnvironmentCapabilityId("lsp.goto-definition").value());
    assertEquals("mcp.call", new EnvironmentCapabilityId("mcp.call").toString());
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
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("Fs.read"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("fs_read"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("fs read"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId(".fs"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("fs."));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("fs..read"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityId("fs.-read"));
  }

  /** 只证明 namespace/type 独立，不表示生产 Capability ID 与 Agent Tool ID 需要同名。 */
  @Test
  void remainsIndependentFromAgentToolId() {
    assertNotEquals(new AgentToolId("fs.read"), new EnvironmentCapabilityId("fs.read"));
  }
}
