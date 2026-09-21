package fun.fengwk.kkstudio.platform.harness.task;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.skill.SkillPromptPathResolver;

import java.time.Clock;
import java.util.Objects;

/** 在 Platform 边界内持有预览依赖，由 Web 组合根只注入 {@link HarnessRuntime}。 */
@Component
public final class SystemPromptPreviewServiceFactory {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentDefinitionConfigCodec agentConfigCodec;
  private final EnvironmentRegistry environmentRegistry;
  private final EnvironmentRepository environmentRepository;
  private final SkillCatalogQueryService skillCatalogQueryService;
  private final SkillPromptPathResolver skillPromptPathResolver;
  private final AgentPromptComposer promptComposer;
  private final Clock clock;

  public SystemPromptPreviewServiceFactory(
      AgentDefinitionRepository agentDefinitionRepository,
      AgentDefinitionConfigCodec agentConfigCodec,
      EnvironmentRegistry environmentRegistry,
      EnvironmentRepository environmentRepository,
      SkillCatalogQueryService skillCatalogQueryService,
      SkillPromptPathResolver skillPromptPathResolver,
      AgentPromptComposer promptComposer,
      Clock clock) {
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.skillCatalogQueryService =
        Objects.requireNonNull(skillCatalogQueryService, "skillCatalogQueryService");
    this.skillPromptPathResolver =
        Objects.requireNonNull(skillPromptPathResolver, "skillPromptPathResolver");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public SystemPromptPreviewService create(HarnessRuntime runtime) {
    return new SystemPromptPreviewService(
        runtime,
        agentDefinitionRepository,
        agentConfigCodec,
        environmentRegistry,
        environmentRepository,
        skillCatalogQueryService,
        skillPromptPathResolver,
        promptComposer,
        clock);
  }
}
