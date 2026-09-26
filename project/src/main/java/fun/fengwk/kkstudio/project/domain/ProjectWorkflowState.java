package fun.fengwk.kkstudio.project.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * workflow 中的一个状态条目：保留状态或工作阶段。
 *
 * <p>保留状态（INIT/BLOCKED/DONE）只声明显示名与正常转移边，不能停用、不能配置 Agent/Environment、 instructions 或
 * maxRuns；BLOCKED 由专用阻塞/恢复操作进入、DONE 由显式重开操作回到 INIT，二者都不通过 {@code next} 表达普通边。工作阶段声明显示名与可选
 * Agent、Environment、instructions、maxRuns、enabled：有 Agent 必须给出正数 maxRuns；无 Agent 是人工阶段，不配置
 * Environment 和 Run 额度，也不自动跳过或产生 Run。 {@code next} 是正常转移白名单（集合语义），不允许自身边、重复目标，也不允许指向 BLOCKED。
 */
public record ProjectWorkflowState(
    ProjectStateCode state,
    String name,
    String agent,
    String environment,
    String instructions,
    Integer maxRuns,
    boolean enabled,
    List<ProjectStateCode> next) {

  public ProjectWorkflowState {
    Objects.requireNonNull(state, "state");
    name = ProjectValidation.requireDisplayName(name, "name");
    agent = ProjectValidation.optionalCanonicalName(agent, "agent");
    environment = ProjectValidation.optionalCanonicalName(environment, "environment");
    instructions = ProjectValidation.optionalText(instructions, "instructions");
    next = normalizeNext(state, next);
    if (ProjectWorkflowReservedState.isReserved(state)) {
      requireReservedShape(state, agent, environment, instructions, maxRuns, enabled, next);
    } else {
      requireWorkStageShape(state, agent, environment, maxRuns);
    }
  }

  /** 是否配置了 Agent：有 Agent 的工作阶段才有 Run 与阶段额度。 */
  public boolean hasAgent() {
    return agent != null;
  }

  private static List<ProjectStateCode> normalizeNext(
      ProjectStateCode state, List<ProjectStateCode> next) {
    Objects.requireNonNull(next, "next");
    List<ProjectStateCode> targets = new ArrayList<>(next.size());
    Set<ProjectStateCode> seen = new HashSet<>();
    for (ProjectStateCode target : next) {
      Objects.requireNonNull(target, "next target");
      if (target.equals(state)) {
        throw new IllegalArgumentException("state " + state + " must not declare a self edge");
      }
      if (ProjectWorkflowReservedState.BLOCKED.code().equals(target)) {
        throw new IllegalArgumentException("BLOCKED is not reachable through normal edges");
      }
      if (!seen.add(target)) {
        throw new IllegalArgumentException("state " + state + " declares duplicate next " + target);
      }
      targets.add(target);
    }
    return List.copyOf(targets);
  }

  private static void requireReservedShape(
      ProjectStateCode state,
      String agent,
      String environment,
      String instructions,
      Integer maxRuns,
      boolean enabled,
      List<ProjectStateCode> next) {
    if (agent != null || environment != null || instructions != null || maxRuns != null) {
      throw new IllegalArgumentException(
          "reserved state "
              + state
              + " must not configure agent, environment, instructions or maxRuns");
    }
    if (!enabled) {
      throw new IllegalArgumentException("reserved state " + state + " cannot be disabled");
    }
    if (!next.isEmpty() && !ProjectWorkflowReservedState.INIT.code().equals(state)) {
      throw new IllegalArgumentException("reserved state " + state + " declares no normal edges");
    }
  }

  private static void requireWorkStageShape(
      ProjectStateCode state, String agent, String environment, Integer maxRuns) {
    if (agent == null) {
      if (environment != null || maxRuns != null) {
        throw new IllegalArgumentException(
            "manual stage " + state + " must not configure environment or maxRuns");
      }
      return;
    }
    if (maxRuns == null) {
      throw new IllegalArgumentException("stage " + state + " with agent must declare maxRuns");
    }
    if (maxRuns <= 0) {
      throw new IllegalArgumentException("stage " + state + " maxRuns must be > 0");
    }
  }
}
