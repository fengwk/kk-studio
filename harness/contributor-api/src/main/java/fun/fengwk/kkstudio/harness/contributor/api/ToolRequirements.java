package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 工具执行所需的环境关系与状态声明。
 *
 * @param environmentSupport 该工具与 Environment 的关系级别
 * @param requiredEnvironmentId 精确要求的目标 Environment 身份；非空时 {@code environmentSupport} 必须为 {@link
 *     EnvironmentSupport#REQUIRED}，null 表示接受任意已绑定 Environment
 * @param stateAccesses 声明的 branch custom state 访问集合
 */
public record ToolRequirements(
    EnvironmentSupport environmentSupport,
    EnvironmentId requiredEnvironmentId,
    List<StateDeclaration> stateAccesses) {

  public ToolRequirements {
    environmentSupport = Objects.requireNonNull(environmentSupport, "environmentSupport");
    if (requiredEnvironmentId != null && environmentSupport != EnvironmentSupport.REQUIRED) {
      throw new IllegalArgumentException(
          "requiredEnvironmentId requires EnvironmentSupport.REQUIRED");
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

  /** 不要求绑定 Environment，但声明 branch custom state 访问的 ToolRequirements。 */
  public ToolRequirements(
      EnvironmentSupport environmentSupport, List<StateDeclaration> stateAccesses) {
    this(environmentSupport, null, stateAccesses);
  }

  /** 返回无任何前置要求的 ToolRequirements。 */
  public static ToolRequirements none() {
    return new ToolRequirements(EnvironmentSupport.NONE, null, List.of());
  }

  /** 返回有 Environment 时使用、无 Environment 时由 Platform 侧执行的 ToolRequirements。 */
  public static ToolRequirements optionalEnvironment() {
    return new ToolRequirements(EnvironmentSupport.OPTIONAL, null, List.of());
  }

  /** 返回需要绑定执行环境的 ToolRequirements（接受任意已绑定的 Environment）。 */
  public static ToolRequirements environment() {
    return new ToolRequirements(EnvironmentSupport.REQUIRED, null, List.of());
  }

  /** 返回精确要求目标 Environment 的 ToolRequirements。 */
  public static ToolRequirements environment(EnvironmentId requiredEnvironmentId) {
    Objects.requireNonNull(requiredEnvironmentId, "requiredEnvironmentId");
    return new ToolRequirements(EnvironmentSupport.REQUIRED, requiredEnvironmentId, List.of());
  }
}
