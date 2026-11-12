package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.testing.TestEnvironments;

import java.util.List;

/** 实际能力驱动 system prompt 的段落顺序、出现条件与黄金文件测试。 */
class AgentPromptComposerTest {

  private static final int DEFAULT_MAX_TURNS = 7;
  private static final String GOLDEN_ROOT = "fun/fengwk/kkstudio/platform/harness/task/prompts/";

  private final AgentPromptComposer composer =
      new AgentPromptComposer(() -> new SubagentConfig(2, 10, 0, DEFAULT_MAX_TURNS));

  /** 有环境且注入 read/edit/grep/bash：Environment 路径说明、环境事实、技能与子 Agent 全部出现。 */
  @Test
  void rendersEnvironmentWithAllTools() {
    AgentPromptInput input =
        input(
            "You are a senior engineer.",
            environment(),
            List.of("read", "edit", "grep", "bash"),
            List.of(skill("dev", "Development conventions.")),
            List.of(new SubagentBinding("explorer", "Read-only repository exploration.")));

    assertGolden("environment-all-tools.golden", input);
  }

  /** 无 Environment 时读取工具仍可见，本地路径说明明确要求选择 Environment。 */
  @Test
  void rendersWithoutEnvironment() {
    AgentPromptInput input =
        input(null, none(), List.of("read", "write", "grep"), List.of(), List.of());

    assertGolden("no-environment.golden", input);
  }

  /** 只读 Agent 不出现写入说明，且不注入与当前能力无关的 temp_dir。 */
  @Test
  void rendersReadOnlyAgentWithoutWriteInstructions() {
    AgentPromptInput input =
        input(null, environment(), List.of("read", "grep"), List.of(), List.of());

    String rendered = assertGolden("read-only.golden", input);
    assertFalse(rendered.contains("Keep them short and current"), rendered);
    assertFalse(rendered.contains("<temp_dir>"), rendered);
  }

  /** 无工具 Agent（例如压缩）：只有 harness 段，不出现 paths/environment/skills/subagents。 */
  @Test
  void rendersOnlyHarnessForAgentWithoutTools() {
    AgentPromptInput input = input(null, none(), List.of(), List.of(), List.of());

    String rendered = assertGolden("no-tools.golden", input);
    assertEquals("[harness]", rendered.lines().findFirst().orElseThrow());
    assertFalse(rendered.contains("[paths]"), rendered);
    assertFalse(rendered.contains("[environment]"), rendered);
  }

  /** 技能只按配置顺序提供名称与描述，委派说明与清单出现。 */
  @Test
  void rendersSkillsAndSubagentsWithoutEnvironment() {
    AgentPromptInput input =
        input(
            null,
            none(),
            List.of("read"),
            List.of(skill("dev", "Development conventions.")),
            List.of(new SubagentBinding("explorer", "Read-only repository exploration.")));

    String rendered = assertGolden("skills-subagents.golden", input);
    assertTrue(rendered.contains("<name>dev</name>"), rendered);
    assertTrue(rendered.contains("<description>Development conventions.</description>"), rendered);
    assertFalse(rendered.contains("<path>"), rendered);
    assertTrue(rendered.contains("The default is `" + DEFAULT_MAX_TURNS + "`"), rendered);
  }

  /** 特殊字符在 prompt XML 中正确转义。 */
  @Test
  void escapesXmlFieldsAndPreservesEnvironmentFacts() {
    CurrentEnvironmentContext environment =
        new CurrentEnvironmentContext(
            TestEnvironments.environmentId("env-special"),
            "nas<&>",
            DaemonOperatingSystem.WSL,
            "dev&user",
            "/home/<dev>",
            "/tmp/<dev>",
            "Use <mount> & \"commands\" from 'Windows'.");
    AgentPromptInput input =
        input(
            null,
            environment,
            List.of("read"),
            List.of(skill("a&b", "uses <angle> and \"quotes\" and 'apos'")),
            List.of(new SubagentBinding("x<y>", "desc & more")));

    String rendered = assertGolden("special-characters.golden", input);
    assertTrue(rendered.contains("<name>nas&lt;&amp;&gt;</name>"), rendered);
    assertTrue(rendered.contains("<user>dev&amp;user</user>"), rendered);
    assertTrue(rendered.contains("<home>/home/&lt;dev&gt;</home>"), rendered);
    assertTrue(rendered.contains("<name>a&amp;b</name>"), rendered);
    assertTrue(rendered.contains("<name>x&lt;y&gt;</name>"), rendered);
  }

  /** 空描述没有在 Java 中拼接固定文案，而是由模板条件分支输出模型可见回退说明。 */
  @Test
  void rendersSubagentDescriptionFallbackFromTemplate() {
    AgentPromptInput input =
        input(null, none(), List.of(), List.of(), List.of(new SubagentBinding("explorer", "  ")));

    String rendered = composer.compose(input);

    assertTrue(rendered.contains("<description>(no description)</description>"), rendered);
  }

  /** Agent 正文原样插入：不执行 ${date}、{{...}} 或 XML 转义。 */
  @Test
  void insertsAgentBodyVerbatim() {
    AgentPromptInput input =
        input(
            "Today is ${date}. Keep {{not_a_template}} and <&> as written.",
            none(),
            List.of(),
            List.of(),
            List.of());

    String rendered = assertGolden("agent-body.golden", input);
    assertTrue(
        rendered.contains("Today is ${date}. Keep {{not_a_template}} and <&> as written."),
        rendered);
  }

  /** 工具名与列表不可为空引用。 */
  @Test
  void rejectsNullInput() {
    assertThrows(NullPointerException.class, () -> composer.compose(null));
  }

  private static AgentPromptInput input(
      String agentInstructions,
      CurrentEnvironmentContext environment,
      List<String> toolNames,
      List<SkillPromptEntry> skills,
      List<SubagentBinding> subagents) {
    return new AgentPromptInput(agentInstructions, environment, toolNames, skills, subagents);
  }

  private static CurrentEnvironmentContext none() {
    return CurrentEnvironmentContext.none();
  }

  private static CurrentEnvironmentContext environment() {
    return new CurrentEnvironmentContext(
        TestEnvironments.environmentId("env-1"),
        "NAS",
        DaemonOperatingSystem.LINUX,
        "fengwk",
        "/home/dev",
        "/tmp",
        "Linux environment.");
  }

  private static SkillPromptEntry skill(String name, String description) {
    return new SkillPromptEntry(name, description);
  }

  private String assertGolden(String name, AgentPromptInput input) {
    String rendered = composer.compose(input);
    assertEquals(golden(name), rendered);
    return rendered;
  }

  private static String golden(String name) {
    return PromptRenderer.read(GOLDEN_ROOT + name).stripTrailing();
  }
}
