package fun.fengwk.kkstudio.core.ai.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;

import java.time.Duration;
import java.util.List;

/** Agent 正文 + skills + subagents 唯一 system prompt 边界的组合语义。 */
class AgentPromptComposerTest {

  private static final int DEFAULT_MAX_TURNS = 7;

  private final AgentPromptComposer composer =
      new AgentPromptComposer(
          new SubagentConfig(
              2, 10, null, Duration.ZERO, DEFAULT_MAX_TURNS, Duration.ofMillis(100)));

  private static SkillBinding skill(String name, String description) {
    return new SkillBinding(name, description, null);
  }

  /** 空正文 + 空 capability 输出空 system prompt。 */
  @Test
  void composesEmptyPromptWhenNothingProvided() {
    assertEquals("", composer.compose(null, List.of(), List.of()));
    assertEquals("", composer.compose("   ", List.of(), List.of()));
  }

  /** 正文、skills、subagents 严格按 body -> skills -> subagents 顺序拼接，并来自真实 classpath 模板。 */
  @Test
  void keepsStrictSectionOrder() {
    String result =
        composer.compose(
            "You are the planner.",
            List.of(skill("math", "Do math.")),
            List.of(new SubagentBinding("researcher", "Do research.")));

    int body = result.indexOf("You are the planner.");
    int skills = result.indexOf("<available_skills>");
    int subagents = result.indexOf("<available_subagents>");
    assertTrue(body >= 0 && skills > body && subagents > skills, result);
    assertTrue(result.contains("load_skill"), result);
    assertFalse(result.contains("${skills}"), result);
    assertFalse(result.contains("${subagents}"), result);
  }

  /** 空白正文不产生空段落；skills/subagents 各自由 classpath 模板渲染。 */
  @Test
  void skipsBlankBodyButRendersCapabilities() {
    String result =
        composer.compose(
            "   ",
            List.of(skill("math", "Do math.")),
            List.of(new SubagentBinding("researcher", "Do research.")));

    assertFalse(result.startsWith("   "), result);
    assertTrue(result.contains("  <skill>\n    <name>math</name>"), result);
    assertTrue(result.contains("  <subagent>\n    <name>researcher</name>"), result);
  }

  /** skill 与 subagent 的 name/description 都做 XML 转义，避免破坏 available 列表结构。 */
  @Test
  void escapesXmlInEntries() {
    String result =
        composer.compose(
            null,
            List.of(skill("a&b", "uses <angle> and \"quotes\" and 'apos'")),
            List.of(new SubagentBinding("x<y>", "desc & more")));

    assertTrue(result.contains("<name>a&amp;b</name>"), result);
    assertTrue(
        result.contains(
            "<description>uses &lt;angle&gt; and &quot;quotes&quot; and &apos;apos&apos;</description>"),
        result);
    assertTrue(result.contains("<name>x&lt;y&gt;</name>"), result);
    assertTrue(result.contains("<description>desc &amp; more</description>"), result);
  }

  /** 空 subagent description 回退为占位文案。 */
  @Test
  void fallsBackForBlankSubagentDescription() {
    String result = composer.compose(null, List.of(), List.of(new SubagentBinding("mute", "  ")));

    assertTrue(result.contains("<description>(no description)</description>"), result);
  }

  /** task 委派指令中的默认 maxTurns 来自 SubagentConfig。 */
  @Test
  void rendersTaskInstructionsWithConfiguredDefaultMaxTurns() {
    String result =
        composer.compose(
            "body", List.of(), List.of(new SubagentBinding("researcher", "Do research.")));

    assertTrue(result.contains("The default is `" + DEFAULT_MAX_TURNS + "`"), result);
    assertFalse(result.contains("${defaultMaxTurns}"), result);
  }

  /** skills/subagents 列表不可为空引用。 */
  @Test
  void rejectsNullLists() {
    assertThrows(NullPointerException.class, () -> composer.compose("body", null, List.of()));
    assertThrows(NullPointerException.class, () -> composer.compose("body", List.of(), null));
  }
}
