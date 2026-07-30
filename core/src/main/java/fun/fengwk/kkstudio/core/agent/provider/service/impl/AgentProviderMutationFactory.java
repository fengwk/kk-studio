package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.share.model.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderType;

/** Normalizes mutable provider configuration while keeping credentials out of public DTOs. */
@Component
final class AgentProviderMutationFactory {

  private static final String RESOURCE = "agent_provider";

  private final AgentEditableSupport editableSupport;
  private final AgentProviderConfigurationCodec configurationCodec;
  private final PostgresqlSequenceIdGenerator idGenerator;

  AgentProviderMutationFactory(
      AgentEditableSupport editableSupport,
      AgentProviderConfigurationCodec configurationCodec,
      PostgresqlSequenceIdGenerator idGenerator) {
    this.editableSupport = editableSupport;
    this.configurationCodec = configurationCodec;
    this.idGenerator = idGenerator;
  }

  AgentProvider newProvider(AgentProviderEditablePropertiesDTO properties) {
    Mutation mutation = newMutation(properties, null, null, null, true);
    AgentProvider provider = new AgentProvider();
    provider.setId(idGenerator.next());
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
    String name = editableSupport.firstNonBlank(properties.getName(), fallbackName);
    if (name == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    String providerType = editableSupport.trimToNull(properties.getProviderType());
    if (providerType == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " providerType must not be blank");
    }
    String credential = editableSupport.trimToNull(properties.getCredential());
    if (!creating && credential == null) {
      credential = existingCredential;
    }
    try {
      String configJson =
          configurationCodec.mergeTimeoutPolicy(
              existingConfigJson,
              properties.getModelCallTimeoutMillis(),
              properties.getModelCallIdleTimeoutMillis());
      return new Mutation(
          name,
          editableSupport.trimToNull(properties.getDescription()),
          AgentProviderType.valueOf(providerType),
          editableSupport.trimToNull(properties.getBaseUrl()),
          credential,
          configJson);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }

  record Mutation(
      String name,
      String description,
      AgentProviderType providerType,
      String baseUrl,
      String credential,
      String configJson) {}
}
