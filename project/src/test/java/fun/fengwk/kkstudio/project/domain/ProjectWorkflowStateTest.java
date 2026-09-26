package fun.fengwk.kkstudio.project.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/** {@link ProjectWorkflowState} 的保留状态与工作阶段契约。 */
class ProjectWorkflowStateTest {

  /** 保留状态只声明显示名与正常边：不能配置 Agent/Environment/instructions/额度，也不能停用。 */
  @Test
  void reservedStatesStayFixed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("INIT", "待开始", "worker", null, null, null, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("INIT", "待开始", null, "linux", null, null, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("INIT", "待开始", null, null, "做事", null, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("INIT", "待开始", null, null, null, 1, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("INIT", "待开始", null, null, null, null, false, "DONE"));
    // BLOCKED 与 DONE 不配置普通边；INIT 可以声明正常边
    assertThrows(IllegalArgumentException.class, () -> reserved("BLOCKED", "业务阻塞", "DONE"));
    assertThrows(IllegalArgumentException.class, () -> reserved("DONE", "完成", "INIT"));
    assertEquals(1, reserved("INIT", "待开始", "DONE").next().size());
  }

  /** 无 Agent 是人工阶段：不配置 Environment 和 Run 额度，也不自动跳过或产生 Run。 */
  @Test
  void manualStagesCarryNoEnvironmentOrBudget() {
    ProjectWorkflowState manual = custom("WORK", "工作", null, null, "做事", null, true, "DONE");
    assertFalse(manual.hasAgent());
    assertEquals("做事", manual.instructions());

    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", "工作", null, "linux", null, null, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", "工作", null, null, null, 2, true, "DONE"));
  }

  /** 有 Agent 的工作阶段必须给出正数 maxRuns；有 maxRuns 就必须有 Agent。 */
  @Test
  void agentStagesRequirePositiveMaxRuns() {
    ProjectWorkflowState stage = custom("WORK", "工作", "worker", "linux", "做事", 3, true, "DONE");
    assertTrue(stage.hasAgent());
    assertEquals("worker", stage.agent());
    assertEquals("linux", stage.environment());
    assertEquals(3, stage.maxRuns());

    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", "工作", "worker", null, null, null, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", "工作", "worker", null, null, 0, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", "工作", "worker", null, null, -1, true, "DONE"));
  }

  /** 显示名、Agent/Environment 规范名与 {code next} 形状在构造期固定，非法输入不能进入配置。 */
  @Test
  void rejectsInvalidNamesAndEdges() {
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", " ", null, null, null, null, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", " 工作 ", null, null, null, null, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", "工作", " ", null, null, 1, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", "工作", "a/b", null, null, 1, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", "工作", "a".repeat(65), null, null, 1, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", "工作", null, " ", null, null, true, "DONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> custom("WORK", "工作", null, null, " ", null, true, "DONE"));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectWorkflowState(
                ProjectStateCode.of("WORK"), "工作", null, null, null, null, true, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectWorkflowState(
                ProjectStateCode.of("WORK"),
                "工作",
                null,
                null,
                null,
                null,
                true,
                Arrays.asList(ProjectStateCode.of("DONE"), null)));
  }

  private static ProjectWorkflowState reserved(String code, String name, String... next) {
    return ProjectDomainFixtures.reservedState(code, name, next);
  }

  private static ProjectWorkflowState custom(
      String code,
      String name,
      String agent,
      String environment,
      String instructions,
      Integer maxRuns,
      boolean enabled,
      String... next) {
    return new ProjectWorkflowState(
        ProjectStateCode.of(code),
        name,
        agent,
        environment,
        instructions,
        maxRuns,
        enabled,
        ProjectDomainFixtures.codes(List.of(next)));
  }
}
