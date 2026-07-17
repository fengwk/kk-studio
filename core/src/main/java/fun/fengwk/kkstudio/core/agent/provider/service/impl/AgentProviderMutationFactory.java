package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextProviderId;

import fun.fengwk.kkstudio.share.model.AgentProviderType;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentProviderEditablePropertiesDTO;
import org.springframework.stereotype.Component;

/** Normalizes mutable provider configuration while keeping credentials out of public DTOs. */
@Component
final class AgentProviderMutationFactory {

  private static final String EMPTY_OBJECT_JSON = "{}";

  private final AgentEditableSupport editableSupport;

  AgentProviderMutationFactory(AgentEditableSupport editableSupport) {
    this.editableSupport = editableSupport;
  }

  AgentProvider newProvider(AgentProviderEditablePropertiesDTO properties) {
    Mutation mutation = newMutation(properties, null, null, true);
    AgentProvider provider = new AgentProvider();
    provider.setId(nextProviderId());
    apply(provider, mutation);
    return provider;
  }

  void update(AgentProvider provider, AgentProviderEditablePropertiesDTO properties) {
    Mutation mutation =
        newMutation(properties, provider.getName(), provider.getCredential(), false);
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
      boolean creating) {
    if (properties == null) {
      throw new IllegalArgumentException("agent provider body must not be null");
    }
    String name = editableSupport.firstNonBlank(properties.getName(), fallbackName);
    if (name == null) {
      throw new IllegalArgumentException("agent provider name must not be blank");
    }
    String providerType = editableSupport.trimToNull(properties.getProviderType());
    if (providerType == null) {
      throw new IllegalArgumentException("agent provider providerType must not be blank");
    }
    String credential = editableSupport.trimToNull(properties.getCredential());
    if (!creating && credential == null) {
      credential = existingCredential;
    }
    String configJson =
        editableSupport.firstNonBlank(properties.getConfigJson(), EMPTY_OBJECT_JSON);
    editableSupport.validateJsonObject(configJson, "configJson");
    try {
      return new Mutation(
          name,
          editableSupport.trimToNull(properties.getDescription()),
          AgentProviderType.valueOf(providerType),
          editableSupport.trimToNull(properties.getBaseUrl()),
          credential,
          configJson);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("unsupported providerType: " + providerType, error);
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
