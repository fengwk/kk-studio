package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 工具执行所需的前置条件与状态声明。
 *
 * @param environmentRequired 是否需要绑定 Environment
 * @param requiredEnvironmentId 精确要求的目标 Environment 身份；非空时 environmentRequired 必须为 true，null
 *     表示可使用任意已绑定 Environment
 * @param stateAccesses 声明的 branch custom state 访问集合
 */
public record ToolRequirements(
    boolean environmentRequired,
    EnvironmentId requiredEnvironmentId,
    List<StateDeclaration> stateAccesses) {

  public ToolRequirements {
    if (requiredEnvironmentId != null && !environmentRequired) {
      throw new IllegalArgumentException(
          "environmentRequired must be true when requiredEnvironmentId is present");
    }
    stateAccesses = List.copyOf(Objects.requireNonNull(stateAccesses, "stateAccesses"));
    Set<String> customTypes = new HashSet<>();
    for (StateDeclaration access : stateAccesses) {
      Objects.requireNonNull(access, "stateAccesses[]");
      if (!customTypes.add(access.customType())) {
        throw new IllegalArgumentException(
            "duplicate state access customType: " + access.customType());
      }
    }
  }

  public ToolRequirements(boolean environmentRequired, List<StateDeclaration> stateAccesses) {
    this(environmentRequired, null, stateAccesses);
  }

  /** 返回无任何前置要求的 ToolRequirements。 */
  public static ToolRequirements none() {
    return new ToolRequirements(false, null, List.of());
  }

  /** 返回仅需要绑定执行环境的 ToolRequirements（接受任意已绑定的 Environment）。 */
  public static ToolRequirements environment() {
    return new ToolRequirements(true, null, List.of());
  }

  /** 返回精确要求目标 Environment 的 ToolRequirements。 */
  public static ToolRequirements environment(EnvironmentId requiredEnvironmentId) {
    Objects.requireNonNull(requiredEnvironmentId, "requiredEnvironmentId");
    return new ToolRequirements(true, requiredEnvironmentId, List.of());
  }
}
