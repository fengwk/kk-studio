package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextProviderId;

import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import java.time.Duration;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * AgentProviderMutationFactory 负责 provider 写路径的入参校验、标准化与实体组装。
 *
 * @author fengwk
 */
@Component
final class AgentProviderMutationFactory {

  private static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

  private final AgentEditableSupport editableSupport;

  AgentProviderMutationFactory(AgentEditableSupport editableSupport) {
    if (editableSupport == null) {
      throw new IllegalArgumentException("editableSupport must not be null");
    }
    this.editableSupport = editableSupport;
  }

  Mutation newCreateMutation(AgentProviderCreateDTO createDTO) {
    return newMutation(createDTO, true, null);
  }

  Mutation newUpdateMutation(String currentName, AgentProviderUpdateDTO updateDTO) {
    requireNonBlank(currentName, "currentName");
    return newMutation(updateDTO, false, currentName);
  }

  AgentProvider newProvider(Mutation mutation) {
    requireNonNull(mutation, "mutation");
    AgentProvider provider = new AgentProvider();
    provider.setId(nextProviderId());
    apply(provider, mutation);
    return provider;
  }

  void apply(AgentProvider provider, Mutation mutation) {
    requireNonNull(provider, "provider");
    requireNonNull(mutation, "mutation");
    provider.setName(mutation.name());
    provider.setDescription(mutation.description());
    provider.setProviderType(mutation.providerType());
    provider.setBaseUrl(mutation.baseUrl());
    provider.setApiKey(mutation.apiKey());
    provider.setTimeout(mutation.timeout());
    provider.setStreamIdleTimeout(mutation.streamIdleTimeout());
  }

  private Mutation newMutation(
      AgentProviderEditablePropertiesDTO properties, boolean requireName, String fallbackName) {
    validateEditable(properties, requireName);
    return new Mutation(
        editableSupport.firstNonBlank(properties.getName(), fallbackName),
        editableSupport.trimToNull(properties.getDescription()),
        toProviderType(properties.getProviderType()),
        editableSupport.trimToNull(properties.getBaseUrl()),
        editableSupport.trimToNull(properties.getApiKey()),
        toDuration(properties.getTimeoutMillis()),
        toDuration(properties.getStreamIdleTimeoutMillis()));
  }

  private void validateEditable(
      AgentProviderEditablePropertiesDTO properties, boolean requireName) {
    if (properties == null) {
      throw new IllegalArgumentException("agent provider body must not be null");
    }
    if (requireName && editableSupport.trimToNull(properties.getName()) == null) {
      throw new IllegalArgumentException("agent provider name must not be blank");
    }
    if (editableSupport.trimToNull(properties.getProviderType()) == null) {
      throw new IllegalArgumentException("agent provider providerType must not be null");
    }
    toProviderType(properties.getProviderType());
    toDuration(properties.getTimeoutMillis());
    toDuration(properties.getStreamIdleTimeoutMillis());
  }

  private Duration toDuration(Long millis) {
    long value = millis == null ? DEFAULT_TIMEOUT_MILLIS : millis;
    if (value <= 0) {
      throw new IllegalArgumentException("timeout millis must be positive");
    }
    return Duration.ofMillis(value);
  }

  private ProviderType toProviderType(String providerType) {
    try {
      return ProviderType.valueOf(providerType.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unsupported providerType: " + providerType, e);
    }
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  record Mutation(
      String name,
      String description,
      ProviderType providerType,
      String baseUrl,
      String apiKey,
      Duration timeout,
      Duration streamIdleTimeout) {}
}
