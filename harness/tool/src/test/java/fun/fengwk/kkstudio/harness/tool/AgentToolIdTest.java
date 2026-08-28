package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link AgentToolId} canonical 公共身份契约测试。 */
class AgentToolIdTest {

  /** 验证常用的分层、分隔和数字后缀身份都能保持原值。 */
  @Test
  void acceptsCanonicalExamples() {
    assertEquals("base.read", new AgentToolId("base.read").value());
    assertEquals("base.apply-patch", new AgentToolId("base.apply-patch").value());
    assertEquals("custom.goal.create", new AgentToolId("custom.goal.create").value());
    assertEquals("mcp.github.create-issue", new AgentToolId("mcp.github.create-issue").value());

    AgentToolId generated = new AgentToolId("studio.canvas.generate2");
    assertEquals("studio.canvas.generate2", generated.value());
    assertEquals("studio.canvas.generate2", generated.toString());
  }

  /** 验证 null 与空值在进入 canonical 校验前即被拒绝。 */
  @Test
  void rejectsNullAndEmptyValues() {
    assertThrows(NullPointerException.class, () -> new AgentToolId(null));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId(""));
  }

  /** 验证 128 字符上限以及超出上限的值。 */
  @Test
  void enforcesMaximumLength() {
    assertEquals(
        "a".repeat(AgentToolId.MAX_LENGTH),
        new AgentToolId("a".repeat(AgentToolId.MAX_LENGTH)).value());
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentToolId("a".repeat(AgentToolId.MAX_LENGTH + 1)));
  }

  /** 验证大写、下划线、空白和非 ASCII 字符不会形成第二套身份语法。 */
  @Test
  void rejectsNonCanonicalCharacters() {
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("Base.read"));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("base_read"));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("base read"));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("base.读"));
  }

  /** 验证分隔符不能出现在身份首尾。 */
  @Test
  void rejectsLeadingAndTrailingSeparators() {
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId(".base"));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("base."));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("-base"));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("base-"));
  }

  /** 验证连续或混合连续分隔符不会被解释为 canonical identity。 */
  @Test
  void rejectsConsecutiveAndMixedSeparators() {
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("base..read"));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("base--read"));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("base.-read"));
    assertThrows(IllegalArgumentException.class, () -> new AgentToolId("base-.read"));
  }
}
