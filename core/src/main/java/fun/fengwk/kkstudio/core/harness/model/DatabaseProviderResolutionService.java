package fun.fengwk.kkstudio.core.harness.model;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.ProviderFactory;
import fun.fengwk.kkstudio.share.model.AgentProviderType;

import java.util.Objects;

/**
 * Default {@link ProviderResolutionService}: PostgreSQL-backed Provider row + Harness extension
 * adapter assembly.
 *
 * <p>The service is the only place that resolves credential values from the stable Provider
 * reference. It freezes those values inside a short-lived adapter closure without exposing them
 * through Runtime records. The SDK-bound {@link ModelProvider} is created later on the Provider I/O
 * executor with a timeout capped by the durable invocation deadline.
 */
@Component
public class DatabaseProviderResolutionService implements ProviderResolutionService {

  private final AgentProviderRepository providerRepository;
  private final AgentProviderConfigurationCodec providerConfigurationCodec;
  private final HarnessExtensionHost extensionHost;

  public DatabaseProviderResolutionService(
      AgentProviderRepository providerRepository,
      AgentProviderConfigurationCodec providerConfigurationCodec,
      HarnessExtensionHost extensionHost) {
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.providerConfigurationCodec =
        Objects.requireNonNull(providerConfigurationCodec, "providerConfigurationCodec");
    this.extensionHost = Objects.requireNonNull(extensionHost, "extensionHost");
  }

  @Override
  public ResolvedExecution resolve(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    long providerResourceId = request.model().providerResourceId();
    if (providerResourceId <= 0) {
      throw new IllegalArgumentException("providerResourceId must be positive");
    }
    ProviderType frozenType = request.model().providerType();
    Objects.requireNonNull(frozenType, "frozen providerType");

    AgentProvider persisted = providerRepository.getById(providerResourceId);
    if (persisted == null) {
      throw new IllegalArgumentException("persisted provider not found: " + providerResourceId);
    }
    ProviderType persistedType = toProviderType(persisted.getProviderType(), providerResourceId);
    if (persistedType != frozenType) {
      throw new IllegalArgumentException(
          "persisted provider type "
              + persistedType
              + " does not match frozen ProviderRequest type "
              + frozenType
              + " for "
              + providerResourceId);
    }

    ProviderFactory providerFactory =
        extensionHost
            .providerFactory(frozenType)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "no Harness ProviderFactory registered for " + frozenType));

    String baseUrl = persisted.getBaseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException(
          "persisted provider baseUrl must not be blank: " + providerResourceId);
    }

    String credential = persisted.getCredential();
    String configJson = persisted.getConfigJson() == null ? "" : persisted.getConfigJson();
    ModelCallTimeoutPolicy timeoutPolicy = providerConfigurationCodec.readTimeoutPolicy(configJson);

    ProviderAdapter adapter;
    try {
      adapter = providerFactory.create(credential, configJson);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException(
          "cannot create provider adapter for "
              + frozenType
              + " from persisted provider "
              + providerResourceId,
          failure);
    }
    if (adapter == null) {
      throw new IllegalArgumentException(
          "ProviderFactory returned a null adapter for " + frozenType);
    }
    if (adapter.providerType() != frozenType) {
      throw new IllegalArgumentException(
          "Harness ProviderFactory returned adapter for " + adapter.providerType());
    }

    String providerId = Long.toString(providerResourceId);
    return new ResolvedExecution(
        timeoutPolicy,
        effectiveTimeoutPolicy ->
            createModelProvider(adapter, providerId, frozenType, baseUrl, effectiveTimeoutPolicy));
  }

  private static ModelProvider createModelProvider(
      ProviderAdapter adapter,
      String providerId,
      ProviderType providerType,
      String baseUrl,
      ModelCallTimeoutPolicy timeoutPolicy) {
    ProviderDescriptor descriptor =
        new ProviderDescriptor(providerId, providerType, baseUrl, timeoutPolicy);
    try {
      ModelProvider modelProvider = adapter.create(descriptor);
      if (modelProvider == null) {
        throw new IllegalArgumentException(
            "ProviderAdapter returned a null ModelProvider for " + providerType);
      }
      return modelProvider;
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException(
          "cannot create ModelProvider for " + providerType + " at " + baseUrl, failure);
    }
  }

  private static ProviderType toProviderType(AgentProviderType type, long providerResourceId) {
    if (type == null) {
      throw new IllegalArgumentException(
          "persisted provider type must not be null: " + providerResourceId);
    }
    return switch (type) {
      case openai -> ProviderType.OPENAI;
      case openai_response -> ProviderType.OPENAI_RESPONSES;
      case anthropic -> ProviderType.ANTHROPIC;
      case google -> ProviderType.GOOGLE;
    };
  }
}
