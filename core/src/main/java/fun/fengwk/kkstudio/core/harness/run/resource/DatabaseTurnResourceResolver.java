package fun.fengwk.kkstudio.core.harness.run.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
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
import fun.fengwk.kkstudio.harness.runtime.run.TurnResourceResolver;
import fun.fengwk.kkstudio.harness.runtime.run.TurnResources;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.session.SessionStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Resolves a frozen Harness context to persisted Provider, model, variant, and tool resources. */
public final class DatabaseTurnResourceResolver implements TurnResourceResolver {

  private final SessionStore sessionStore;
  private final SessionEntryStore entryStore;
  private final AgentModelRepository modelRepository;
  private final AgentProviderRepository providerRepository;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final HarnessExtensionHost extensionHost;
  private final HarnessRuntimeProperties properties;
  private final ObjectMapper objectMapper;

  public DatabaseTurnResourceResolver(
      SessionStore sessionStore,
      SessionEntryStore entryStore,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentModelRuntimeConfigParser modelConfigParser,
      HarnessExtensionHost extensionHost,
      HarnessRuntimeProperties properties,
      ObjectMapper objectMapper) {
    this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    this.entryStore = Objects.requireNonNull(entryStore, "entryStore");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.extensionHost = Objects.requireNonNull(extensionHost, "extensionHost");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public TurnResources resolve(long sessionId, AgentRuntimeConfig config) {
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
    ModelProvider provider =
        Objects.requireNonNull(
            adapter.create(providerDescriptor(persistedProvider, providerType)), "model provider");

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
    return new TurnResources(
        provider,
        model,
        variant,
        toolBindings,
        properties.resolvedWorkdir(),
        properties.resolvedEnvironmentRoot());
  }

  private void requireFrozenSnapshot(long sessionId) {
    Session session =
        sessionStore
            .find(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("unknown session: " + sessionId));
    if (session.leafEntryId() == null) {
      throw new IllegalArgumentException("session has no active leaf: " + sessionId);
    }
    List<SessionEntry> path = entryStore.loadPath(sessionId, session.leafEntryId());
    for (int index = path.size() - 1; index >= 0; index--) {
      if (path.get(index).payload() instanceof AgentSnapshotEntryPayload) {
        return;
      }
    }
    throw new IllegalArgumentException(
        "session has no frozen agent snapshot on its active path: " + sessionId);
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
        Duration.ofMillis(timeoutMillis(provider.getConfigJson())));
  }

  private long timeoutMillis(String configJson) {
    JsonNode config;
    try {
      config = objectMapper.readTree(configJson);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("persisted provider configJson must be valid JSON", error);
    }
    if (config == null || !config.isObject()) {
      throw new IllegalArgumentException("persisted provider configJson must be an object");
    }
    JsonNode timeout = config.get("timeoutMillis");
    if (timeout == null || !timeout.isIntegralNumber() || !timeout.canConvertToLong()) {
      throw new IllegalArgumentException(
          "persisted provider configJson.timeoutMillis must be an integer");
    }
    long value = timeout.longValue();
    if (value <= 0) {
      throw new IllegalArgumentException(
          "persisted provider configJson.timeoutMillis must be positive");
    }
    return value;
  }

  private List<ToolBinding> toolBindings(List<String> frozenTools) {
    List<ToolBinding> result = new ArrayList<>();
    for (String reference : List.copyOf(Objects.requireNonNull(frozenTools, "tools"))) {
      Tool tool = resolveTool(reference);
      if (ToolTargetType.fromExecutionMode(tool.descriptor().executionMode())
          == ToolTargetType.ENVIRONMENT) {
        throw new IllegalArgumentException(
            "frozen ENVIRONMENT tool requires an environment binding: " + reference);
      }
      result.add(ToolBinding.of(tool.descriptor()));
    }
    return List.copyOf(result);
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
