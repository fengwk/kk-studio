package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 工具执行所需的执行位置与状态访问声明。
 *
 * <p>只声明 Tool 与 Environment 的关系级别：文件工具声明 {@link EnvironmentSupport#ROUTED}，每次调用的执行位置由调用参数决定；只有
 * {@code bash}、LSP 这类只能在环境上运行的工具声明 {@link EnvironmentSupport#REQUIRED}。
 *
 * @param environmentSupport 该工具与 Environment 的关系级别
 * @param stateAccesses 声明的 branch custom state 访问集合
 */
public record ToolRequirements(
    EnvironmentSupport environmentSupport, List<StateDeclaration> stateAccesses) {

  public ToolRequirements {
    environmentSupport = Objects.requireNonNull(environmentSupport, "environmentSupport");
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

  /** 返回无任何前置要求的 ToolRequirements。 */
  public static ToolRequirements none() {
    return new ToolRequirements(EnvironmentSupport.NONE, List.of());
  }

  /** 返回每次调用按参数决定执行位置的 ToolRequirements（文件工具）。 */
  public static ToolRequirements routed() {
    return new ToolRequirements(EnvironmentSupport.ROUTED, List.of());
  }

  /** 返回只能在绑定环境执行的 ToolRequirements。 */
  public static ToolRequirements environment() {
    return new ToolRequirements(EnvironmentSupport.REQUIRED, List.of());
  }
}
