package fun.fengwk.kkstudio.core.harness.thread.resource;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.adapter.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.session.SessionStore;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnResourceResolver;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnResources;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves a Thread Turn to Provider, model, variant, and short-name tool bindings.
 *
 * <p>Tool short names resolve platform-first (registered PLATFORM tools) then selected Environment
 * capability. A selected Environment that is offline or missing fails clearly.
 *
 * <p>Platform tools {@code create_goal}/{@code get_goal}/{@code update_goal} are always injected
 * when registered. {@code load_skill} is injected only when the Agent has selected skills.
 */
@Component
public final class DatabaseTurnResourceResolver implements TurnResourceResolver {

  private final SessionStore sessionStore;
  private final AgentModelRepository modelRepository;
  private final AgentProviderRepository providerRepository;
  private final AgentProviderConfigurationCodec providerConfigurationCodec;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final HarnessExtensionHost extensionHost;
  private final HarnessRuntimeProperties properties;
  private final LiveEnvironmentRegistry environmentRegistry;

  public DatabaseTurnResourceResolver(
      SessionStore sessionStore,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentProviderConfigurationCodec providerConfigurationCodec,
      AgentModelRuntimeConfigParser modelConfigParser,
      HarnessExtensionHost extensionHost,
      HarnessRuntimeProperties properties,
      LiveEnvironmentRegistry environmentRegistry) {
    this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.providerConfigurationCodec =
        Objects.requireNonNull(providerConfigurationCodec, "providerConfigurationCodec");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.extensionHost = Objects.requireNonNull(extensionHost, "extensionHost");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
  }

  @Override
  public TurnResources resolve(long sessionId, long threadId, AgentRuntimeConfig config) {
    Objects.requireNonNull(config, "config");
    requireFrozenSnapshot(sessionId);

    long modelId = snowflakeId(config.modelId(), "modelId");
    AgentModel persistedModel = modelRepository.getById(modelId);
    if (persistedModel == null) {
      throw new IllegalArgumentException("frozen agent model not found: " + modelId);
    }
    AgentProvider persistedProvider = providerRepository.getById(persistedModel.getProviderId());
    if (persistedProvider == null) {
      throw new IllegalArgumentException(
          "provider for frozen agent model not found: " + persistedModel.getProviderId());
    }

    ProviderType providerType = providerType(persistedProvider);
    ProviderFactory providerFactory =
        extensionHost
            .providerFactory(providerType)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "no Harness ProviderFactory registered for " + providerType));
    ProviderAdapter adapter =
        Objects.requireNonNull(
            providerFactory.create(
                persistedProvider.getCredential(), persistedProvider.getConfigJson()),
            "provider factory result");
    if (adapter.providerType() != providerType) {
      throw new IllegalStateException(
          "Harness ProviderFactory returned adapter for " + adapter.providerType());
    }
    ProviderDescriptor providerDescriptor = providerDescriptor(persistedProvider, providerType);
    ModelProvider provider =
        Objects.requireNonNull(adapter.create(providerDescriptor), "model provider");

    ParsedAgentModelConfig parsed = modelConfigParser.parse(persistedModel.getConfigJson());
    PromptCachePolicy cachePolicy =
        cachePolicy(providerType, providerFactory.promptCacheCapability());
    ModelDescriptor model =
        new ModelDescriptor(
            persistedProvider.getId(),
            persistedModel.getId(),
            providerType,
            persistedModel.getName(),
            displayName(persistedModel),
            parsed.contextWindow(),
            parsed.maxOutputTokens(),
            parsed.inputModalities(),
            parsed.tools(),
            parsed.reasoning(),
            parsed.variants(),
            parsed.pricing(),
            cachePolicy);
    ModelVariant variant =
        parsed.variants().stream()
            .filter(candidate -> candidate.id().equals(config.variant()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "frozen model variant is unavailable: " + config.variant()));
    List<ToolBinding> toolBindings = toolBindings(config);
    List<ToolDescriptor> toolDescriptors =
        toolBindings.stream().map(ToolBinding::descriptor).toList();
    return new TurnResources(
        provider,
        providerDescriptor.modelCallTimeoutPolicy(),
        model,
        variant,
        toolDescriptors,
        toolBindings,
        properties.resolvedWorkdir(),
        properties.resolvedEnvironmentRoot());
  }

  private void requireFrozenSnapshot(long sessionId) {
    // Path-level snapshot validation is performed when the reconciler builds context from head.
    sessionStore
        .find(sessionId)
        .orElseThrow(() -> new IllegalArgumentException("unknown session: " + sessionId));
  }

  private ProviderDescriptor providerDescriptor(AgentProvider provider, ProviderType providerType) {
    String endpoint = provider.getBaseUrl();
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("persisted provider baseUrl must not be blank");
    }
    return new ProviderDescriptor(
        String.valueOf(provider.getId()),
        providerType,
        endpoint,
        providerConfigurationCodec.readTimeoutPolicy(provider.getConfigJson()));
  }

  private List<ToolBinding> toolBindings(AgentRuntimeConfig config) {
    LiveEnvironment selectedEnvironment = null;
    if (config.environmentName() != null) {
      selectedEnvironment = requireReadyEnvironment(config.environmentName());
    }
    List<ToolBinding> result = new ArrayList<>();
    Set<String> descriptorNames = new HashSet<>();
    for (String shortName : List.copyOf(Objects.requireNonNull(config.tools(), "tools"))) {
      ToolBinding binding = resolveShortNameTool(shortName, selectedEnvironment);
      String descriptorName = binding.descriptor().name();
      if (!descriptorNames.add(descriptorName)) {
        throw new IllegalArgumentException(
            "tool descriptor names must be unique across bindings: " + descriptorName);
      }
      result.add(binding);
    }
    // Platform tools auto-exposed without requiring Agent config listing.
    injectPlatformToolIfRegistered(result, descriptorNames, "create_goal");
    injectPlatformToolIfRegistered(result, descriptorNames, "get_goal");
    injectPlatformToolIfRegistered(result, descriptorNames, "update_goal");
    if (!config.selectedSkills().isEmpty()) {
      injectPlatformToolIfRegistered(result, descriptorNames, "load_skill");
    }
    return List.copyOf(result);
  }

  private void injectPlatformToolIfRegistered(
      List<ToolBinding> result, Set<String> descriptorNames, String shortName) {
    if (!descriptorNames.add(shortName)) {
      return;
    }
    Tool platformTool = resolvePlatformTool(shortName);
    if (platformTool == null) {
      descriptorNames.remove(shortName);
      return;
    }
    result.add(ToolBinding.of(platformTool.descriptor()));
  }

  private ToolBinding resolveShortNameTool(String shortName, LiveEnvironment selectedEnvironment) {
    if (shortName == null || shortName.isBlank()) {
      throw new IllegalArgumentException("tool short name must not be blank");
    }
    if (shortName.indexOf(':') >= 0 || shortName.indexOf('/') >= 0 || shortName.indexOf('@') >= 0) {
      throw new IllegalArgumentException("tool selection must use short names only: " + shortName);
    }
    Tool platformTool = resolvePlatformTool(shortName);
    if (platformTool != null) {
      return ToolBinding.of(platformTool.descriptor());
    }
    if (selectedEnvironment == null) {
      throw new IllegalArgumentException("unknown tool short name: " + shortName);
    }
    ToolDescriptor descriptor = uniqueToolByName(selectedEnvironment.tools(), shortName);
    if (descriptor == null) {
      throw new IllegalArgumentException(
          "unknown tool short name in environment "
              + selectedEnvironment.environmentName()
              + ": "
              + shortName);
    }
    if (descriptor.executionLocation() != ToolExecutionLocation.ENVIRONMENT) {
      throw new IllegalArgumentException(
          "environment tool must use ENVIRONMENT execution location: " + shortName);
    }
    return ToolBinding.of(descriptor, selectedEnvironment.environmentName());
  }

  private Tool resolvePlatformTool(String shortName) {
    List<Tool> matches = new ArrayList<>();
    for (var factory : extensionHost.toolFactories()) {
      ToolDescriptor descriptor = factory.descriptor();
      if (!descriptor.name().equals(shortName)) {
        continue;
      }
      if (descriptor.executionLocation() != ToolExecutionLocation.PLATFORM) {
        continue;
      }
      matches.add(
          extensionHost
              .createTool(descriptor.name(), descriptor.version())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "registered tool factory failed to create "
                              + descriptor.name()
                              + "@"
                              + descriptor.version())));
    }
    if (matches.isEmpty()) {
      return null;
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          "tool short name must resolve to exactly one registered PLATFORM version: " + shortName);
    }
    return matches.get(0);
  }

  private static ToolDescriptor uniqueToolByName(List<ToolDescriptor> tools, String shortName) {
    Map<String, ToolDescriptor> byName = new LinkedHashMap<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (ToolDescriptor tool : tools) {
      counts.merge(tool.name(), 1, Integer::sum);
      byName.putIfAbsent(tool.name(), tool);
    }
    Integer count = counts.get(shortName);
    if (count == null) {
      return null;
    }
    if (count > 1) {
      throw new IllegalArgumentException(
          "environment tool short name is ambiguous across versions: " + shortName);
    }
    return byName.get(shortName);
  }

  private LiveEnvironment requireReadyEnvironment(String environmentName) {
    return environmentRegistry
        .find(environmentName)
        .filter(LiveEnvironment::isReady)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "agent environment is offline or missing: " + environmentName));
  }

  private static PromptCachePolicy cachePolicy(
      ProviderType providerType, PromptCacheCapability capability) {
    capability = Objects.requireNonNull(capability, "promptCacheCapability");
    return switch (providerType) {
      case OPENAI, OPENAI_RESPONSES -> PromptCachePolicy.affinityShort(capability);
      case ANTHROPIC -> PromptCachePolicy.breakpointsShort(capability);
      case GOOGLE -> PromptCachePolicy.automatic(capability);
    };
  }

  private static ProviderType providerType(AgentProvider provider) {
    if (provider.getProviderType() == null) {
      throw new IllegalArgumentException("persisted provider type must not be null");
    }
    return switch (provider.getProviderType()) {
      case openai -> ProviderType.OPENAI;
      case openai_response -> ProviderType.OPENAI_RESPONSES;
      case anthropic -> ProviderType.ANTHROPIC;
      case google -> ProviderType.GOOGLE;
    };
  }

  private static long snowflakeId(String value, String field) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " must be a positive Snowflake ID", error);
    }
  }

  private static String displayName(AgentModel model) {
    return model.getDescription() == null || model.getDescription().isBlank()
        ? model.getName()
        : model.getDescription();
  }
}
