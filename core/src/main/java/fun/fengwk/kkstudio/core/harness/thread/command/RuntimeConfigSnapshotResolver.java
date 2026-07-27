package fun.fengwk.kkstudio.core.harness.thread.command;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.configuration.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ModelSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSource;
import fun.fengwk.kkstudio.harness.runtime.configuration.SkillSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 仅用于命令提交时的 live resource -> immutable {@link RuntimeConfigSnapshot} 冻结器。
 *
 * <p>实现 runtime {@link RuntimeConfigSource}：可以读取 Definition、Model、Provider、ready Environment 和
 * {@link ToolFactories} 暴露的 platform tool descriptors，但严禁调用 {@link ProviderFactory#create(String,
 * String)} 或任何 Provider I/O。credential 不进入 descriptor；它只在后续 worker 依据 {@code providerResourceId}
 * 短生命周期解析。纯 YOLO 替换由 runtime 编排侧的 {@link RuntimeConfigSnapshot#withYoloEnabled(boolean)} 完成。
 */
@Component
public class RuntimeConfigSnapshotResolver implements RuntimeConfigSource {

  private final AgentDefinitionMapper definitionMapper;
  private final AgentDefinitionConfigCodec definitionConfigCodec;
  private final AgentModelRepository modelRepository;
  private final AgentProviderRepository providerRepository;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final ProviderFactories providerFactories;
  private final ToolFactories toolFactories;
  private final LiveEnvironmentRegistry environmentRegistry;

  public RuntimeConfigSnapshotResolver(
      AgentDefinitionMapper definitionMapper,
      AgentDefinitionConfigCodec definitionConfigCodec,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentModelRuntimeConfigParser modelConfigParser,
      ProviderFactories providerFactories,
      ToolFactories toolFactories,
      LiveEnvironmentRegistry environmentRegistry) {
    this.definitionMapper = Objects.requireNonNull(definitionMapper, "definitionMapper");
    this.definitionConfigCodec =
        Objects.requireNonNull(definitionConfigCodec, "definitionConfigCodec");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.providerFactories = Objects.requireNonNull(providerFactories, "providerFactories");
    this.toolFactories = Objects.requireNonNull(toolFactories, "toolFactories");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
  }

  /** 使用 Definition 的默认 model/variant 构造完整快照。 */
  @Override
  public RuntimeConfigSnapshot resolveAgent(long definitionId, boolean yoloEnabled) {
    AgentDefinitionDO definition = requireDefinition(definitionId);
    if (definition.getModelId() == null || definition.getModelId() <= 0) {
      throw new IllegalArgumentException("agent definition has no default model: " + definitionId);
    }
    return resolve(definition, definition.getModelId(), definition.getVariant(), yoloEnabled);
  }

  /** 复制现有快照，只替换完整、当前可用的 model/variant；并重新校验 tool capability。 */
  @Override
  public RuntimeConfigSnapshot replaceModel(
      RuntimeConfigSnapshot current, long modelId, String requestedVariant) {
    Objects.requireNonNull(current, "current");
    ModelSnapshot model = resolveModel(modelId, requestedVariant);
    if (!current.tools().isEmpty() && !model.descriptor().tools()) {
      throw new IllegalArgumentException("selected model does not support configured tools");
    }
    return new RuntimeConfigSnapshot(
        current.agent(), model, current.tools(), current.skills(), current.yoloEnabled());
  }

  private RuntimeConfigSnapshot resolve(
      AgentDefinitionDO definition, long modelId, String requestedVariant, boolean yoloEnabled) {
    AgentDefinitionConfigDTO config = definitionConfigCodec.decode(definition.getConfigJson());
    String environmentName = optionalEnvironmentName(config.getEnvironmentName());
    LiveEnvironment environment =
        environmentName == null ? null : requireReadyEnvironment(environmentName);
    List<ToolBinding> tools =
        resolveTools(config.getTools(), environment, !config.getSkills().isEmpty());
    List<SkillSnapshot> skills = resolveSkills(config.getSkills(), environment);
    return new RuntimeConfigSnapshot(
        new AgentSnapshot(definition.getId(), definition.getName(), definition.getSystemPrompt()),
        resolveModel(modelId, requestedVariant),
        tools,
        skills,
        yoloEnabled);
  }

  private ModelSnapshot resolveModel(long modelId, String requestedVariant) {
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
    // 先在 resolver 内完成 variant 身份校验，确保 ModelSnapshot 仅承载已知 variant。
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
    return new ModelSnapshot(descriptor, variant);
  }

  private List<ToolBinding> resolveTools(
      List<String> names, LiveEnvironment environment, boolean hasSelectedSkills) {
    Objects.requireNonNull(names, "agent tools");
    Map<String, ToolDescriptor> platform = platformTools();
    List<ToolBinding> result = new ArrayList<>();
    Set<String> selected = new HashSet<>();
    for (String name : names) {
      requireShortName(name, "tool");
      ToolDescriptor descriptor = platform.get(name);
      if (descriptor != null) {
        addBinding(result, selected, ToolBinding.of(descriptor));
        continue;
      }
      if (environment == null) {
        throw new IllegalArgumentException("unknown tool without ready environment: " + name);
      }
      ToolDescriptor environmentTool = uniqueEnvironmentTool(environment, name);
      addBinding(result, selected, ToolBinding.of(environmentTool, environment.environmentName()));
    }
    for (String automatic : List.of("create_goal", "get_goal", "update_goal")) {
      ToolDescriptor descriptor = platform.get(automatic);
      if (descriptor != null) {
        addBinding(result, selected, ToolBinding.of(descriptor));
      }
    }
    if (hasSelectedSkills) {
      ToolDescriptor loadSkill = platform.get("load_skill");
      if (loadSkill != null) {
        addBinding(result, selected, ToolBinding.of(loadSkill));
      }
    }
    return List.copyOf(result);
  }

  private List<SkillSnapshot> resolveSkills(List<String> names, LiveEnvironment environment) {
    Objects.requireNonNull(names, "agent skills");
    if (names.isEmpty()) {
      return List.of();
    }
    if (environment == null) {
      throw new IllegalArgumentException("skills require a ready environment");
    }
    Map<String, SkillSnapshot> available = new HashMap<>();
    environment
        .skills()
        .forEach(
            skill ->
                available.putIfAbsent(
                    skill.name(),
                    new SkillSnapshot(
                        skill.name(), skill.description(), environment.environmentName())));
    List<SkillSnapshot> result = new ArrayList<>();
    for (String name : names) {
      requireShortName(name, "skill");
      SkillSnapshot skill = available.get(name);
      if (skill == null) {
        throw new IllegalArgumentException("selected skill is unavailable: " + name);
      }
      result.add(skill);
    }
    return List.copyOf(result);
  }

  private Map<String, ToolDescriptor> platformTools() {
    Map<String, ToolDescriptor> result = new HashMap<>();
    for (ToolDescriptor descriptor : toolFactories.descriptors()) {
      ToolDescriptor prior = result.putIfAbsent(descriptor.name(), descriptor);
      if (prior != null) {
        throw new IllegalArgumentException(
            "ambiguous registered PLATFORM tool name: " + descriptor.name());
      }
    }
    return result;
  }

  private static ToolDescriptor uniqueEnvironmentTool(LiveEnvironment environment, String name) {
    ToolDescriptor found = null;
    for (ToolDescriptor descriptor : environment.tools()) {
      if (!descriptor.name().equals(name)) {
        continue;
      }
      if (found != null) {
        throw new IllegalArgumentException("ambiguous environment tool: " + name);
      }
      found = descriptor;
    }
    if (found == null) {
      throw new IllegalArgumentException("unknown environment tool: " + name);
    }
    return found;
  }

  private static void addBinding(
      List<ToolBinding> bindings, Set<String> selected, ToolBinding binding) {
    if (!selected.add(binding.descriptor().name())) {
      throw new IllegalArgumentException("duplicate selected tool: " + binding.descriptor().name());
    }
    bindings.add(binding);
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

  private LiveEnvironment requireReadyEnvironment(String environmentName) {
    return environmentRegistry
        .find(environmentName)
        .filter(LiveEnvironment::isReady)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "environment is offline or missing: " + environmentName));
  }

  private static String optionalEnvironmentName(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (!value.equals(value.trim())) {
      throw new IllegalArgumentException("environmentName must not have surrounding whitespace");
    }
    return value;
  }

  private static void requireShortName(String value, String kind) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(kind + " name must not be blank");
    }
    if (value.indexOf(':') >= 0 || value.indexOf('/') >= 0 || value.indexOf('@') >= 0) {
      throw new IllegalArgumentException(kind + " selection must use a short name: " + value);
    }
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
}
