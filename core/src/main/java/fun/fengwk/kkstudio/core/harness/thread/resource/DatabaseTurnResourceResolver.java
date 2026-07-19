package fun.fengwk.kkstudio.core.harness.thread.resource;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.core.environment.service.ToolEnvironmentIds;
import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;
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
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves a frozen Harness context to persisted Provider, model, variant, and tool resources. */
@Component
public final class DatabaseTurnResourceResolver implements TurnResourceResolver {

  private static final String ENVIRONMENT_REFERENCE_PREFIX = "environment:";

  private final SessionStore sessionStore;
  private final AgentModelRepository modelRepository;
  private final AgentProviderRepository providerRepository;
  private final AgentProviderConfigurationCodec providerConfigurationCodec;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final HarnessExtensionHost extensionHost;
  private final HarnessRuntimeProperties properties;
  private final ToolEnvironmentRepository environmentRepository;
  private final DaemonToolCapabilitiesCodec capabilitiesCodec;

  public DatabaseTurnResourceResolver(
      SessionStore sessionStore,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentProviderConfigurationCodec providerConfigurationCodec,
      AgentModelRuntimeConfigParser modelConfigParser,
      HarnessExtensionHost extensionHost,
      HarnessRuntimeProperties properties,
      ToolEnvironmentRepository environmentRepository,
      DaemonToolCapabilitiesCodec capabilitiesCodec) {
    this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.providerConfigurationCodec =
        Objects.requireNonNull(providerConfigurationCodec, "providerConfigurationCodec");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.extensionHost = Objects.requireNonNull(extensionHost, "extensionHost");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.capabilitiesCodec = Objects.requireNonNull(capabilitiesCodec, "capabilitiesCodec");
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

    ParsedAgentModelConfig parsed =
        modelConfigParser.parse(
            persistedModel.getCapabilitiesJson(), persistedModel.getConfigJson());
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
            parsed.capabilities(),
            parsed.variants(),
            parsed.pricing(),
            cachePolicy);
    ModelVariant variant =
        parsed.variants().stream()
            .filter(candidate -> candidate.name().equals(config.variant()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "frozen model variant is unavailable: " + config.variant()));
    List<ToolBinding> toolBindings = toolBindings(config.tools());
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
    // Path-level snapshot validation is performed when ThreadProcessor builds context from head.
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

  private List<ToolBinding> toolBindings(List<String> frozenTools) {
    List<ToolBinding> result = new ArrayList<>();
    Set<String> descriptorNames = new HashSet<>();
    for (String reference : List.copyOf(Objects.requireNonNull(frozenTools, "tools"))) {
      ToolBinding binding;
      if (isEnvironmentReference(reference)) {
        binding = resolveEnvironmentTool(reference);
      } else {
        Tool tool = resolveTool(reference);
        if (ToolTargetType.fromExecutionMode(tool.descriptor().executionMode())
            == ToolTargetType.ENVIRONMENT) {
          throw new IllegalArgumentException(
              "frozen ENVIRONMENT tool requires an environment binding: " + reference);
        }
        binding = ToolBinding.of(tool.descriptor());
      }
      String descriptorName = binding.descriptor().name();
      if (!descriptorNames.add(descriptorName)) {
        throw new IllegalArgumentException(
            "frozen tool descriptor names must be unique across bindings: " + descriptorName);
      }
      result.add(binding);
    }
    return List.copyOf(result);
  }

  private static boolean isEnvironmentReference(String reference) {
    return reference != null && reference.startsWith(ENVIRONMENT_REFERENCE_PREFIX);
  }

  private ToolBinding resolveEnvironmentTool(String reference) {
    String body = reference.substring(ENVIRONMENT_REFERENCE_PREFIX.length());
    int slash = body.indexOf('/');
    if (slash <= 0 || slash == body.length() - 1) {
      throw new IllegalArgumentException(
          "frozen Environment reference must use environment:<id>/<tool>@<version>: " + reference);
    }
    String idPart = body.substring(0, slash);
    String toolPart = body.substring(slash + 1);
    long environmentId;
    try {
      environmentId = ToolEnvironmentIds.parsePositive(idPart, "environmentId");
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "frozen Environment reference id must be an unsigned positive decimal: " + reference,
          error);
    }
    int at = toolPart.lastIndexOf('@');
    if (at <= 0 || at == toolPart.length() - 1) {
      throw new IllegalArgumentException(
          "frozen Environment tool reference must include name@version: " + reference);
    }
    String toolName = toolPart.substring(0, at);
    String toolVersion = toolPart.substring(at + 1);
    if (toolName.isBlank() || toolVersion.isBlank()) {
      throw new IllegalArgumentException(
          "frozen Environment tool name and version must not be blank: " + reference);
    }
    ToolEnvironment environment = environmentRepository.getById(environmentId);
    if (environment == null) {
      throw new IllegalArgumentException("frozen Environment not found: " + reference);
    }
    DaemonToolCapabilitiesCodec.DaemonToolCapabilities capabilities;
    try {
      capabilities = capabilitiesCodec.decode(environment.getCapabilitiesJson());
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(
          "persisted Environment capabilitiesJson is not a canonical Daemon CAPABILITIES payload: "
              + reference,
          error);
    }
    Map<String, ToolDescriptor> byKey = new LinkedHashMap<>();
    for (ToolDescriptor descriptor : capabilities.tools()) {
      byKey.put(descriptor.name() + "@" + descriptor.version(), descriptor);
    }
    ToolDescriptor descriptor = byKey.get(toolName + "@" + toolVersion);
    if (descriptor == null) {
      throw new IllegalArgumentException("frozen Environment capability not found: " + reference);
    }
    if (descriptor.executionMode() != ToolExecutionMode.ENVIRONMENT) {
      throw new IllegalArgumentException(
          "frozen Environment tool must use ENVIRONMENT execution mode: " + reference);
    }
    return new ToolBinding(descriptor, ToolTargetType.ENVIRONMENT, environmentId);
  }

  private Tool resolveTool(String reference) {
    int separator = reference.lastIndexOf('@');
    if (separator < 0) {
      List<String> versions =
          extensionHost.toolFactories().stream()
              .map(factory -> factory.descriptor())
              .filter(descriptor -> descriptor.name().equals(reference))
              .map(descriptor -> descriptor.version())
              .toList();
      if (versions.size() != 1) {
        throw new IllegalArgumentException(
            "frozen tool name must resolve to exactly one registered version: " + reference);
      }
      return extensionHost.createTool(reference, versions.get(0)).orElseThrow();
    }
    if (separator == 0 || separator == reference.length() - 1) {
      throw new IllegalArgumentException(
          "frozen tool reference must use name or name@version: " + reference);
    }
    String name = reference.substring(0, separator);
    String version = reference.substring(separator + 1);
    return extensionHost
        .createTool(name, version)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "frozen tool is unavailable: " + name + "@" + version));
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
