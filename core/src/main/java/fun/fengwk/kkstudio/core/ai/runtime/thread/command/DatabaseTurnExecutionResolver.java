package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.plan.PlanningFailure;
import fun.fengwk.kkstudio.harness.runtime.model.plan.PlanningFailureKind;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ResolvedTurnExecution;
import fun.fengwk.kkstudio.harness.runtime.model.plan.TurnExecutionResolver;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the current catalog and live Environment state for one turn.
 *
 * <p>Only the name references in {@link TurnSettings} cross this boundary. Every successful result
 * contains the exact live model, tool, and skill facts that the planner freezes into one model
 * invocation request.
 */
@Component
public final class DatabaseTurnExecutionResolver implements TurnExecutionResolver {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository modelRepository;
  private final AgentProviderRepository providerRepository;
  private final AgentDefinitionConfigCodec agentConfigCodec;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final ProviderFactories providerFactories;
  private final ToolCatalog toolCatalog;
  private final LiveEnvironmentRegistry environmentRegistry;

  public DatabaseTurnExecutionResolver(
      AgentDefinitionRepository agentDefinitionRepository,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentDefinitionConfigCodec agentConfigCodec,
      AgentModelRuntimeConfigParser modelConfigParser,
      ProviderFactories providerFactories,
      ToolCatalog toolCatalog,
      LiveEnvironmentRegistry environmentRegistry) {
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.providerFactories = Objects.requireNonNull(providerFactories, "providerFactories");
    this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
  }

  @Override
  public Resolution resolve(TurnSettings settings) {
    if (settings == null) {
      return failed(PlanningFailureKind.MISSING_TURN_SETTINGS, "turn settings are required");
    }
    try {
      return resolveLive(settings);
    } catch (RuntimeException error) {
      return failed(
          PlanningFailureKind.INVALID_TURN_SETTINGS,
          messageOrClass("cannot resolve turn settings", error));
    }
  }

  private Resolution resolveLive(TurnSettings settings) {
    AgentDefinition agent = agentDefinitionRepository.getByName(settings.agentName());
    if (agent == null) {
      return failed(
          PlanningFailureKind.AGENT_NOT_FOUND, "agent not found: " + settings.agentName());
    }
    String providerName = requireReference(agent.getModelProviderName(), "agent model provider");
    String modelName = requireReference(agent.getModelName(), "agent model");

    AgentProvider provider = providerRepository.getByName(providerName);
    if (provider == null) {
      return failed(PlanningFailureKind.PROVIDER_NOT_FOUND, "provider not found: " + providerName);
    }
    ProviderType providerType;
    try {
      providerType = toProviderType(provider.getProviderType());
    } catch (IllegalArgumentException error) {
      return failed(PlanningFailureKind.INVALID_TURN_SETTINGS, error.getMessage());
    }

    AgentModel model = modelRepository.getByProviderNameAndName(providerName, modelName);
    if (model == null) {
      return failed(
          PlanningFailureKind.MODEL_NOT_FOUND,
          "model not found: " + providerName + "/" + modelName);
    }

    ParsedAgentModelConfig parsedModel;
    AgentDefinitionConfigDTO agentConfig;
    try {
      parsedModel = modelConfigParser.parse(model.getConfigJson());
      agentConfig = agentConfigCodec.decode(agent.getConfigJson());
    } catch (RuntimeException error) {
      return failed(
          PlanningFailureKind.INVALID_TURN_SETTINGS,
          messageOrClass("stored Agent or Model configuration is invalid", error));
    }

    String variantName = effectiveVariant(agent.getVariant(), parsedModel.defaultVariant());
    ModelVariant variant = findVariant(parsedModel, variantName);
    if (variant == null) {
      return failed(
          PlanningFailureKind.VARIANT_NOT_FOUND,
          "model variant not found: " + providerName + "/" + modelName + " variant=" + variantName);
    }

    Optional<LiveEnvironment> environment = resolveEnvironment(settings.environmentName());
    if (settings.environmentName() != null && environment.isEmpty()) {
      return failed(
          PlanningFailureKind.ENVIRONMENT_NOT_FOUND,
          "environment not found or not READY/open: " + settings.environmentName());
    }

    List<ToolBinding> toolBindings = resolveTools(agentConfig.getTools(), settings, environment);
    if (toolBindings == null) {
      return failed(
          PlanningFailureKind.ENVIRONMENT_NOT_FOUND,
          "environment not found or not READY/open: " + settings.environmentName());
    }
    if (toolBindings.isEmpty() && containsEnvironmentTool(agentConfig.getTools())) {
      return failed(
          PlanningFailureKind.ENVIRONMENT_NOT_FOUND,
          "environment not found or not READY/open: " + settings.environmentName());
    }
    if (toolBindings.size() != agentConfig.getTools().size()) {
      String missing = firstMissingTool(agentConfig.getTools(), toolBindings);
      return failed(PlanningFailureKind.TOOL_NOT_FOUND, "tool not found: " + missing);
    }

    List<SkillBinding> skillBindings =
        resolveSkills(agentConfig.getSkills(), settings.environmentName(), environment);
    if (skillBindings == null) {
      return failed(
          PlanningFailureKind.ENVIRONMENT_NOT_FOUND,
          "environment not found or not READY/open: " + settings.environmentName());
    }
    if (skillBindings.size() != agentConfig.getSkills().size()) {
      String missing = firstMissingSkill(agentConfig.getSkills(), skillBindings);
      return failed(
          PlanningFailureKind.SKILL_NOT_FOUND,
          "skill not found in environment " + settings.environmentName() + ": " + missing);
    }

    Optional<ProviderFactory> providerFactory = providerFactories.lookup(providerType);
    if (providerFactory.isEmpty()) {
      return failed(
          PlanningFailureKind.PROVIDER_NOT_FOUND,
          "provider factory not found for " + providerName + " (" + providerType + ")");
    }
    if ((!toolBindings.isEmpty() || !skillBindings.isEmpty()) && !parsedModel.tools()) {
      return failed(
          PlanningFailureKind.INVALID_TURN_SETTINGS,
          "model does not support tools: " + providerName + "/" + modelName);
    }

    if (!skillBindings.isEmpty()) {
      Optional<ToolDescriptor> loadSkill = toolCatalog.findRuntimeManaged(LoadSkillTool.NAME);
      if (loadSkill.isEmpty()) {
        return failed(
            PlanningFailureKind.TOOL_NOT_FOUND,
            "runtime-managed tool not found: " + LoadSkillTool.NAME);
      }
      toolBindings = new ArrayList<>(toolBindings);
      toolBindings.add(ToolBinding.of(loadSkill.get()));
    }

    PromptCacheCapability cacheCapability = providerFactory.get().promptCacheCapability();
    PromptCachePolicy cachePolicy =
        PromptCachePolicy.of(
            cacheCapability,
            cacheCapability.supports(PromptCacheRetention.SHORT)
                ? PromptCacheRetention.SHORT
                : PromptCacheRetention.NONE);
    ModelDescriptor descriptor =
        new ModelDescriptor(
            providerName,
            modelName,
            providerType,
            parsedModel.tools(),
            parsedModel.reasoning(),
            parsedModel.pricing(),
            cachePolicy);
    return new Resolution.Resolved(
        new ResolvedTurnExecution(
            agent.getSystemPrompt(),
            descriptor,
            variant,
            toolBindings,
            skillBindings,
            settings.yoloEnabled()));
  }

  private Optional<LiveEnvironment> resolveEnvironment(String environmentName) {
    if (environmentName == null) {
      return Optional.empty();
    }
    return environmentRegistry.find(environmentName).filter(LiveEnvironment::isReady);
  }

  private List<ToolBinding> resolveTools(
      List<String> names, TurnSettings settings, Optional<LiveEnvironment> environment) {
    List<ToolBinding> bindings = new ArrayList<>();
    for (String name : names) {
      Optional<ToolDescriptor> local = toolCatalog.findLocal(name);
      if (local.isPresent()) {
        bindings.add(ToolBinding.of(local.get()));
        continue;
      }
      Optional<ToolDescriptor> environmentTool = toolCatalog.findEnvironment(name);
      if (environmentTool.isEmpty()) {
        continue;
      }
      if (settings.environmentName() == null || environment.isEmpty()) {
        return null;
      }
      bindings.add(ToolBinding.of(environmentTool.get(), settings.environmentName()));
    }
    return bindings;
  }

  private List<SkillBinding> resolveSkills(
      List<String> names, String environmentName, Optional<LiveEnvironment> environment) {
    if (names.isEmpty()) {
      return List.of();
    }
    if (environmentName == null || environment.isEmpty()) {
      return null;
    }
    List<SkillBinding> bindings = new ArrayList<>();
    for (String name : names) {
      DaemonSkillDescriptor descriptor =
          environment.get().skills().stream()
              .filter(skill -> skill.name().equals(name))
              .findFirst()
              .orElse(null);
      if (descriptor == null) {
        continue;
      }
      bindings.add(new SkillBinding(name, descriptor.description(), environmentName));
    }
    return List.copyOf(bindings);
  }

  private static ModelVariant findVariant(ParsedAgentModelConfig model, String name) {
    return model.variants().stream()
        .filter(variant -> variant.id().equals(name))
        .findFirst()
        .orElse(null);
  }

  private static String effectiveVariant(String requested, String defaultVariant) {
    if (requested == null || requested.isBlank()) {
      return defaultVariant;
    }
    return requested.trim();
  }

  private static boolean containsEnvironmentTool(List<String> names) {
    return names.stream().anyMatch(name -> EnvironmentToolCatalog.find(name).isPresent());
  }

  private static String firstMissingTool(List<String> names, List<ToolBinding> bindings) {
    for (String name : names) {
      if (bindings.stream().noneMatch(binding -> binding.descriptor().name().equals(name))) {
        return name;
      }
    }
    return "<unknown>";
  }

  private static String firstMissingSkill(List<String> names, List<SkillBinding> bindings) {
    for (String name : names) {
      if (bindings.stream().noneMatch(binding -> binding.name().equals(name))) {
        return name;
      }
    }
    return "<unknown>";
  }

  private static String requireReference(String value, String description) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(description + " reference is blank");
    }
    return value;
  }

  private static ProviderType toProviderType(AgentProviderType type) {
    if (type == null) {
      throw new IllegalArgumentException("provider type must not be null");
    }
    return switch (type) {
      case openai -> ProviderType.OPENAI;
      case openai_response -> ProviderType.OPENAI_RESPONSES;
      case anthropic -> ProviderType.ANTHROPIC;
      case google -> ProviderType.GOOGLE;
    };
  }

  private static Resolution.Failed failed(PlanningFailureKind kind, String message) {
    return new Resolution.Failed(new PlanningFailure(kind, message));
  }

  private static String messageOrClass(String prefix, RuntimeException error) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? prefix : prefix + ": " + detail;
  }
}
