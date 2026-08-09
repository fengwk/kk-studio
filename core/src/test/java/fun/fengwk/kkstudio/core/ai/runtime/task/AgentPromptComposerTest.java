package fun.fengwk.kkstudio.core.ai.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/** Agent 正文 + current environment + skills + subagents 唯一 system prompt 边界的组合语义。 */
class AgentPromptComposerTest {

  private static final int DEFAULT_MAX_TURNS = 7;

  private final AgentPromptComposer composer =
      new AgentPromptComposer(
          new SubagentConfig(
              2, 10, null, Duration.ZERO, DEFAULT_MAX_TURNS, Duration.ofMillis(100)));

  private static SkillBinding skill(String name, String description) {
    return new SkillBinding(name, description, null);
  }

  /** 空正文 + 无 Environment 仍输出严格、稳定且只有四个字段的 current environment 块。 */
  @Test
  void alwaysComposesExactCurrentEnvironmentBlock() {
    String expected =
        "<current_environment>\n"
            + "- name: none\n"
            + "- system: none\n"
            + "- date: 2026-08-09\n"
            + "- note: none\n"
            + "</current_environment>";

    assertEquals(expected, composer.compose(null, none(), List.of(), List.of()));
    assertEquals(expected, composer.compose("   ", none(), List.of(), List.of()));
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
    CurrentEnvironmentContext environment =
        new CurrentEnvironmentContext(
            name,
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

  /** 冻结上下文只允许无选择、选中但无 metadata、或 OS/note 成对存在三种形态，且日期必填。 */
  @Test
  void validatesCurrentEnvironmentContextInvariants() {
    LocalDate date = LocalDate.of(2026, 8, 9);
    EnvironmentName name = new EnvironmentName("env");

    assertEquals(new CurrentEnvironmentContext(null, null, date, null), none());
    CurrentEnvironmentContext selectedWithoutMetadata =
        new CurrentEnvironmentContext(name, null, date, null);
    assertEquals(name, selectedWithoutMetadata.name());
    assertEquals(null, selectedWithoutMetadata.operatingSystem());
    assertEquals(null, selectedWithoutMetadata.note());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, DaemonOperatingSystem.LINUX, date, "note"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(name, DaemonOperatingSystem.LINUX, date, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(name, null, date, "note"));
    assertThrows(
        NullPointerException.class, () -> new CurrentEnvironmentContext(null, null, null, null));
  }

  /** 模型可见 note 在冻结上下文边界再次严格校验，任何调用方都不能绕过 READY/CLI 的约束。 */
  @Test
  void validatesCurrentEnvironmentContextNote() {
    LocalDate date = LocalDate.of(2026, 8, 9);
    EnvironmentName name = new EnvironmentName("env");

    assertInvalidContextNote(name, date, "", "note must not be blank");
    assertInvalidContextNote(name, date, " ", "note must not be blank");
    assertInvalidContextNote(name, date, " leading", "note must not have surrounding whitespace");
    assertInvalidContextNote(name, date, "trailing ", "note must not have surrounding whitespace");
    assertInvalidContextNote(
        name, date, "first\nsecond", "note must not contain ISO control characters");
    assertInvalidContextNote(name, date, "first\u2028second", "note must be a single line");
    assertInvalidContextNote(
        name, date, "control\u0007value", "note must not contain ISO control characters");
    assertInvalidContextNote(
        name,
        date,
        "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS + 1),
        "note exceeds " + DaemonEnvironmentInfo.MAX_NOTE_CHARS + " characters");

    String maximumLength = "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS);
    assertEquals(
        maximumLength,
        new CurrentEnvironmentContext(name, DaemonOperatingSystem.LINUX, date, maximumLength)
            .note());
  }

  private static CurrentEnvironmentContext none() {
    return new CurrentEnvironmentContext(null, null, LocalDate.of(2026, 8, 9), null);
  }

  private static void assertInvalidContextNote(
      EnvironmentName name, LocalDate date, String note, String expectedMessage) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new CurrentEnvironmentContext(name, DaemonOperatingSystem.LINUX, date, note));
    assertEquals(expectedMessage, error.getMessage());
  }

  private static void assertNoLegacyFields(String prompt) {
    assertFalse(prompt.contains("status"), prompt);
    assertFalse(prompt.contains("working_directory"), prompt);
    assertFalse(prompt.contains("current_time"), prompt);
    assertFalse(prompt.contains("time_zone"), prompt);
  }
}
