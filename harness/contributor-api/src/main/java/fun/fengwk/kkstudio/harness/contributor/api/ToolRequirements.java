package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 工具执行所需的前置条件与状态声明。
 *
 * @param environmentRequired 是否需要绑定 Environment
 * @param stateAccesses 声明的 branch custom state 访问集合
 */
public record ToolRequirements(boolean environmentRequired, List<StateDeclaration> stateAccesses) {

  public ToolRequirements {
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
    return new ToolRequirements(false, List.of());
  }

  /** 返回仅需要绑定执行环境的 ToolRequirements。 */
  public static ToolRequirements environment() {
    return new ToolRequirements(true, List.of());
  }
}
