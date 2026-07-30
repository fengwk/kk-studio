package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import fun.fengwk.kkstudio.core.agent.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderType;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

import java.time.Duration;

/** Provider mutation validation and credential patch behavior. */
public class AgentProviderMutationFactoryTest {

  @Test
  public void shouldRetainCredentialWhenUpdateDoesNotProvideOne() {
    AgentProviderMutationFactory factory = factory();
    AgentProvider provider = existingProvider("initial-secret", "{}");

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setCredential(" ");
    factory.update(provider, update);

    assertEquals("initial-secret", provider.getCredential());
    assertEquals(
        "{\"modelCallTimeoutMillis\":1800000,\"modelCallIdleTimeoutMillis\":120000}",
        provider.getConfigJson());
  }

  @Test
  public void shouldPersistConfiguredTimeoutsAndRetainExistingValuesOnPartialUpdate() {
    AgentProviderMutationFactory factory = factory();
    AgentProvider persisted = existingProvider(null, "{}");

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    update.setModelCallTimeoutMillis(Duration.ofMinutes(2).toMillis());
    update.setModelCallIdleTimeoutMillis(Duration.ofSeconds(3).toMillis());
    factory.update(persisted, update);

    AgentProviderUpdateDTO partialUpdate = new AgentProviderUpdateDTO();
    partialUpdate.setProviderType("openai");
    partialUpdate.setModelCallIdleTimeoutMillis(Duration.ofSeconds(5).toMillis());
    factory.update(persisted, partialUpdate);

    assertEquals(
        "{\"modelCallTimeoutMillis\":120000,\"modelCallIdleTimeoutMillis\":5000}",
        persisted.getConfigJson());
  }

  @Test
  public void shouldRejectInvalidProviderConfiguration() {
    AgentProviderMutationFactory factory = factory();
    assertThrows(AiValidationException.class, () -> factory.newProvider(null));

    AgentProviderCreateDTO blank = provider(" ", null);
    assertThrows(AiValidationException.class, () -> factory.newProvider(blank));

    AgentProviderCreateDTO unsupported = provider("provider", null);
    unsupported.setProviderType("missing");
    assertThrows(AiValidationException.class, () -> factory.newProvider(unsupported));

    AgentProviderCreateDTO invalidTimeout = provider("provider", null);
    invalidTimeout.setModelCallIdleTimeoutMillis(0L);
    assertThrows(AiValidationException.class, () -> factory.newProvider(invalidTimeout));
  }

  @Test
  public void shouldEnforceProviderSchemaStringLimitsAfterNormalization() {
    AgentProviderMutationFactory factory = factory();
    AgentProviderCreateDTO accepted = provider(" " + "n".repeat(64) + " ", "c".repeat(512));
    accepted.setDescription("d".repeat(512));
    accepted.setBaseUrl("u".repeat(512));
    AgentProvider persisted = factory.newProvider(accepted);
    assertEquals("n".repeat(64), persisted.getName());
    assertEquals("d".repeat(512), persisted.getDescription());
    assertEquals("u".repeat(512), persisted.getBaseUrl());
    assertEquals("c".repeat(512), persisted.getCredential());

    AgentProviderCreateDTO oversizedName = provider("n".repeat(65), null);
    assertThrows(AiValidationException.class, () -> factory.newProvider(oversizedName));
    AgentProviderCreateDTO oversizedDescription = provider("provider", null);
    oversizedDescription.setDescription("d".repeat(513));
    assertThrows(AiValidationException.class, () -> factory.newProvider(oversizedDescription));
    AgentProviderCreateDTO oversizedBaseUrl = provider("provider", null);
    oversizedBaseUrl.setBaseUrl("u".repeat(513));
    assertThrows(AiValidationException.class, () -> factory.newProvider(oversizedBaseUrl));
    AgentProviderCreateDTO oversizedCredential = provider("provider", "c".repeat(513));
    assertThrows(AiValidationException.class, () -> factory.newProvider(oversizedCredential));
  }

  private AgentProviderCreateDTO provider(String name, String credential) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    dto.setCredential(credential);
    return dto;
  }

  private AgentProvider existingProvider(String credential, String configJson) {
    AgentProvider provider = new AgentProvider();
    provider.setId(1L);
    provider.setName("provider");
    provider.setProviderType(AgentProviderType.openai);
    provider.setCredential(credential);
    provider.setConfigJson(configJson);
    return provider;
  }

  private AgentProviderMutationFactory factory() {
    ObjectMapper objectMapper = new ObjectMapper();
    PostgresqlSequenceIdGenerator idGenerator = Mockito.mock(PostgresqlSequenceIdGenerator.class);
    when(idGenerator.next()).thenReturn(101L);
    return new AgentProviderMutationFactory(
        new AgentEditableSupport(objectMapper),
        new AgentProviderConfigurationCodec(objectMapper),
        idGenerator);
  }
}
