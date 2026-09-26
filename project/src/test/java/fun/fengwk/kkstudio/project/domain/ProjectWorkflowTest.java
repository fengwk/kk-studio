package fun.fengwk.kkstudio.project.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** {@link ProjectWorkflow} 聚合不变量：保留值、编码唯一、边合法与文档要求的可达性。 */
class ProjectWorkflowTest {

  /** 状态数组顺序即展示顺序，工作阶段查询与按编码查找都基于同一份配置。 */
  @Test
  void keepsDeclaredOrderAndExposesStageLookups() {
    ProjectWorkflow workflow = ProjectDomainFixtures.documentedWorkflow();

    assertEquals(
        List.of("INIT", "DESIGN", "REVIEW", "BLOCKED", "DONE"),
        workflow.states().stream().map(state -> state.state().value()).toList());
    assertEquals(
        List.of("DESIGN", "REVIEW"),
        workflow.workStages().stream().map(state -> state.state().value()).toList());
    assertTrue(workflow.find(ProjectStateCode.of("REVIEW")).isPresent());
    assertFalse(workflow.find(ProjectStateCode.of("GHOST")).isPresent());
    assertEquals("designer", workflow.require(ProjectStateCode.of("DESIGN")).agent());
    assertThrows(
        IllegalArgumentException.class, () -> workflow.require(ProjectStateCode.of("GHOST")));
    assertThrows(NullPointerException.class, () -> workflow.find(null));
  }

  /** 空配置、重复编码与缺失保留状态都必须拒绝：INIT/BLOCKED/DONE 是固定保留值。 */
  @Test
  void requiresUniqueCodesAndReservedStates() {
    ProjectWorkflowState init = ProjectDomainFixtures.reservedState("INIT", "待开始", "WORK");
    ProjectWorkflowState work = ProjectDomainFixtures.agentState("WORK", "worker", List.of("DONE"));
    ProjectWorkflowState blocked = ProjectDomainFixtures.reservedState("BLOCKED", "业务阻塞");
    ProjectWorkflowState done = ProjectDomainFixtures.reservedState("DONE", "完成");

    assertThrows(NullPointerException.class, () -> new ProjectWorkflow(null));
    assertThrows(IllegalArgumentException.class, () -> new ProjectWorkflow(List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectWorkflow(List.of(init, init, blocked, done)));
    assertThrows(
        IllegalArgumentException.class, () -> new ProjectWorkflow(List.of(work, blocked, done)));
    assertThrows(
        IllegalArgumentException.class, () -> new ProjectWorkflow(List.of(init, work, done)));
    assertThrows(
        IllegalArgumentException.class, () -> new ProjectWorkflow(List.of(init, work, blocked)));
  }

  /** 边只能指向已声明状态，且不允许自身边、重复目标或指向 BLOCKED。 */
  @Test
  void rejectsIllegalEdges() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectWorkflow(
                List.of(
                    ProjectDomainFixtures.reservedState("INIT", "待开始", "GHOST"),
                    ProjectDomainFixtures.reservedState("BLOCKED", "业务阻塞"),
                    ProjectDomainFixtures.reservedState("DONE", "完成"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectDomainFixtures.reservedState("INIT", "待开始", "INIT"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectDomainFixtures.reservedState("INIT", "待开始", "BLOCKED"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectDomainFixtures.reservedState("INIT", "待开始", "DONE", "DONE"));
  }

  /** 文档要求的可达性：每个启用工作阶段必须能从 INIT 沿正常边到达，且存在到 DONE 的正常路径。 */
  @Test
  void requiresEnabledStagesReachableAndPathToDone() {
    // 启用阶段没有任何入口
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectWorkflow(
                List.of(
                    ProjectDomainFixtures.reservedState("INIT", "待开始", "DONE"),
                    ProjectDomainFixtures.agentState("ORPHAN", "worker", List.of("DONE")),
                    ProjectDomainFixtures.reservedState("BLOCKED", "业务阻塞"),
                    ProjectDomainFixtures.reservedState("DONE", "完成"))));
    // 只有保留状态且 INIT 没有正常边：不存在到 DONE 的正常路径
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectWorkflow(
                List.of(
                    ProjectDomainFixtures.reservedState("INIT", "待开始"),
                    ProjectDomainFixtures.reservedState("BLOCKED", "业务阻塞"),
                    ProjectDomainFixtures.reservedState("DONE", "完成"))));
    // 唯一通路经过已声明但停用的阶段时仍算可达：停用只表示不再派发新 Run
    assertDoesNotThrow(
        () ->
            new ProjectWorkflow(
                List.of(
                    ProjectDomainFixtures.reservedState("INIT", "待开始", "RETIRED"),
                    ProjectDomainFixtures.manualState("RETIRED", false, List.of("DONE")),
                    ProjectDomainFixtures.reservedState("BLOCKED", "业务阻塞"),
                    ProjectDomainFixtures.reservedState("DONE", "完成"))));
  }

  /** 停用阶段只保留历史语义：既不要求可达，也不要求能到 DONE，但必须先声明边才合法。 */
  @Test
  void keepsDisabledStagesHistorical() {
    ProjectWorkflow workflow =
        new ProjectWorkflow(
            List.of(
                ProjectDomainFixtures.reservedState("INIT", "待开始", "WORK"),
                ProjectDomainFixtures.agentState("WORK", "worker", List.of("DONE")),
                ProjectDomainFixtures.reservedState("BLOCKED", "业务阻塞"),
                ProjectDomainFixtures.manualState("RETIRED", false, List.of("WORK")),
                ProjectDomainFixtures.reservedState("DONE", "完成")));

    assertEquals(5, workflow.states().size());
    assertEquals(
        List.of("WORK", "RETIRED"),
        workflow.workStages().stream().map(state -> state.state().value()).toList());
    assertFalse(workflow.require(ProjectStateCode.of("RETIRED")).enabled());
  }

  /** 允许返工环：可达性只要求启用阶段可达与存在到 DONE 的路径，不禁止闭环或暂不外出的返工集合。 */
  @Test
  void allowsReworkLoops() {
    ProjectWorkflow workflow =
        new ProjectWorkflow(
            List.of(
                ProjectDomainFixtures.reservedState("INIT", "待开始", "DONE", "A"),
                ProjectDomainFixtures.agentState("A", "worker", List.of("B")),
                ProjectDomainFixtures.agentState("B", "worker", List.of("A", "DONE")),
                ProjectDomainFixtures.reservedState("BLOCKED", "业务阻塞"),
                ProjectDomainFixtures.reservedState("DONE", "完成")));

    assertEquals(
        ProjectDomainFixtures.codes("A", "B"),
        workflow.workStages().stream().map(ProjectWorkflowState::state).toList());
  }
}
