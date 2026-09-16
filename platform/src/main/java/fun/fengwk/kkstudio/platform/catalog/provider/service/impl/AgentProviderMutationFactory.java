package fun.fengwk.kkstudio.platform.catalog.provider.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;

import java.util.Objects;
import java.util.UUID;

/** 规范化可变的 provider 配置，同时保证公开 DTO 不携带凭据。 */
@Component
final class AgentProviderMutationFactory {

  private static final String RESOURCE = "agent_provider";
  private static final int NAME_MAX_LENGTH = 64;
  private static final int BASE_URL_MAX_LENGTH = 512;
  private static final int CREDENTIAL_MAX_LENGTH = 512;

  private final AgentEditableSupport editableSupport;
  private final AgentProviderConfigurationCodec configurationCodec;

  AgentProviderMutationFactory(
      AgentEditableSupport editableSupport, AgentProviderConfigurationCodec configurationCodec) {
    this.editableSupport = editableSupport;
    this.configurationCodec = configurationCodec;
  }

  AgentProvider newProvider(String name, AgentProviderEditablePropertiesDTO properties) {
    Mutation mutation = newMutation(properties, name, null, null, true);
    AgentProvider provider = new AgentProvider();
    provider.setConnectionGenerationId(UUID.randomUUID());
    apply(provider, mutation);
    return provider;
  }

  void update(AgentProvider provider, AgentProviderEditablePropertiesDTO properties) {
    Mutation mutation =
        newMutation(
            properties,
            provider.getName(),
            provider.getCredential(),
            provider.getConfigJson(),
            false);
    boolean protocolChanged =
        !Objects.equals(provider.getProviderType(), mutation.providerType())
            || !Objects.equals(provider.getBaseUrl(), mutation.baseUrl())
            || !Objects.equals(provider.getCredential(), mutation.credential())
            || !configurationCodec.isProtocolConfigEqual(
                provider.getConfigJson(), mutation.configJson());
    if (provider.getConnectionGenerationId() == null || protocolChanged) {
      provider.setConnectionGenerationId(UUID.randomUUID());
    }
    apply(provider, mutation);
  }

  private void apply(AgentProvider provider, Mutation mutation) {
    provider.setName(mutation.name());
    provider.setDescription(mutation.description());
    provider.setProviderType(mutation.providerType());
    provider.setBaseUrl(mutation.baseUrl());
    provider.setCredential(mutation.credential());
    provider.setConfigJson(mutation.configJson());
  }

  private Mutation newMutation(
      AgentProviderEditablePropertiesDTO properties,
      String fallbackName,
      String existingCredential,
      String existingConfigJson,
      boolean creating) {
    if (properties == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    String name = requireName(fallbackName);
    String providerType = properties.getProviderType();
    if (providerType == null || providerType.isBlank()) {
      throw new AiValidationException(RESOURCE, RESOURCE + " providerType must not be blank");
    }
    String credential = editableSupport.trimToNull(properties.getCredential());
    if (!creating && credential == null) {
      credential = existingCredential;
    }
    String description = editableSupport.trimToNull(properties.getDescription());
    String baseUrl = editableSupport.trimToNull(properties.getBaseUrl());
    editableSupport.validateMaxLength(RESOURCE, "name", name, NAME_MAX_LENGTH);
    editableSupport.validateMaxLength(RESOURCE, "baseUrl", baseUrl, BASE_URL_MAX_LENGTH);
    editableSupport.validateMaxLength(RESOURCE, "credential", credential, CREDENTIAL_MAX_LENGTH);
    try {
      String configJson =
          configurationCodec.mergeTimeoutPolicy(
              existingConfigJson,
              properties.getModelCallTimeoutMillis(),
              properties.getModelCallIdleTimeoutMillis());
      return new Mutation(
          name,
          description,
          ProviderType.fromWireValue(providerType),
          baseUrl,
          credential,
          configJson);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage());
    }
  }

  private String requireName(String name) {
    if (name == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    String canonical = name.strip();
    if (canonical.isEmpty()) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!name.equals(canonical)) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " name must not contain surrounding whitespace");
    }
    if (canonical.indexOf('/') >= 0) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not contain '/'");
    }
    editableSupport.validateMaxLength(RESOURCE, "name", canonical, NAME_MAX_LENGTH);
    return canonical;
  }

  record Mutation(
      String name,
      String description,
      ProviderType providerType,
      String baseUrl,
      String credential,
      String configJson) {}
}
