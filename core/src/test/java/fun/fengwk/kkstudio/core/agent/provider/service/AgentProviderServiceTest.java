package fun.fengwk.kkstudio.core.agent.provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

import java.util.List;

@SpringBootTest(classes = CoreTestApplication.class)
public class AgentProviderServiceTest {

  @Autowired private AgentProviderService agentProviderService;

  @Autowired private AgentModelService agentModelService;

  @Test
  public void shouldCreateUpdateAndDeleteProvider() {
    String suffix = String.valueOf(System.nanoTime());
    Page<AgentProviderDTO> baseline = agentProviderService.pageProviders(new PageQuery(1, 200));

    AgentProviderCreateDTO createDTO = new AgentProviderCreateDTO();
    createDTO.setName("provider_" + suffix);
    createDTO.setDescription("Primary provider");
    createDTO.setProviderType("openai");
    createDTO.setBaseUrl("https://example.invalid/v1");
    createDTO.setApiKey("test-key");
    createDTO.setTimeoutMillis(30_000L);
    createDTO.setStreamIdleTimeoutMillis(45_000L);

    // 创建链路必须校验 provider 名称唯一，并把对外返回字段标准化。
    AgentProviderDTO created = agentProviderService.createProvider(createDTO);
    assertNotNull(created.getId());
    assertEquals(createDTO.getName(), created.getName());
    assertEquals(createDTO.getDescription(), created.getDescription());
    assertEquals(createDTO.getProviderType(), created.getProviderType());
    assertEquals(createDTO.getBaseUrl(), created.getBaseUrl());
    assertEquals(createDTO.getTimeoutMillis(), created.getTimeoutMillis());

    AgentProviderUpdateDTO updateDTO = new AgentProviderUpdateDTO();
    updateDTO.setName("provider_updated_" + suffix);
    updateDTO.setDescription("Updated provider");
    updateDTO.setProviderType("openai");
    updateDTO.setBaseUrl("https://example.invalid/v2");
    updateDTO.setApiKey("test-key-updated");
    updateDTO.setTimeoutMillis(40_000L);
    updateDTO.setStreamIdleTimeoutMillis(50_000L);

    // 更新链路必须同步刷新配置字段，并维持唯一名称约束。
    AgentProviderDTO updated = agentProviderService.updateProvider(created.getId(), updateDTO);
    assertEquals(updateDTO.getName(), updated.getName());
    assertEquals(updateDTO.getDescription(), updated.getDescription());
    assertEquals(updateDTO.getBaseUrl(), updated.getBaseUrl());
    assertEquals(updateDTO.getApiKey(), updated.getApiKey());
    assertEquals(updateDTO.getTimeoutMillis(), updated.getTimeoutMillis());
    assertEquals(updateDTO.getStreamIdleTimeoutMillis(), updated.getStreamIdleTimeoutMillis());

    agentProviderService.deleteProvider(created.getId());
    Page<AgentProviderDTO> afterDelete = agentProviderService.pageProviders(new PageQuery(1, 200));
    List<AgentProviderDTO> providers = afterDelete.getResults();
    assertEquals(baseline.getTotalCount(), afterDelete.getTotalCount());
    assertTrue(providers.stream().noneMatch(provider -> created.getId().equals(provider.getId())));
  }

  @Test
  public void shouldRejectDeletingProviderInUseByModels() {
    String suffix = String.valueOf(System.nanoTime());
    AgentProviderDTO provider = createProvider("provider_guard_" + suffix);
    AgentModelDTO model = createModel(provider.getName(), "model_guard_" + suffix);

    // 删除链路必须阻止移除仍承载 model 的 provider，避免模型归属悬空。
    assertThrows(
        IllegalStateException.class, () -> agentProviderService.deleteProvider(provider.getId()));

    agentModelService.deleteModel(model.getId());
    agentProviderService.deleteProvider(provider.getId());
  }

  private AgentProviderDTO createProvider(String name) {
    AgentProviderCreateDTO createDTO = new AgentProviderCreateDTO();
    createDTO.setName(name);
    createDTO.setProviderType("openai");
    createDTO.setBaseUrl("https://example.invalid/v1");
    createDTO.setApiKey("test-key");
    return agentProviderService.createProvider(createDTO);
  }

  private AgentModelDTO createModel(String providerName, String name) {
    AgentModelCreateDTO createDTO = new AgentModelCreateDTO();
    createDTO.setProvider(providerName);
    createDTO.setName(name);
    createDTO.setVariantsJson("[{\"name\":\"default\"}]");
    return agentModelService.createModel(createDTO);
  }
}
