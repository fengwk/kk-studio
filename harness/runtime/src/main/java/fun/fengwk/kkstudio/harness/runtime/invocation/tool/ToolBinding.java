package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.Objects;

/**
 * 单次 Tool invocation 的冻结 binding：完整 Agent tool definition、Contributor provenance、环境需求标志与可选
 * Environment binding。
 *
 * <p>{@link AgentToolDefinition#descriptor()} 是 model contract，{@link AgentToolDefinition#id()} 是
 * durable registry 与 permission identity。 Contributor 必须非空；当且仅当 {@code environmentRequired == true}
 * 时 {@code environment} 必须非空。
 */
public record ToolBinding(
    AgentToolDefinition definition,
    ContributorBinding contributor,
    boolean environmentRequired,
    EnvironmentBinding environment) {

  public ToolBinding {
    definition = Objects.requireNonNull(definition, "definition");
    contributor = Objects.requireNonNull(contributor, "contributor");
    if (environmentRequired) {
      if (environment == null) {
        throw new IllegalArgumentException("environmentRequired is true but environment is null");
      }
    } else {
      if (environment != null) {
        throw new IllegalArgumentException(
            "environmentRequired is false but environment is not null");
      }
    }
  }

  /** 便捷取得 model contract，避免调用方重复穿透 definition。 */
  public ToolDescriptor descriptor() {
    return definition.descriptor();
  }
}
