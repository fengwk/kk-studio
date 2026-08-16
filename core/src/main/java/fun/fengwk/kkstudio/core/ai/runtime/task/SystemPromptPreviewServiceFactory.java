package fun.fengwk.kkstudio.core.ai.runtime.task;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;

import java.time.Clock;
import java.util.Objects;

/** 在 Core 边界内持有预览依赖，由 Web 组合根只注入 {@link HarnessRuntime}。 */
@Component
public final class SystemPromptPreviewServiceFactory {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentDefinitionConfigCodec agentConfigCodec;
  private final LiveEnvironmentRegistry environmentRegistry;
  private final SubagentConfig subagentConfig;
  private final AgentPromptComposer promptComposer;
  private final Clock clock;

  public SystemPromptPreviewServiceFactory(
      AgentDefinitionRepository agentDefinitionRepository,
      AgentDefinitionConfigCodec agentConfigCodec,
      LiveEnvironmentRegistry environmentRegistry,
      SubagentConfig subagentConfig,
      AgentPromptComposer promptComposer,
      Clock clock) {
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.subagentConfig = Objects.requireNonNull(subagentConfig, "subagentConfig");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public SystemPromptPreviewService create(HarnessRuntime runtime) {
    return new SystemPromptPreviewService(
        runtime,
        agentDefinitionRepository,
        agentConfigCodec,
        environmentRegistry,
        subagentConfig,
        promptComposer,
        clock);
  }
}
