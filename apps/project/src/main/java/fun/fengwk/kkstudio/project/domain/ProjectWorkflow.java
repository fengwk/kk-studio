package fun.fengwk.kkstudio.project.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 项目工作流配置：状态数组顺序即展示顺序，{@code next} 是正常转移白名单。
 *
 * <p>保留状态 INIT/BLOCKED/DONE 必须存在，编码唯一；边只能指向已声明状态，且不允许自身边、重复目标或指向 BLOCKED。可达性只判定文档要求的两件事：每个启用工作阶段都能从
 * INIT 沿正常边到达，且存在一条到 DONE 的正常 路径；停用编码只保留历史语义，不参与这两项要求，但仍可作为已声明的中间节点出现在路径上。workflow 内的状态
 * 正确性由领域与统一服务负责，数据库只保证它是 object 且 {@code states} 是 array。
 */
public record ProjectWorkflow(List<ProjectWorkflowState> states) {

  public ProjectWorkflow {
    Objects.requireNonNull(states, "states");
    states = List.copyOf(states);
    if (states.isEmpty()) {
      throw new IllegalArgumentException("workflow must declare at least one state");
    }
    Map<ProjectStateCode, ProjectWorkflowState> index = indexStates(states);
    requireReservedStates(index);
    requireDeclaredEdges(index);
    requireUsableNormalGraph(index);
  }

  /** 工作阶段：按声明顺序返回非保留状态，停用阶段也在其中。 */
  public List<ProjectWorkflowState> workStages() {
    List<ProjectWorkflowState> stages = new ArrayList<>();
    for (ProjectWorkflowState state : states) {
      if (!ProjectWorkflowReservedState.isReserved(state.state())) {
        stages.add(state);
      }
    }
    return List.copyOf(stages);
  }

  /** 按编码查找状态。 */
  public Optional<ProjectWorkflowState> find(ProjectStateCode code) {
    Objects.requireNonNull(code, "code");
    for (ProjectWorkflowState state : states) {
      if (state.state().equals(code)) {
        return Optional.of(state);
      }
    }
    return Optional.empty();
  }

  /** 要求状态存在，否则非法：配置整体保存时用它校验保留值、引用与边。 */
  public ProjectWorkflowState require(ProjectStateCode code) {
    return find(code)
        .orElseThrow(() -> new IllegalArgumentException("workflow does not declare state " + code));
  }

  private static Map<ProjectStateCode, ProjectWorkflowState> indexStates(
      List<ProjectWorkflowState> states) {
    Map<ProjectStateCode, ProjectWorkflowState> index = new LinkedHashMap<>();
    for (ProjectWorkflowState state : states) {
      Objects.requireNonNull(state, "states element");
      if (index.putIfAbsent(state.state(), state) != null) {
        throw new IllegalArgumentException("workflow state code must be unique: " + state.state());
      }
    }
    return index;
  }

  private static void requireReservedStates(Map<ProjectStateCode, ProjectWorkflowState> index) {
    for (ProjectWorkflowReservedState reserved : ProjectWorkflowReservedState.values()) {
      if (!index.containsKey(reserved.code())) {
        throw new IllegalArgumentException("workflow must reserve state " + reserved.code());
      }
    }
  }

  private static void requireDeclaredEdges(Map<ProjectStateCode, ProjectWorkflowState> index) {
    for (ProjectWorkflowState state : index.values()) {
      for (ProjectStateCode next : state.next()) {
        if (!index.containsKey(next)) {
          throw new IllegalArgumentException(
              "state " + state.state() + " points to undeclared state " + next);
        }
      }
    }
  }

  /** 文档要求的可达性：启用工作阶段必须从 INIT 可达，且 INIT 必须存在到 DONE 的正常路径。 */
  private static void requireUsableNormalGraph(Map<ProjectStateCode, ProjectWorkflowState> index) {
    Set<ProjectStateCode> reachable = reachableFromInit(index);
    for (ProjectWorkflowState state : index.values()) {
      if (isEnabledWorkStage(state) && !reachable.contains(state.state())) {
        throw new IllegalArgumentException(
            "enabled stage " + state.state() + " is not reachable from INIT");
      }
    }
    if (!reachable.contains(ProjectWorkflowReservedState.DONE.code())) {
      throw new IllegalArgumentException("workflow must provide a normal path from INIT to DONE");
    }
  }

  private static boolean isEnabledWorkStage(ProjectWorkflowState state) {
    return state.enabled() && !ProjectWorkflowReservedState.isReserved(state.state());
  }

  private static Set<ProjectStateCode> reachableFromInit(
      Map<ProjectStateCode, ProjectWorkflowState> index) {
    ProjectStateCode start = ProjectWorkflowReservedState.INIT.code();
    Set<ProjectStateCode> visited = new LinkedHashSet<>();
    Deque<ProjectStateCode> queue = new ArrayDeque<>();
    visited.add(start);
    queue.add(start);
    while (!queue.isEmpty()) {
      for (ProjectStateCode next : index.get(queue.remove()).next()) {
        if (visited.add(next)) {
          queue.add(next);
        }
      }
    }
    return visited;
  }
}
