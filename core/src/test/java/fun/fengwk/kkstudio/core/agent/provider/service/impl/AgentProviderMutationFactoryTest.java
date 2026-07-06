package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import org.junit.jupiter.api.Test;

/**
 * AgentProviderMutationFactory 的聚焦行为测试。
 *
 * @author fengwk
 */
public class AgentProviderMutationFactoryTest {

  /** 校验创建 mutation 会统一 trim、补默认超时，并完整组装 provider 实体。 */
  @Test
  public void shouldCreateProviderWithNormalizedFields() {
    AgentProviderMutationFactory factory = newFactory();
    AgentProviderCreateDTO createDTO = new AgentProviderCreateDTO();
    createDTO.setName("  openai-compatible  ");
    createDTO.setDescription("  Provider description  ");
    createDTO.setProviderType(" openai ");
    createDTO.setBaseUrl("  https://example.test  ");
    createDTO.setApiKey("  secret-key  ");

    AgentProviderMutationFactory.Mutation mutation = factory.newCreateMutation(createDTO);
    AgentProvider provider = factory.newProvider(mutation);

    assertEquals("openai-compatible", mutation.name());
    assertEquals("Provider description", mutation.description());
    assertEquals(ProviderType.openai, mutation.providerType());
    assertEquals("https://example.test", mutation.baseUrl());
    assertEquals("secret-key", mutation.apiKey());
    assertEquals(60_000L, mutation.timeout().toMillis());
    assertEquals(60_000L, mutation.streamIdleTimeout().toMillis());
    assertNotNull(provider.getId());
    assertEquals("openai-compatible", provider.getName());
    assertEquals("Provider description", provider.getDescription());
    assertEquals(ProviderType.openai, provider.getProviderType());
  }

  /** 校验更新 mutation 会继承旧名称，并把空白可编辑字段收敛为 null。 */
  @Test
  public void shouldApplyUpdateMutationWithCurrentNameFallback() {
    AgentProviderMutationFactory factory = newFactory();
    AgentProviderUpdateDTO updateDTO = new AgentProviderUpdateDTO();
    updateDTO.setName("   ");
    updateDTO.setDescription("   ");
    updateDTO.setProviderType("openai");
    updateDTO.setBaseUrl(" ");
    updateDTO.setApiKey(" ");
    updateDTO.setTimeoutMillis(30_000L);
    updateDTO.setStreamIdleTimeoutMillis(45_000L);

    AgentProvider provider = new AgentProvider();
    factory.apply(provider, factory.newUpdateMutation("provider-a", updateDTO));

    assertEquals("provider-a", provider.getName());
    assertNull(provider.getDescription());
    assertNull(provider.getBaseUrl());
    assertNull(provider.getApiKey());
    assertEquals(30_000L, provider.getTimeout().toMillis());
    assertEquals(45_000L, provider.getStreamIdleTimeout().toMillis());
  }

  /** 校验非法入参会被立即拒绝，避免把坏数据推进到 provider 持久化层。 */
  @Test
  public void shouldRejectInvalidArguments() {
    AgentProviderMutationFactory factory = newFactory();
    AgentProviderCreateDTO createDTO = new AgentProviderCreateDTO();
    createDTO.setName("provider-a");
    createDTO.setProviderType("openai");

    assertThrows(IllegalArgumentException.class, () -> new AgentEditableSupport(null));
    assertThrows(IllegalArgumentException.class, () -> new AgentProviderMutationFactory(null));
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(null));

    createDTO.setName(" ");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setName("provider-a");
    createDTO.setProviderType(" ");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setProviderType("unknown");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setProviderType("openai");
    createDTO.setTimeoutMillis(0L);
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    AgentProviderUpdateDTO updateDTO = new AgentProviderUpdateDTO();
    updateDTO.setProviderType("openai");
    assertThrows(IllegalArgumentException.class, () -> factory.newUpdateMutation(" ", updateDTO));
    assertThrows(IllegalArgumentException.class, () -> factory.newProvider(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.apply(null, factory.newCreateMutation(baseCreate())));
    assertThrows(IllegalArgumentException.class, () -> factory.apply(new AgentProvider(), null));
  }

  private static AgentProviderCreateDTO baseCreate() {
    AgentProviderCreateDTO createDTO = new AgentProviderCreateDTO();
    createDTO.setName("provider-a");
    createDTO.setProviderType("openai");
    return createDTO;
  }

  private static AgentProviderMutationFactory newFactory() {
    return new AgentProviderMutationFactory(new AgentEditableSupport(new ObjectMapper()));
  }
}
