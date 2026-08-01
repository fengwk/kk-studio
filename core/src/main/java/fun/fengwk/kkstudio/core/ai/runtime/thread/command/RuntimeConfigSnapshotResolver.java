package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.configuration.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ModelSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSource;
import fun.fengwk.kkstudio.harness.runtime.configuration.SkillSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.plan.RuntimeCapabilityResolver;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves durable command snapshots and, separately, the live capabilities for one model plan.
 *
 * <p>Command methods never read the Environment registry. Live registry state is consulted only by
 * {@link #resolve(RuntimeConfigSnapshot)} after a response debt has been identified.
 */
@Component
public class RuntimeConfigSnapshotResolver
    implements RuntimeConfigSource, RuntimeCapabilityResolver {

  private final AgentDefinitionMapper definitionMapper;
  private final AgentDefinitionConfigCodec definitionConfigCodec;
  private final AgentModelRepository modelRepository;
  private final AgentProviderRepository providerRepository;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final ProviderFactories providerFactories;
  private final ToolCatalog toolCatalog;
  private final LiveEnvironmentRegistry environmentRegistry;

  @Autowired
  public RuntimeConfigSnapshotResolver(
      AgentDefinitionMapper definitionMapper,
      AgentDefinitionConfigCodec definitionConfigCodec,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentModelRuntimeConfigParser modelConfigParser,
      ProviderFactories providerFactories,
      ToolCatalog toolCatalog,
      LiveEnvironmentRegistry environmentRegistry) {
    this.definitionMapper = Objects.requireNonNull(definitionMapper, "definitionMapper");
    this.definitionConfigCodec =
        Objects.requireNonNull(definitionConfigCodec, "definitionConfigCodec");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.providerFactories = Objects.requireNonNull(providerFactories, "providerFactories");
    this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
  }

  public RuntimeConfigSnapshotResolver(
      AgentDefinitionMapper definitionMapper,
      AgentDefinitionConfigCodec definitionConfigCodec,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentModelRuntimeConfigParser modelConfigParser,
      ProviderFactories providerFactories,
      ToolFactories toolFactories,
      LiveEnvironmentRegistry environmentRegistry) {
    this(
        definitionMapper,
        definitionConfigCodec,
        modelRepository,
        providerRepository,
        modelConfigParser,
        providerFactories,
        new ToolCatalog(toolFactories.descriptors(), Set.of(LoadSkillTool.NAME)),
        environmentRegistry);
  }

  @Override
  public RuntimeConfigSnapshot resolveAgent(long definitionId, boolean yoloEnabled) {
    return resolveAgent(definitionId, null, yoloEnabled);
  }

  @Override
  public RuntimeConfigSnapshot resolveAgent(
      long definitionId, String environmentName, boolean yoloEnabled) {
    AgentDefinitionDO definition = requireDefinition(definitionId);
    if (definition.getModelId() == null || definition.getModelId() <= 0) {
      throw new IllegalArgumentException("agent definition has no default model: " + definitionId);
    }
    AgentDefinitionConfigDTO config = definitionConfigCodec.decode(definition.getConfigJson());
    validateStaticSelections(config);
    return new RuntimeConfigSnapshot(
        new AgentSnapshot(definition.getId(), definition.getName(), definition.getSystemPrompt()),
        resolveModel(definition.getModelId(), definition.getVariant()),
        environmentName,
        config.getTools(),
        config.getSkills(),
        yoloEnabled);
  }

  @Override
  public RuntimeConfigSnapshot replaceAgent(RuntimeConfigSnapshot current, long definitionId) {
    Objects.requireNonNull(current, "current");
    AgentDefinitionDO definition = requireDefinition(definitionId);
    if (definition.getModelId() == null || definition.getModelId() <= 0) {
      throw new IllegalArgumentException("agent definition has no default model: " + definitionId);
    }
    AgentDefinitionConfigDTO config = definitionConfigCodec.decode(definition.getConfigJson());
    validateStaticSelections(config);
    return new RuntimeConfigSnapshot(
        new AgentSnapshot(definition.getId(), definition.getName(), definition.getSystemPrompt()),
        resolveModel(definition.getModelId(), definition.getVariant()),
        current.environmentName(),
        config.getTools(),
        config.getSkills(),
        current.yoloEnabled());
  }

  @Override
  public RuntimeConfigSnapshot replaceModel(
      RuntimeConfigSnapshot current, long modelId, String requestedVariant) {
    Objects.requireNonNull(current, "current");
    ModelSnapshotParts model = resolveModelParts(modelId, requestedVariant);
    return new RuntimeConfigSnapshot(
        current.agent(),
        model.snapshot(),
        current.environmentName(),
        current.toolNames(),
        current.skillNames(),
        current.yoloEnabled());
  }

  @Override
  public ResolvedCapabilities resolve(RuntimeConfigSnapshot config) {
    Objects.requireNonNull(config, "config");
    List<ToolBinding> bindings = new ArrayList<>();
    Set<String> selected = new HashSet<>();
    LiveEnvironment environment =
        config.environmentName() == null
            ? null
            : environmentRegistry
                .find(config.environmentName())
                .filter(LiveEnvironment::isReady)
                .orElse(null);

    for (String name : config.toolNames()) {
      ToolDescriptor local = toolCatalog.findLocal(name).orElse(null);
      if (local != null) {
        addBinding(bindings, selected, ToolBinding.of(local));
        continue;
      }
      ToolDescriptor environmentTool = toolCatalog.findEnvironment(name).orElse(null);
      if (environmentTool == null || environment == null) {
        // Environment capabilities are optional at plan time. An offline target must not block
        // the Thread or cause an endless requeue cycle.
        continue;
      }
      addBinding(bindings, selected, ToolBinding.of(environmentTool, config.environmentName()));
    }

    List<SkillSnapshot> skills = resolveSkills(config, environment);
    if (!skills.isEmpty()) {
      ToolDescriptor loadSkill =
          toolCatalog
              .findRuntimeManaged(LoadSkillTool.NAME)
              .orElseThrow(
                  () -> new IllegalStateException("load_skill runtime tool is not registered"));
      addBinding(bindings, selected, ToolBinding.of(loadSkill));
    }
    return new ResolvedCapabilities(bindings, skills);
  }

  private List<SkillSnapshot> resolveSkills(
      RuntimeConfigSnapshot config, LiveEnvironment environment) {
    if (environment == null || config.skillNames().isEmpty()) {
      return List.of();
    }
    Map<String, SkillSnapshot> available = new HashMap<>();
    environment
        .skills()
        .forEach(
            skill ->
                available.putIfAbsent(
                    skill.name(),
                    new SkillSnapshot(
                        skill.name(), skill.description(), config.environmentName())));
    List<SkillSnapshot> result = new ArrayList<>();
    for (String name : config.skillNames()) {
      SkillSnapshot skill = available.get(name);
      if (skill != null) {
        result.add(skill);
      }
    }
    return List.copyOf(result);
  }

  private static void addBinding(
      List<ToolBinding> bindings, Set<String> selected, ToolBinding binding) {
    if (!selected.add(binding.descriptor().name())) {
      throw new IllegalArgumentException("duplicate selected tool: " + binding.descriptor().name());
    }
    bindings.add(binding);
  }

  private void validateStaticSelections(AgentDefinitionConfigDTO config) {
    Set<String> names = new HashSet<>();
    for (String name : config.getTools()) {
      if (!names.add(name)) {
        throw new IllegalArgumentException("duplicate agent tool: " + name);
      }
      if (LoadSkillTool.NAME.equals(name)) {
        throw new IllegalArgumentException("load_skill is runtime-managed and cannot be selected");
      }
      if (toolCatalog.find(name).isEmpty()) {
        throw new IllegalArgumentException("unknown agent tool: " + name);
      }
    }
  }

  private AgentDefinitionDO requireDefinition(long definitionId) {
    if (definitionId <= 0) {
      throw new IllegalArgumentException("definitionId must be positive");
    }
    AgentDefinitionDO definition = definitionMapper.getById(definitionId);
    if (definition == null) {
      throw new IllegalArgumentException("unknown agent definition: " + definitionId);
    }
    return definition;
  }

  private ModelSnapshotParts resolveModelParts(long modelId, String requestedVariant) {
    if (modelId <= 0) {
      throw new IllegalArgumentException("modelId must be positive");
    }
    AgentModel model = modelRepository.getById(modelId);
    if (model == null) {
      throw new IllegalArgumentException("unknown agent model: " + modelId);
    }
    if (model.getProviderId() == null || model.getProviderId() <= 0) {
      throw new IllegalStateException("agent model has no provider: " + modelId);
    }
    AgentProvider provider = providerRepository.getById(model.getProviderId());
    if (provider == null) {
      throw new IllegalArgumentException("unknown provider for agent model: " + modelId);
    }
    ProviderType providerType = providerType(provider.getProviderType());
    ProviderFactory factory =
        providerFactories
            .lookup(providerType)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "no provider factory registered for " + providerType));
    ParsedAgentModelConfig parsed = modelConfigParser.parse(model.getConfigJson());
    String variantId =
        requestedVariant == null || requestedVariant.isBlank()
            ? parsed.defaultVariant()
            : requestedVariant;
    ModelVariant variant =
        parsed.variants().stream()
            .filter(candidate -> candidate.id().equals(variantId))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown variant '" + variantId + "' for model " + modelId));
    ModelDescriptor descriptor =
        new ModelDescriptor(
            provider.getId(),
            model.getId(),
            providerType,
            model.getName(),
            parsed.tools(),
            parsed.reasoning(),
            parsed.pricing(),
            cachePolicy(providerType, factory.promptCacheCapability()));
    return new ModelSnapshotParts(new ModelSnapshot(descriptor, variant));
  }

  private ModelSnapshot resolveModel(long modelId, String requestedVariant) {
    return resolveModelParts(modelId, requestedVariant).snapshot();
  }

  private static ProviderType providerType(AgentProviderType value) {
    if (value == null) {
      throw new IllegalArgumentException("provider type must not be null");
    }
    return switch (value) {
      case openai -> ProviderType.OPENAI;
      case openai_response -> ProviderType.OPENAI_RESPONSES;
      case anthropic -> ProviderType.ANTHROPIC;
      case google -> ProviderType.GOOGLE;
    };
  }

  private static PromptCachePolicy cachePolicy(
      ProviderType providerType, PromptCacheCapability capability) {
    return switch (providerType) {
      case OPENAI, OPENAI_RESPONSES -> PromptCachePolicy.affinityShort(capability);
      case ANTHROPIC -> PromptCachePolicy.breakpointsShort(capability);
      case GOOGLE -> PromptCachePolicy.automatic(capability);
    };
  }

  private record ModelSnapshotParts(ModelSnapshot snapshot) {}
}
