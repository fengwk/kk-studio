package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.testing.TestEnvironmentBindings;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Agent 正文 + current environment + skills + subagents 唯一 system prompt 边界的组合语义。 */
class AgentPromptComposerTest {

  private static final int DEFAULT_MAX_TURNS = 7;

  private final AgentPromptComposer composer =
      new AgentPromptComposer(() -> new SubagentConfig(2, 10, 0, Duration.ZERO, DEFAULT_MAX_TURNS));

  private static SkillBinding skill(String name, String description) {
    return new SkillBinding(name, description, null);
  }

  /** 空正文 + 无 Environment 只保留有值的 date，不输出 none 字段。 */
  @Test
  void omitsNoneCurrentEnvironmentFields() {
    String expected = "<current_environment>\n" + "- date: 2026-08-09\n" + "</current_environment>";

    assertEquals(expected, composer.compose(null, none(), List.of(), List.of()));
    assertEquals(expected, composer.compose("   ", none(), List.of(), List.of()));
    assertFalse(expected.contains("- name:"));
    assertFalse(expected.contains("- workspace:"));
    assertFalse(expected.contains("- system:"));
    assertFalse(expected.contains("- note:"));
    assertNoLegacyFields(expected);
  }

  /** 正文、current environment、skills、subagents 严格按固定顺序拼接，并来自真实 classpath 模板。 */
  @Test
  void keepsStrictSectionOrder() {
    String result =
        composer.compose(
            "You are the planner.",
            none(),
            List.of(skill("math", "Do math.")),
            List.of(new SubagentBinding("researcher", "Do research.")));

    int body = result.indexOf("You are the planner.");
    int currentEnvironment = result.indexOf("<current_environment>");
    int skills = result.indexOf("<available_skills>");
    int subagents = result.indexOf("<available_subagents>");
    assertTrue(
        body >= 0 && currentEnvironment > body && skills > currentEnvironment && subagents > skills,
        result);
    assertTrue(result.contains("load_skill"), result);
    assertFalse(result.contains("${skills}"), result);
    assertFalse(result.contains("${subagents}"), result);
  }

  /** Environment、skill 与 subagent 动态值在进入 XML 模板前全部转义。 */
  @Test
  void escapesXmlInDynamicValues() {
    EnvironmentName name = mock(EnvironmentName.class);
    when(name.value()).thenReturn("env<&\"'");
    EnvironmentBinding binding = mock(EnvironmentBinding.class);
    when(binding.environmentName()).thenReturn(name);
    when(binding.workspacePath()).thenReturn("sub/<&\"'");
    CurrentEnvironmentContext environment =
        new CurrentEnvironmentContext(
            binding,
            DaemonOperatingSystem.WSL,
            LocalDate.of(2026, 8, 9),
            "Use <mount> & \"commands\" from 'Windows'.");

    String result =
        composer.compose(
            null,
            environment,
            List.of(skill("a&b", "uses <angle> and \"quotes\" and 'apos'")),
            List.of(new SubagentBinding("x<y>", "desc & more")));

    assertTrue(result.contains("- name: env&lt;&amp;&quot;&apos;"), result);
    assertTrue(result.contains("- workspace: sub/&lt;&amp;&quot;&apos;"), result);
    assertTrue(
        result.contains(
            "- note: Use &lt;mount&gt; &amp; &quot;commands&quot; from &apos;Windows&apos;."),
        result);
    assertTrue(result.contains("<name>a&amp;b</name>"), result);
    assertTrue(
        result.contains(
            "<description>uses &lt;angle&gt; and &quot;quotes&quot; and &apos;apos&apos;</description>"),
        result);
    assertTrue(result.contains("<name>x&lt;y&gt;</name>"), result);
    assertTrue(result.contains("<description>desc &amp; more</description>"), result);
  }

  /** 空白正文不产生空段落；skills/subagents 各自由 classpath 模板渲染。 */
  @Test
  void skipsBlankBodyButRendersCapabilities() {
    String result =
        composer.compose(
            "   ",
            none(),
            List.of(skill("math", "Do math.")),
            List.of(new SubagentBinding("researcher", "Do research.")));

    assertFalse(result.startsWith("   "), result);
    assertTrue(result.contains("  <skill>\n    <name>math</name>"), result);
    assertTrue(result.contains("  <subagent>\n    <name>researcher</name>"), result);
  }

  /** 空 subagent description 回退为占位文案。 */
  @Test
  void fallsBackForBlankSubagentDescription() {
    String result =
        composer.compose(null, none(), List.of(), List.of(new SubagentBinding("mute", "  ")));

    assertTrue(result.contains("<description>(no description)</description>"), result);
  }

  /** task 委派指令中的默认 maxTurns 来自 SubagentConfig。 */
  @Test
  void rendersTaskInstructionsWithConfiguredDefaultMaxTurns() {
    String result =
        composer.compose(
            "body", none(), List.of(), List.of(new SubagentBinding("researcher", "Do research.")));

    assertTrue(result.contains("The default is `" + DEFAULT_MAX_TURNS + "`"), result);
    assertFalse(result.contains("${defaultMaxTurns}"), result);
  }

  /** system prompt 每次组合都读取最新 policy，不能复用 descriptor 的冻结 schema 快照。 */
  @Test
  void rendersLiveDefaultMaxTurnsInTaskInstructions() {
    AtomicReference<SubagentConfig> liveConfig =
        new AtomicReference<>(new SubagentConfig(2, 10, 0, Duration.ZERO, DEFAULT_MAX_TURNS));
    AgentPromptComposer liveComposer = new AgentPromptComposer(liveConfig::get);
    List<SubagentBinding> subagents = List.of(new SubagentBinding("researcher", "Do research."));

    String initial = liveComposer.compose("body", none(), List.of(), subagents);
    liveConfig.set(new SubagentConfig(2, 10, 0, Duration.ZERO, 13));
    String updated = liveComposer.compose("body", none(), List.of(), subagents);

    assertTrue(initial.contains("The default is `" + DEFAULT_MAX_TURNS + "`"), initial);
    assertTrue(updated.contains("The default is `13`"), updated);
    assertFalse(updated.contains("The default is `" + DEFAULT_MAX_TURNS + "`"), updated);
  }

  /** Agent 正文只替换 date/workspace/cwd；未知或未闭合占位符原文保留，不把 shell 示例打成规划失败。 */
  @Test
  void rendersDeclaredAgentBodyVariablesOnly() {
    String withBoth = "Today is ${date} in ${workspace}.";
    String bodyOnly = "No placeholders here.";
    String dateOnly = "Today is ${date}.";
    String mixed = "cwd=${cwd} keep ${JAVA_HOME} and ${unterminated";
    EnvironmentBinding binding = TestEnvironmentBindings.binding("env");
    CurrentEnvironmentContext selected =
        new CurrentEnvironmentContext(binding, null, LocalDate.of(2026, 8, 9), null);

    String rendered = composer.compose(withBoth, selected, List.of(), List.of());
    assertTrue(rendered.startsWith("Today is 2026-08-09 in ."), rendered);

    String unselectedDate = composer.compose(dateOnly, none(), List.of(), List.of());
    assertTrue(unselectedDate.startsWith("Today is 2026-08-09."), unselectedDate);

    String renderedBodyOnly = composer.compose(bodyOnly, none(), List.of(), List.of());
    assertTrue(renderedBodyOnly.startsWith("No placeholders here."), renderedBodyOnly);

    String renderedMixed = composer.compose(mixed, selected, List.of(), List.of());
    assertTrue(
        renderedMixed.startsWith("cwd=. keep ${JAVA_HOME} and ${unterminated"), renderedMixed);
  }

  /** skills/subagents 列表不可为空引用。 */
  @Test
  void rejectsNullLists() {
    assertThrows(
        NullPointerException.class, () -> composer.compose("body", null, List.of(), List.of()));
    assertThrows(
        NullPointerException.class, () -> composer.compose("body", none(), null, List.of()));
    assertThrows(
        NullPointerException.class, () -> composer.compose("body", none(), List.of(), null));
  }

  private static CurrentEnvironmentContext none() {
    return new CurrentEnvironmentContext(null, null, LocalDate.of(2026, 8, 9), null);
  }

  private static void assertNoLegacyFields(String text) {
    assertFalse(text.contains("- platform:"));
    assertFalse(text.contains("- host:"));
    assertFalse(text.contains("- architecture:"));
  }
}
