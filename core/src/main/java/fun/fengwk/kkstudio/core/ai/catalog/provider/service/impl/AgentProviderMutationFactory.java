package fun.fengwk.kkstudio.core.ai.catalog.provider.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

/** Normalizes mutable provider configuration while keeping credentials out of public DTOs. */
@Component
final class AgentProviderMutationFactory {

  private static final String RESOURCE = "agent_provider";
  private static final int NAME_MAX_LENGTH = 64;
  private static final int DESCRIPTION_MAX_LENGTH = 512;
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
    String providerType = editableSupport.trimToNull(properties.getProviderType());
    if (providerType == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " providerType must not be blank");
    }
    String credential = editableSupport.trimToNull(properties.getCredential());
    if (!creating && credential == null) {
      credential = existingCredential;
    }
    String description = editableSupport.trimToNull(properties.getDescription());
    String baseUrl = editableSupport.trimToNull(properties.getBaseUrl());
    editableSupport.validateMaxLength(RESOURCE, "name", name, NAME_MAX_LENGTH);
    editableSupport.validateMaxLength(RESOURCE, "description", description, DESCRIPTION_MAX_LENGTH);
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
          AgentProviderType.valueOf(providerType),
          baseUrl,
          credential,
          configJson);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }

  private String requireName(String name) {
    String normalized = editableSupport.trimToNull(name);
    if (normalized == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    editableSupport.validateMaxLength(RESOURCE, "name", normalized, NAME_MAX_LENGTH);
    return normalized;
  }

  record Mutation(
      String name,
      String description,
      AgentProviderType providerType,
      String baseUrl,
      String credential,
      String configJson) {}
}
