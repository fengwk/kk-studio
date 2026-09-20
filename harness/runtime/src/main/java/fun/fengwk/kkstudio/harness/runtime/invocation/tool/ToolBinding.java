package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.Objects;

/**
 * 单次 Tool invocation 的冻结 binding：完整 Agent tool definition、Contributor provenance、环境支持级别与可选
 * Environment 路由身份 / 名称。
 *
 * <p>{@link AgentToolDefinition#descriptor()} 的 name 同时是 model contract、durable registry 与
 * permission identity。Contributor 必须非空。
 *
 * <p>环境组合规则是封闭的：{@link EnvironmentSupport#NONE} 不得携带环境；{@link EnvironmentSupport#OPTIONAL}
 * 可以携带当前选择，也可以完全不携带；{@link EnvironmentSupport#REQUIRED} 的正常规划结果必须携带环境——无选择的 {@code REQUIRED} 工具在
 * {@code ModelRequestSpec} 构造之前就已被过滤，因此缺环境的 REQUIRED binding 一定是陈旧或伪造的。
 *
 * <p>{@code environmentId} 与 {@code environmentName} 同源，必须同时存在或同时缺失。{@code environmentName}
 * 是该分支冻结的用户可见 Environment 名，是历史投影判定 native 资格的 durable 事实：branch 后来切换 Environment 时，既有
 * environment-bound 调用必须降级。 目录不作为 binding 的一部分。
 */
public record ToolBinding(
    AgentToolDefinition definition,
    ContributorBinding contributor,
    EnvironmentSupport environmentSupport,
    EnvironmentId environmentId,
    String environmentName) {

  public ToolBinding {
    definition = Objects.requireNonNull(definition, "definition");
    contributor = Objects.requireNonNull(contributor, "contributor");
    environmentSupport = Objects.requireNonNull(environmentSupport, "environmentSupport");
    if (environmentSupport == EnvironmentSupport.NONE) {
      requireWithoutEnvironment(environmentId, environmentName);
    } else if (environmentSupport == EnvironmentSupport.REQUIRED) {
      requireWithEnvironment(environmentId, environmentName);
    } else if ((environmentId == null) != (environmentName == null)) {
      throw new IllegalArgumentException(
          "environmentId and environmentName must be present together or absent together");
    }
    if (environmentName != null && environmentName.isBlank()) {
      throw new IllegalArgumentException("environmentName must be null or non-blank");
    }
  }

  /** 便捷取得 model contract，避免调用方重复穿透 definition。 */
  public ToolDescriptor descriptor() {
    return definition.descriptor();
  }

  private static void requireWithoutEnvironment(EnvironmentId environmentId, String name) {
    if (environmentId != null || name != null) {
      throw new IllegalArgumentException("EnvironmentSupport.NONE must not carry an environment");
    }
  }

  private static void requireWithEnvironment(EnvironmentId environmentId, String name) {
    if (environmentId == null || name == null) {
      throw new IllegalArgumentException(
          "EnvironmentSupport.REQUIRED must carry both environmentId and environmentName");
    }
  }
}
