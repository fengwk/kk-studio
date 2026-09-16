package fun.fengwk.kkstudio.platform.catalog.provider.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

import java.time.Duration;
import java.util.UUID;

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

    // Provider wire 值不接受大小写变化或首尾空白，避免本地规范化掩盖非法 catalog 数据。
    unsupported.setProviderType("OPENAI");
    assertThrows(
        AiValidationException.class, () -> factory.newProvider(unsupported.getName(), unsupported));
    unsupported.setProviderType("openai ");
    assertThrows(
        AiValidationException.class, () -> factory.newProvider(unsupported.getName(), unsupported));

    AgentProviderCreateDTO invalidTimeout = provider("provider", null);
    invalidTimeout.setModelCallIdleTimeoutMillis(0L);
    assertThrows(
        AiValidationException.class,
        () -> factory.newProvider(invalidTimeout.getName(), invalidTimeout));
  }

  @Test
  public void shouldNotRetainMalformedExistingConfigurationCause() {
    // 测试意图：更新路径读取损坏的持久配置时，不把可能携带原始配置的解析器 cause 暴露给
    // Web 异常边界。
    AgentProvider provider =
        existingProvider("initial-secret", "{\"credential\":\"sensitive-value\"");
    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");

    AiValidationException error =
        assertThrows(AiValidationException.class, () -> factory().update(provider, update));

    assertFalse(error.getMessage().contains("sensitive-value"));
    assertNull(error.getCause());
  }

  // 测试意图: 断言 name/baseUrl/credential 仍受 schema 列宽约束，而 description 已放开为 text
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

    AgentProviderCreateDTO unbounded = provider("provider", "c".repeat(512));
    unbounded.setDescription("d".repeat(4096));
    assertEquals("d".repeat(4096), factory.newProvider("provider", unbounded).getDescription());

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
    AgentProviderCreateDTO oversizedBaseUrl = provider("provider", "c".repeat(512));
    oversizedBaseUrl.setBaseUrl("u".repeat(513));
    assertThrows(
        AiValidationException.class,
        () -> factory.newProvider(oversizedBaseUrl.getName(), oversizedBaseUrl));
  }

  @Test
  public void shouldAssignNewConnectionGenerationIdOnCreate() {
    AgentProviderMutationFactory factory = factory();
    AgentProviderCreateDTO dto = provider("new-provider", "key-1");
    AgentProvider created = factory.newProvider(dto.getName(), dto);
    assertNotNull(created.getConnectionGenerationId());
  }

  @Test
  public void shouldRotateConnectionGenerationIdOnCredentialOrBaseUrlOrTypeChange() {
    AgentProviderMutationFactory factory = factory();
    UUID originalGen = UUID.randomUUID();
    AgentProvider provider = existingProvider("initial-secret", "{}");
    provider.setConnectionGenerationId(originalGen);
    provider.setBaseUrl("http://localhost:8080");

    // 凭据变更触发轮换
    AgentProviderUpdateDTO credChange = new AgentProviderUpdateDTO();
    credChange.setProviderType("openai");
    credChange.setBaseUrl("http://localhost:8080");
    credChange.setCredential("new-secret");
    factory.update(provider, credChange);
    assertNotEquals(originalGen, provider.getConnectionGenerationId());

    // baseUrl 变更触发轮换
    UUID afterCredChange = provider.getConnectionGenerationId();
    AgentProviderUpdateDTO urlChange = new AgentProviderUpdateDTO();
    urlChange.setProviderType("openai");
    urlChange.setBaseUrl("http://localhost:9090");
    factory.update(provider, urlChange);
    assertNotEquals(afterCredChange, provider.getConnectionGenerationId());

    // providerType 变更触发轮换
    UUID afterUrlChange = provider.getConnectionGenerationId();
    AgentProviderUpdateDTO typeChange = new AgentProviderUpdateDTO();
    typeChange.setProviderType("openai_response");
    typeChange.setBaseUrl("http://localhost:9090");
    factory.update(provider, typeChange);
    assertNotEquals(afterUrlChange, provider.getConnectionGenerationId());
  }

  @Test
  public void shouldNotRotateConnectionGenerationIdOnDescriptionOrTimeoutOnlyChange() {
    AgentProviderMutationFactory factory = factory();
    UUID originalGen = UUID.randomUUID();
    AgentProvider provider = existingProvider("initial-secret", "{}");
    provider.setConnectionGenerationId(originalGen);
    provider.setBaseUrl("http://localhost:8080");

    // 仅变更 description
    AgentProviderUpdateDTO descChange = new AgentProviderUpdateDTO();
    descChange.setProviderType("openai");
    descChange.setBaseUrl("http://localhost:8080");
    descChange.setDescription("new-description");
    factory.update(provider, descChange);
    assertEquals(originalGen, provider.getConnectionGenerationId());

    // 仅变更 timeout
    AgentProviderUpdateDTO timeoutChange = new AgentProviderUpdateDTO();
    timeoutChange.setProviderType("openai");
    timeoutChange.setBaseUrl("http://localhost:8080");
    timeoutChange.setModelCallTimeoutMillis(60000L);
    timeoutChange.setModelCallIdleTimeoutMillis(10000L);
    factory.update(provider, timeoutChange);
    assertEquals(originalGen, provider.getConnectionGenerationId());
  }

  @Test
  public void shouldAssignConnectionGenerationIdIfPreviouslyNull() {
    AgentProviderMutationFactory factory = factory();
    AgentProvider provider = existingProvider("initial-secret", "{}");
    provider.setConnectionGenerationId(null);

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai");
    factory.update(provider, update);
    assertNotNull(provider.getConnectionGenerationId());
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
    provider.setProviderType(ProviderType.OPENAI);
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
