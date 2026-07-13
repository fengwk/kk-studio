package fun.fengwk.kkstudio.core.agent.model.service;

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
import fun.fengwk.kkstudio.core.agent.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;

import java.util.List;

@SpringBootTest(classes = CoreTestApplication.class)
public class AgentModelServiceTest {

  @Autowired private AgentModelService agentModelService;

  @Autowired private AgentProviderService agentProviderService;

  @Autowired private AgentDefinitionService agentDefinitionService;

  @Test
  public void shouldCreateUpdateAndDeleteModelWithResolvedProvider() {
    String suffix = String.valueOf(System.nanoTime());
    AgentProviderDTO provider = createProvider("provider_model_" + suffix);
    Page<AgentModelDTO> baseline = agentModelService.pageModels(new PageQuery(1, 200));

    AgentModelCreateDTO createDTO = new AgentModelCreateDTO();
    createDTO.setProvider(provider.getName());
    createDTO.setName("model_" + suffix);
    createDTO.setDescription("Primary model");
    createDTO.setDefaultVariant("fast");
    createDTO.setVariantsJson("[{\"name\":\"fast\"},{\"name\":\"accurate\"}]");

    // 创建链路必须解析 provider 名称，并把 provider 引用稳定落成 id。
    AgentModelDTO created = agentModelService.createModel(createDTO);
    assertNotNull(created.getId());
    assertEquals(provider.getId(), created.getProviderId());
    assertEquals(provider.getName(), created.getProviderName());
    assertEquals(createDTO.getName(), created.getName());
    assertEquals(createDTO.getDefaultVariant(), created.getDefaultVariant());

    AgentModelUpdateDTO updateDTO = new AgentModelUpdateDTO();
    updateDTO.setName("model_updated_" + suffix);
    updateDTO.setDescription("Updated model");
    updateDTO.setDefaultVariant("accurate");
    updateDTO.setVariantsJson("[{\"name\":\"accurate\"}]");

    // 更新链路必须保持 provider 归属不变，同时刷新对外返回字段。
    AgentModelDTO updated = agentModelService.updateModel(created.getId(), updateDTO);
    assertEquals(provider.getId(), updated.getProviderId());
    assertEquals(provider.getName(), updated.getProviderName());
    assertEquals(updateDTO.getName(), updated.getName());
    assertEquals(updateDTO.getDescription(), updated.getDescription());
    assertEquals(updateDTO.getVariantsJson(), updated.getVariantsJson());

    agentModelService.deleteModel(created.getId());
    Page<AgentModelDTO> afterDelete = agentModelService.pageModels(new PageQuery(1, 200));
    List<AgentModelDTO> models = afterDelete.getResults();
    assertEquals(baseline.getTotalCount(), afterDelete.getTotalCount());
    assertTrue(models.stream().noneMatch(model -> created.getId().equals(model.getId())));

    agentProviderService.deleteProvider(provider.getId());
  }

  @Test
  public void shouldRejectMissingResolvedProvider() {
    AgentModelCreateDTO createDTO = new AgentModelCreateDTO();
    createDTO.setProvider("provider_missing");
    createDTO.setName("model_missing_" + System.nanoTime());

    // 缺失 provider 时必须直接拒绝，避免写入悬空 provider 引用。
    assertThrows(IllegalArgumentException.class, () -> agentModelService.createModel(createDTO));
  }

  @Test
  public void shouldRejectDeletingModelInUseByAgents() {
    String suffix = String.valueOf(System.nanoTime());
    AgentProviderDTO provider = createProvider("provider_guard_" + suffix);
    AgentModelDTO model = createModel(provider.getName(), "model_guard_" + suffix);
    AgentDefinitionDTO agent =
        createAgent(provider.getName(), model.getName(), "agent_guard_" + suffix);

    // 删除链路必须阻止移除仍被 agent 引用的 model，避免默认模型悬空。
    assertThrows(IllegalStateException.class, () -> agentModelService.deleteModel(model.getId()));

    agentDefinitionService.deleteAgent(agent.getId());
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

  private AgentDefinitionDTO createAgent(String providerName, String modelName, String name) {
    AgentDefinitionCreateDTO createDTO = new AgentDefinitionCreateDTO();
    createDTO.setName(name);
    createDTO.setDefaultProvider(providerName);
    createDTO.setDefaultModel(modelName);
    return agentDefinitionService.createAgent(createDTO);
  }
}
