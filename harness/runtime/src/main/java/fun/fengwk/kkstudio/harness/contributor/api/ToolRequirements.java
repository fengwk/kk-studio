package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Objects;

/**
 * 工具执行所需的执行位置声明。
 *
 * <p>只声明 Tool 与 Environment 的关系级别：文件工具声明 {@link EnvironmentSupport#ROUTED}，每次调用的执行位置由调用参数决定；只有
 * {@code bash}、LSP 这类只能在环境上运行的工具声明 {@link EnvironmentSupport#REQUIRED}。
 *
 * @param environmentSupport 该工具与 Environment 的关系级别
 */
public record ToolRequirements(EnvironmentSupport environmentSupport) {

  public ToolRequirements {
    environmentSupport = Objects.requireNonNull(environmentSupport, "environmentSupport");
  }

  /** 返回无任何前置要求的 ToolRequirements。 */
  public static ToolRequirements none() {
    return new ToolRequirements(EnvironmentSupport.NONE);
  }

  /** 返回每次调用按参数决定执行位置的 ToolRequirements（文件工具）。 */
  public static ToolRequirements routed() {
    return new ToolRequirements(EnvironmentSupport.ROUTED);
  }

  /** 返回只能在绑定环境执行的 ToolRequirements。 */
  public static ToolRequirements environment() {
    return new ToolRequirements(EnvironmentSupport.REQUIRED);
  }
}
