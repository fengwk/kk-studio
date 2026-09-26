package fun.fengwk.kkstudio.project.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link IssueStateTransitions} 的正常边、业务阻塞/恢复与 DONE 重开契约。 */
class IssueStateTransitionsTest {

  private final IssueStateTransitions transitions =
      new IssueStateTransitions(ProjectDomainFixtures.documentedWorkflow());

  /** 正常边只认 workflow 的 next 白名单：返工环合法，未声明边、BLOCKED 与未知编码都不合法。 */
  @Test
  void allowsOnlyDeclaredNormalEdges() {
    assertTrue(transitions.canTransition(code("INIT"), code("DESIGN")));
    assertTrue(transitions.canTransition(code("DESIGN"), code("REVIEW")));
    assertTrue(transitions.canTransition(code("REVIEW"), code("DESIGN")));
    assertTrue(transitions.canTransition(code("REVIEW"), code("DONE")));
    assertFalse(transitions.canTransition(code("DESIGN"), code("DONE")));
    assertFalse(transitions.canTransition(code("DESIGN"), code("BLOCKED")));
    assertFalse(transitions.canTransition(code("GHOST"), code("DONE")));
    assertFalse(transitions.canTransition(null, code("DONE")));
    assertFalse(transitions.canTransition(code("DESIGN"), null));

    assertEquals(code("REVIEW"), transitions.requireTransition(code("DESIGN"), code("REVIEW")));
    assertThrows(
        IllegalArgumentException.class,
        () -> transitions.requireTransition(code("DESIGN"), code("DONE")));
    assertThrows(NullPointerException.class, () -> new IssueStateTransitions(null));
  }

  /** 停用编码只保留历史语义，不能作为正常边目标。 */
  @Test
  void rejectsTransitionsIntoDisabledStages() {
    IssueStateTransitions disabled =
        new IssueStateTransitions(ProjectDomainFixtures.disabledStageWorkflow());

    assertFalse(disabled.canTransition(code("INIT"), code("WORK")));
    assertThrows(
        IllegalArgumentException.class,
        () -> disabled.requireTransition(code("INIT"), code("WORK")));
  }

  /** 业务阻塞必须记录原阶段与非空原因；BLOCKED 与 DONE 本身不能再被阻塞。 */
  @Test
  void blocksOnlyWorkingStagesWithReason() {
    assertEquals(code("INIT"), transitions.requireBlock(code("INIT"), "等需求确认"));
    assertEquals(code("DESIGN"), transitions.requireBlock(code("DESIGN"), "等需求确认"));
    assertThrows(
        IllegalArgumentException.class, () -> transitions.requireBlock(code("BLOCKED"), "等需求确认"));
    assertThrows(
        IllegalArgumentException.class, () -> transitions.requireBlock(code("DONE"), "等需求确认"));
    assertThrows(
        IllegalArgumentException.class, () -> transitions.requireBlock(code("GHOST"), "等需求确认"));
    assertThrows(
        IllegalArgumentException.class, () -> transitions.requireBlock(code("DESIGN"), " "));
    assertThrows(NullPointerException.class, () -> transitions.requireBlock(code("DESIGN"), null));
  }

  /** 恢复只能显式回到仍然启用的阻塞前阶段：BLOCKED、DONE 与已停用阶段都不是有效恢复点。 */
  @Test
  void recoversOnlyIntoEnabledStages() {
    assertEquals(code("INIT"), transitions.requireRecover(code("INIT")));
    assertEquals(code("DESIGN"), transitions.requireRecover(code("DESIGN")));
    assertThrows(IllegalArgumentException.class, () -> transitions.requireRecover(code("BLOCKED")));
    assertThrows(IllegalArgumentException.class, () -> transitions.requireRecover(code("DONE")));
    assertThrows(IllegalArgumentException.class, () -> transitions.requireRecover(code("GHOST")));
    IssueStateTransitions disabled =
        new IssueStateTransitions(ProjectDomainFixtures.disabledStageWorkflow());
    assertThrows(IllegalArgumentException.class, () -> disabled.requireRecover(code("WORK")));
  }

  /** DONE 只能由显式重开操作回到 INIT，其他阶段不能重开。 */
  @Test
  void reopensOnlyDoneIntoInit() {
    assertEquals(code("INIT"), transitions.requireReopen(code("DONE")));
    assertThrows(IllegalArgumentException.class, () -> transitions.requireReopen(code("INIT")));
    assertThrows(IllegalArgumentException.class, () -> transitions.requireReopen(null));
  }

  private static ProjectStateCode code(String value) {
    return ProjectStateCode.of(value);
  }
}
