package fun.fengwk.kkstudio.platform.catalog.provider.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

import java.time.Duration;

/** Provider 变更校验与凭据补丁行为。 */
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
    assertThrows(AiValidationException.class, () -> factory.newProvider(null, null));

    AgentProviderCreateDTO blank = provider(" ", null);
    assertThrows(AiValidationException.class, () -> factory.newProvider(blank.getName(), blank));

    AgentProviderCreateDTO unsupported = provider("provider", null);
    unsupported.setProviderType("missing");
    assertThrows(
        AiValidationException.class, () -> factory.newProvider(unsupported.getName(), unsupported));

    AgentProviderCreateDTO invalidTimeout = provider("provider", null);
    invalidTimeout.setModelCallIdleTimeoutMillis(0L);
    assertThrows(
        AiValidationException.class,
        () -> factory.newProvider(invalidTimeout.getName(), invalidTimeout));
  }

  @Test
  public void shouldEnforceProviderSchemaStringLimitsAfterNormalization() {
    AgentProviderMutationFactory factory = factory();
    AgentProviderCreateDTO surrounded = provider("\u2003provider\u2003", "c".repeat(512));
    assertThrows(
        AiValidationException.class, () -> factory.newProvider(surrounded.getName(), surrounded));

    AgentProviderCreateDTO accepted = provider("n".repeat(64), "c".repeat(512));
    accepted.setDescription("d".repeat(512));
    accepted.setBaseUrl("u".repeat(512));
    AgentProvider persisted = factory.newProvider(accepted.getName(), accepted);
    assertEquals("n".repeat(64), persisted.getName());

    AgentProviderCreateDTO oversizedName = provider("n".repeat(65), null);
    assertThrows(
        AiValidationException.class,
        () -> factory.newProvider(oversizedName.getName(), oversizedName));
    AgentProviderCreateDTO pathBreakingName = provider("provider/name", null);
    assertThrows(
        AiValidationException.class,
        () -> factory.newProvider(pathBreakingName.getName(), pathBreakingName));
    AgentProviderCreateDTO oversizedCredential = provider("provider", "c".repeat(513));
    assertThrows(
        AiValidationException.class,
        () -> factory.newProvider(oversizedCredential.getName(), oversizedCredential));
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
    provider.setName("provider");
    provider.setProviderType(AgentProviderType.openai);
    provider.setCredential(credential);
    provider.setConfigJson(configJson);
    return provider;
  }

  private AgentProviderMutationFactory factory() {
    ObjectMapper objectMapper = new ObjectMapper();
    return new AgentProviderMutationFactory(
        new AgentEditableSupport(objectMapper), new AgentProviderConfigurationCodec(objectMapper));
  }
}
