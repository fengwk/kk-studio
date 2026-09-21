package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.platform.testing.TestEnvironments;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Agent 正文 + current environment + skills + subagents 唯一 system prompt 边界的组合语义。 */
class AgentPromptComposerTest {

  private static final int DEFAULT_MAX_TURNS = 7;

  private final AgentPromptComposer composer =
      new AgentPromptComposer(() -> new SubagentConfig(2, 10, 0, Duration.ZERO, DEFAULT_MAX_TURNS));

  private static SkillPromptEntry skill(String name, String description) {
    return new SkillPromptEntry(
        name, description, "kkstudio:/skills/test-package/" + name + "/SKILL.md");
  }

  /** 无 Environment 时只渲染 name: none 与 date；绝不出现任何宿主事实或 root/workspace/cwd 语义。 */
  @Test
  void rendersNoneEnvironmentNameAndPlatformDate() {
    String expected =
        "<current_environment>\n"
            + "- name: none\n"
            + "- date: 2026-08-09\n"
            + "</current_environment>";

    String rendered = composer.compose(null, none(), List.of(), List.of());
    assertEquals(expected, rendered);
    assertEquals(expected, composer.compose("   ", none(), List.of(), List.of()));
    assertFalse(rendered.contains("- system:"), rendered);
    assertFalse(rendered.contains("- user:"), rendered);
    assertFalse(rendered.contains("- home:"), rendered);
    assertFalse(rendered.contains("- note:"), rendered);
    assertFalse(rendered.contains("root"), rendered);
    assertFalse(rendered.contains("workspace"), rendered);
    assertFalse(rendered.contains("cwd"), rendered);
  }

  /** Environment 宿主事实严格按 name、system、user、home、date、note 顺序渲染。 */
  @Test
  void rendersEnvironmentFactsInStableOrder() {
    CurrentEnvironmentContext environment =
        new CurrentEnvironmentContext(
            TestEnvironments.environmentId("env-1"),
            "nas-dev",
            DaemonOperatingSystem.LINUX,
            "dev-user",
            "/home/dev",
            LocalDate.of(2026, 8, 9),
            "Linux environment.");

    String rendered = composer.compose(null, environment, List.of(), List.of());

    assertEquals(
        "<current_environment>\n"
            + "- name: nas-dev\n"
            + "- system: linux\n"
            + "- user: dev-user\n"
            + "- home: /home/dev\n"
            + "- date: 2026-08-09\n"
            + "- note: Linux environment.\n"
            + "</current_environment>",
        rendered);
  }

  /** 可选宿主事实缺失时整行省略，但 name 与 date 始终存在。 */
  @Test
  void omitsUnavailableHostFacts() {
    CurrentEnvironmentContext environment =
        new CurrentEnvironmentContext(
            TestEnvironments.environmentId("env-1"),
            "nas-dev",
            null,
            null,
            null,
            LocalDate.of(2026, 8, 9),
            null);

    String rendered = composer.compose(null, environment, List.of(), List.of());

    assertEquals(
        "<current_environment>\n"
            + "- name: nas-dev\n"
            + "- date: 2026-08-09\n"
            + "</current_environment>",
        rendered);
  }

  /** 宿主事实即使字面等于 none 也是合法取值，必须原样渲染，绝不与未选择环境的 name: none 哨兵混淆。 */
  @Test
  void rendersFactualNoneValues() {
    CurrentEnvironmentContext environment =
        new CurrentEnvironmentContext(
            TestEnvironments.environmentId("env-1"),
            "nas-dev",
            null,
            "none",
            "/home/none",
            LocalDate.of(2026, 8, 9),
            "none");

    String rendered = composer.compose(null, environment, List.of(), List.of());

    assertEquals(
        "<current_environment>\n"
            + "- name: nas-dev\n"
            + "- user: none\n"
            + "- home: /home/none\n"
            + "- date: 2026-08-09\n"
            + "- note: none\n"
            + "</current_environment>",
        rendered);
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
    assertTrue(result.contains("read"), result);
    assertFalse(result.contains("${skills}"), result);
    assertFalse(result.contains("${subagents}"), result);
  }

  /** Environment、skill 与 subagent 动态值在进入 XML 模板前全部转义。 */
  @Test
  void escapesXmlInDynamicValues() {
    CurrentEnvironmentContext environment =
        new CurrentEnvironmentContext(
            TestEnvironments.environmentId("env-1"),
            "env-1<&>",
            DaemonOperatingSystem.WSL,
            "dev&user",
            "/home/<dev>",
            LocalDate.of(2026, 8, 9),
            "Use <mount> & \"commands\" from 'Windows'.");

    String result =
        composer.compose(
            null,
            environment,
            List.of(skill("a&b", "uses <angle> and \"quotes\" and 'apos'")),
            List.of(new SubagentBinding("x<y>", "desc & more")));

    assertTrue(result.contains("- name: env-1&lt;&amp;&gt;"), result);
    assertTrue(result.contains("- user: dev&amp;user"), result);
    assertTrue(result.contains("- home: /home/&lt;dev&gt;"), result);
    assertTrue(
        result.contains(
            "- note: Use &lt;mount&gt; &amp; &quot;commands&quot; from &apos;Windows&apos;."),
        result);
    assertTrue(result.contains("<name>a&amp;b</name>"), result);
    assertTrue(
        result.contains(
            "<description>uses &lt;angle&gt; and &quot;quotes&quot; and &apos;apos&apos;</description>"),
        result);
    assertTrue(
        result.contains("<path>kkstudio:/skills/test-package/a&amp;b/SKILL.md</path>"), result);
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
    // ${workspace}/${cwd} 已不再是可替换变量：它们必须与其它未知占位符一样保持原文。
    String withBoth = "Today is ${date} in ${workspace}.";
    String bodyOnly = "No placeholders here.";
    String dateOnly = "Today is ${date}.";
    String mixed = "cwd=${cwd} keep ${JAVA_HOME} and ${unterminated";
    EnvironmentId binding = TestEnvironments.environmentId("env");
    CurrentEnvironmentContext selected =
        new CurrentEnvironmentContext(
            binding, "env", null, null, null, LocalDate.of(2026, 8, 9), null);

    String rendered = composer.compose(withBoth, selected, List.of(), List.of());
    assertTrue(rendered.startsWith("Today is 2026-08-09 in ${workspace}."), rendered);
    assertFalse(rendered.contains(".${workspace}"), rendered);

    String unselectedDate = composer.compose(dateOnly, none(), List.of(), List.of());
    assertTrue(unselectedDate.startsWith("Today is 2026-08-09."), unselectedDate);

    String renderedBodyOnly = composer.compose(bodyOnly, none(), List.of(), List.of());
    assertTrue(renderedBodyOnly.startsWith("No placeholders here."), renderedBodyOnly);

    String renderedMixed = composer.compose(mixed, selected, List.of(), List.of());
    assertTrue(
        renderedMixed.startsWith("cwd=${cwd} keep ${JAVA_HOME} and ${unterminated"), renderedMixed);
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
    return new CurrentEnvironmentContext(
        null, null, null, null, null, LocalDate.of(2026, 8, 9), null);
  }
}
