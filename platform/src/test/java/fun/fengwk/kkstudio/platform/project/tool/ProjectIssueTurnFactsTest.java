package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/** Issue Agent 上下文段的渲染契约：只渲染本 Thread 自身的当前事实，并显式声明交接工具与合法目标。 */
class ProjectIssueTurnFactsTest {

  private static final UUID ISSUE_ID = new UUID(0L, 1L);
  private static final UUID PROJECT_ID = new UUID(0L, 2L);
  private static final UUID RUN_ID = new UUID(0L, 3L);

  private static ProjectIssueTurnFacts facts(
      String description, String instructions, List<String> nextStates, String environmentName) {
    return new ProjectIssueTurnFacts(
        ISSUE_ID,
        PROJECT_ID,
        RUN_ID,
        12L,
        "修复渲染缺陷",
        description,
        "DESIGN",
        "设计",
        instructions,
        nextStates,
        environmentName,
        "designer");
  }

  /** 测试意图：上下文段必须携带稳定的归属身份、当前阶段职责与合法交接目标，模型据此才能正确请求交接。 */
  @Test
  void rendersStableIdentityRequirementsAndHandoffProtocol() {
    ProjectIssueTurnFacts facts = facts("验收描述", "完成可交付方案", List.of("REVIEW", "DONE"), "env-a");

    String text = facts.contextSection();

    assertTrue(text.startsWith("# Issue Agent Context\n\n"), text);
    assertTrue(text.contains("- issue_id: " + ISSUE_ID), text);
    assertTrue(text.contains("- project_id: " + PROJECT_ID), text);
    assertTrue(text.contains("- run_id: " + RUN_ID), text);
    assertTrue(text.contains("- stage: DESIGN (设计)"), text);
    assertTrue(text.contains("- agent_name: designer"), text);
    assertTrue(text.contains("## Current Issue"), text);
    assertTrue(text.contains("#12 修复渲染缺陷"), text);
    assertTrue(text.contains("验收描述"), text);
    assertTrue(text.contains("## Stage Instructions"), text);
    assertTrue(text.contains("完成可交付方案"), text);
    assertTrue(text.contains("`" + IssueTransitionTool.NAME + "`"), text);
    assertTrue(text.contains("Allowed handoff targets: REVIEW, DONE."), text);
  }

  /** 测试意图：空缺的可选事实（描述、阶段指令、下一阶段）必须显式标注而不是留下悬空标题。 */
  @Test
  void rendersExplicitPlaceholdersForAbsentOptionalFacts() {
    ProjectIssueTurnFacts facts = facts(null, null, List.of(), null);

    String text = facts.contextSection();

    assertTrue(text.contains("(this stage declares no additional instructions)"), text);
    assertTrue(text.contains("This stage declares no legal next stage."), text);
    assertNull(facts.environmentName());
    assertEquals(List.of(), facts.nextStates());
  }

  /** 测试意图：空白描述与空白指令等同于缺省，绝不渲染只有空白的段。 */
  @Test
  void treatsBlankTextAsAbsent() {
    ProjectIssueTurnFacts facts = facts("   ", "  ", List.of("REVIEW"), null);

    String text = facts.contextSection();

    assertTrue(text.contains("#12 修复渲染缺陷"), text);
    assertTrue(text.contains("(this stage declares no additional instructions)"), text);
  }
}
