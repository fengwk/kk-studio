package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.Objects;

/**
 * 单次 Tool invocation 的冻结 binding：完整 Agent tool definition、Environment binding 以及 plugin provenance。
 *
 * <p>{@link AgentToolDefinition#backend()} 是唯一 execution route；{@link
 * AgentToolDefinition#descriptor()} 是 model contract，{@link AgentToolDefinition#id()} 是 durable
 * registry 与 permission identity。HOST binding 不携带 environment 或 plugin；PLUGIN binding 必须携带 plugin
 * 且不携带 environment；ENVIRONMENT_CAPABILITY binding 不携带 plugin，environment 可为 null。创建 approval 的 YOLO
 * policy 在此被刻意省略。
 */
public record ToolBinding(
    AgentToolDefinition definition, EnvironmentBinding environment, PluginToolBinding plugin) {

  public ToolBinding {
    definition = Objects.requireNonNull(definition, "definition");
    switch (definition.backend()) {
      case HOST -> {
        if (environment != null || plugin != null) {
          throw new IllegalArgumentException(
              "HOST binding must not have environment or plugin provenance");
        }
      }
      case PLUGIN -> {
        if (environment != null || plugin == null) {
          throw new IllegalArgumentException(
              "PLUGIN binding requires plugin provenance and must not have an environment binding");
        }
      }
      case ENVIRONMENT_CAPABILITY -> {
        if (plugin != null) {
          throw new IllegalArgumentException(
              "ENVIRONMENT_CAPABILITY binding must not have plugin provenance");
        }
      }
    }
  }

  /** 便捷取得 model contract，避免调用方重复穿透 definition。 */
  public ToolDescriptor descriptor() {
    return definition.descriptor();
  }
}
