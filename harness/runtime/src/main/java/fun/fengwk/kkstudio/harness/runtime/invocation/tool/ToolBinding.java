package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.Objects;

/**
 * 单次 Tool invocation 的冻结 binding：完整 Agent tool definition、Contributor provenance、环境需求标志与可选
 * Environment 路由身份 / 名称。
 *
 * <p>{@link AgentToolDefinition#descriptor()} 的 name 同时是 model contract、durable registry 与
 * permission identity。 Contributor 必须非空；{@code environmentRequired == true} 时 {@code environmentId}
 * 可以为 null，表示该 branch 尚未选择 Environment——工具保持声明，调用时以 {@code ENVIRONMENT_NOT_SELECTED} 确定性失败；{@code
 * environmentRequired == false} 时 {@code environmentId} 与 {@code environmentName} 必须为 null。
 *
 * <p>{@code environmentName} 是该分支冻结的用户可见 Environment 名，与 {@code environmentId} 同源。它是历史投影判定 native
 * 资格的 durable 事实：branch 后来切换 Environment 时，既有 environment-bound 调用必须降级。目录不作为 binding 的一部分。
 */
public record ToolBinding(
    AgentToolDefinition definition,
    ContributorBinding contributor,
    boolean environmentRequired,
    EnvironmentId environmentId,
    String environmentName) {

  public ToolBinding {
    definition = Objects.requireNonNull(definition, "definition");
    contributor = Objects.requireNonNull(contributor, "contributor");
    if (!environmentRequired && environmentId != null) {
      throw new IllegalArgumentException(
          "environmentRequired is false but environmentId is not null");
    }
    if (!environmentRequired && environmentName != null) {
      throw new IllegalArgumentException(
          "environmentRequired is false but environmentName is not null");
    }
    if (environmentName != null && environmentName.isBlank()) {
      throw new IllegalArgumentException("environmentName must be null or non-blank");
    }
  }

  /** 不携带冻结 Environment 名的 binding；非环境工具或尚未选择 Environment 时使用。 */
  public ToolBinding(
      AgentToolDefinition definition,
      ContributorBinding contributor,
      boolean environmentRequired,
      EnvironmentId environmentId) {
    this(definition, contributor, environmentRequired, environmentId, null);
  }

  /** 便捷取得 model contract，避免调用方重复穿透 definition。 */
  public ToolDescriptor descriptor() {
    return definition.descriptor();
  }
}
