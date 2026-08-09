package fun.fengwk.kkstudio.core.ai.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

  /** 空正文 + 空 capability 仍输出严格的 current environment 块。 */
  @Test
  void alwaysComposesCurrentEnvironmentBlock() {
    String expected =
        "<current_environment>\n"
            + "  <name>none</name>\n"
            + "  <status>none</status>\n"
            + "  <operating_system>none</operating_system>\n"
            + "  <working_directory>none</working_directory>\n"
            + "  <current_date>2026-08-09</current_date>\n"
            + "  <current_time>04:05:06</current_time>\n"
            + "  <time_zone>UTC</time_zone>\n"
            + "</current_environment>";
    assertEquals(expected, composer.compose(null, none(), List.of(), List.of()));
    assertEquals(expected, composer.compose("   ", none(), List.of(), List.of()));
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

  /** skill 与 subagent 的 name/description 都做 XML 转义，避免破坏 available 列表结构。 */
  @Test
  void escapesXmlInEntries() {
    String result =
        composer.compose(
            null,
            ready(),
            List.of(skill("a&b", "uses <angle> and \"quotes\" and 'apos'")),
            List.of(new SubagentBinding("x<y>", "desc & more")));

    assertTrue(result.contains("<name>a&amp;b</name>"), result);
    assertTrue(
        result.contains(
            "<description>uses &lt;angle&gt; and &quot;quotes&quot; and &apos;apos&apos;</description>"),
        result);
    assertTrue(result.contains("<name>x&lt;y&gt;</name>"), result);
    assertTrue(result.contains("<description>desc &amp; more</description>"), result);
    assertTrue(
        result.contains("<working_directory>/workspace/a&amp;b&lt;c&gt;</working_directory>"),
        result);
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

  /** 冻结上下文拒绝状态、名称、metadata 与时间区域不一致，避免渲染自相矛盾的受信任块。 */
  @Test
  void validatesCurrentEnvironmentContextInvariants() {
    ZonedDateTime utc = ZonedDateTime.of(2026, 8, 9, 4, 5, 6, 0, ZoneId.of("UTC"));
    DaemonEnvironmentInfo environment =
        new DaemonEnvironmentInfo(DaemonOperatingSystem.LINUX, "/workspace", "Asia/Shanghai");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CurrentEnvironmentContext(
                new EnvironmentName("env"), CurrentEnvironmentContext.Status.NONE, null, utc));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CurrentEnvironmentContext(
                null, CurrentEnvironmentContext.Status.NONE, environment, utc));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CurrentEnvironmentContext(
                null, CurrentEnvironmentContext.Status.UNAVAILABLE, null, utc));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CurrentEnvironmentContext(
                new EnvironmentName("env"), CurrentEnvironmentContext.Status.READY, null, utc));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CurrentEnvironmentContext(
                new EnvironmentName("env"),
                CurrentEnvironmentContext.Status.UNAVAILABLE,
                environment,
                utc));
    assertThrows(
        NullPointerException.class, () -> new CurrentEnvironmentContext(null, null, null, utc));
    assertThrows(
        NullPointerException.class,
        () ->
            new CurrentEnvironmentContext(null, CurrentEnvironmentContext.Status.NONE, null, null));
  }

  private static CurrentEnvironmentContext none() {
    return new CurrentEnvironmentContext(
        null,
        CurrentEnvironmentContext.Status.NONE,
        null,
        ZonedDateTime.of(2026, 8, 9, 4, 5, 6, 987_000_000, ZoneId.of("UTC")));
  }

  private static CurrentEnvironmentContext ready() {
    DaemonEnvironmentInfo environment =
        new DaemonEnvironmentInfo(
            DaemonOperatingSystem.LINUX, "/workspace/a&b<c>", "Asia/Shanghai");
    return new CurrentEnvironmentContext(
        new EnvironmentName("env"),
        CurrentEnvironmentContext.Status.READY,
        environment,
        ZonedDateTime.of(2026, 8, 9, 12, 34, 56, 999_000_000, ZoneId.of("Asia/Shanghai")));
  }
}
