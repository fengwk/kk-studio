package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.Objects;

/**
 * 单次 Tool invocation 的冻结 binding：完整 Agent tool definition、Contributor provenance 与可选 Environment
 * binding。
 *
 * <p>{@link AgentToolDefinition#backend()} 是唯一 execution route；{@link
 * AgentToolDefinition#descriptor()} 是 model contract，{@link AgentToolDefinition#id()} 是 durable
 * registry 与 permission identity。 Contributor 必须非空；Environment 仅在 ENVIRONMENT_CAPABILITY 时非空；state
 * accesses 仅在 DECLARATIVE 时允许非空。
 */
public record ToolBinding(
    AgentToolDefinition definition,
    ContributorBinding contributor,
    EnvironmentBinding environment) {

  public ToolBinding {
    definition = Objects.requireNonNull(definition, "definition");
    contributor = Objects.requireNonNull(contributor, "contributor");
    switch (definition.backend()) {
      case HOST -> {
        if (environment != null) {
          throw new IllegalArgumentException("HOST binding must not have an environment binding");
        }
        if (!contributor.stateAccesses().isEmpty()) {
          throw new IllegalArgumentException("HOST binding must not declare state accesses");
        }
      }
      case DECLARATIVE -> {
        if (environment != null) {
          throw new IllegalArgumentException(
              "DECLARATIVE binding must not have an environment binding");
        }
      }
      case ENVIRONMENT_CAPABILITY -> {
        if (environment == null) {
          throw new IllegalArgumentException(
              "ENVIRONMENT_CAPABILITY binding requires an environment binding");
        }
        if (!contributor.stateAccesses().isEmpty()) {
          throw new IllegalArgumentException(
              "ENVIRONMENT_CAPABILITY binding must not declare state accesses");
        }
      }
    }
  }

  /** 便捷取得 model contract，避免调用方重复穿透 definition。 */
  public ToolDescriptor descriptor() {
    return definition.descriptor();
  }
}
