package fun.fengwk.kkstudio.core.ai.runtime.model;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRevisionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProviderRevision;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.util.Objects;

/** Resolves the immutable persisted Provider revision for a frozen model request. */
@Component
public final class DatabaseProviderResolutionService implements ProviderResolutionService {

  private final AgentProviderRevisionRepository providerRevisionRepository;
  private final AgentProviderConfigurationCodec providerConfigurationCodec;
  private final ProviderFactories providerFactories;

  public DatabaseProviderResolutionService(
      AgentProviderRevisionRepository providerRevisionRepository,
      AgentProviderConfigurationCodec providerConfigurationCodec,
      ProviderFactories providerFactories) {
    this.providerRevisionRepository =
        Objects.requireNonNull(providerRevisionRepository, "providerRevisionRepository");
    this.providerConfigurationCodec =
        Objects.requireNonNull(providerConfigurationCodec, "providerConfigurationCodec");
    this.providerFactories = Objects.requireNonNull(providerFactories, "providerFactories");
  }

  @Override
  public ResolvedExecution resolve(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    String providerName = request.model().providerName();
    long providerVersion = request.model().providerVersion();
    AgentProviderRevision revision =
        providerRevisionRepository.getByProviderNameAndVersion(providerName, providerVersion);
    if (revision == null) {
      throw new IllegalArgumentException(
          "persisted provider revision not found: " + providerName + "@" + providerVersion);
    }
    ProviderType requestedType = request.model().providerType();
    ProviderType persistedType = toProviderType(revision.getProviderType());
    if (persistedType != requestedType) {
      throw new IllegalArgumentException(
          "persisted provider type "
              + persistedType
              + " does not match frozen request type "
              + requestedType);
    }
    var timeoutPolicy = providerConfigurationCodec.readTimeoutPolicy(revision.getConfigJson());
    new ProviderDescriptor(providerName, requestedType, revision.getBaseUrl(), timeoutPolicy);
    var factory =
        providerFactories
            .lookup(requestedType)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "ProviderFactory is not registered for " + requestedType));
    ProviderAdapter adapter;
    try {
      adapter = factory.create(revision.getCredential(), revision.getConfigJson());
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(
          "cannot create provider adapter for " + providerName, error);
    }
    if (adapter == null) {
      throw new IllegalArgumentException(
          "ProviderFactory returned null adapter for " + providerName);
    }
    if (adapter.providerType() != requestedType) {
      throw new IllegalArgumentException(
          "ProviderFactory returned adapter type "
              + adapter.providerType()
              + " for "
              + requestedType);
    }
    return new ResolvedExecution(
        timeoutPolicy,
        effectiveTimeoutPolicy -> {
          ProviderDescriptor descriptor =
              new ProviderDescriptor(
                  providerName, requestedType, revision.getBaseUrl(), effectiveTimeoutPolicy);
          try {
            var modelProvider = adapter.create(descriptor);
            if (modelProvider == null) {
              throw new IllegalArgumentException(
                  "cannot create ModelProvider for " + providerName + ": null provider");
            }
            return modelProvider;
          } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException
                && error.getMessage() != null
                && error.getMessage().startsWith("cannot create ModelProvider")) {
              throw error;
            }
            throw new IllegalArgumentException(
                "cannot create ModelProvider for " + providerName, error);
          }
        });
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
}
