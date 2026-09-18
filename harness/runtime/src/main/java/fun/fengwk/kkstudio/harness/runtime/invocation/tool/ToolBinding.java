package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.Objects;

/**
 * 单次 Tool invocation 的冻结 binding：完整 Agent tool definition、Contributor provenance、环境需求标志与可选
 * Environment 路由身份。
 *
 * <p>{@link AgentToolDefinition#descriptor()} 的 name 同时是 model contract、durable registry 与
 * permission identity。 Contributor 必须非空；当且仅当 {@code environmentRequired == true} 时 {@code
 * environmentId} 必须非空，目录不作为 binding 的一部分。
 */
public record ToolBinding(
    AgentToolDefinition definition,
    ContributorBinding contributor,
    boolean environmentRequired,
    EnvironmentId environmentId) {

  public ToolBinding {
    definition = Objects.requireNonNull(definition, "definition");
    contributor = Objects.requireNonNull(contributor, "contributor");
    if (environmentRequired) {
      if (environmentId == null) {
        throw new IllegalArgumentException("environmentRequired is true but environmentId is null");
      }
    } else {
      if (environmentId != null) {
        throw new IllegalArgumentException(
            "environmentRequired is false but environmentId is not null");
      }
    }
  }

  /** 便捷取得 model contract，避免调用方重复穿透 definition。 */
  public ToolDescriptor descriptor() {
    return definition.descriptor();
  }
}
