package fun.fengwk.kkstudio.core.agent.definition.service;

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
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;

import java.util.List;

@SpringBootTest(classes = CoreTestApplication.class)
public class AgentDefinitionServiceTest {

  @Autowired private AgentDefinitionService agentDefinitionService;

  @Autowired private AgentProviderService agentProviderService;

  @Autowired private AgentModelService agentModelService;

  @Test
  public void shouldCreateUpdateAndDeleteAgentDefinitionWithResolvedDefaults() {
    String suffix = String.valueOf(System.nanoTime());
    AgentProviderDTO primaryProvider = createProvider("provider_primary_" + suffix);
    AgentModelDTO primaryModel = createModel(primaryProvider.getName(), "model_primary_" + suffix);
    AgentProviderDTO fallbackProvider = createProvider("provider_fallback_" + suffix);
    AgentModelDTO fallbackModel =
        createModel(fallbackProvider.getName(), "model_fallback_" + suffix);
    Page<AgentDefinitionDTO> baseline = agentDefinitionService.pageAgents(new PageQuery(1, 200));

    AgentDefinitionCreateDTO createDTO = new AgentDefinitionCreateDTO();
    createDTO.setName("agent_" + suffix);
    createDTO.setDescription("Primary agent");
    createDTO.setSystemPrompt("answer carefully");
    createDTO.setDefaultProvider(primaryProvider.getName());
    createDTO.setDefaultModel(primaryModel.getName());
    createDTO.setDefaultVariant("creative");
    createDTO.setToolsJson("[\"browser\"]");

    // 创建链路必须解析 provider/model 名称，并把默认引用落成稳定的实体 id。
    AgentDefinitionDTO created = agentDefinitionService.createAgent(createDTO);
    assertNotNull(created.getId());
    assertEquals(createDTO.getName(), created.getName());
    assertEquals(primaryProvider.getId(), created.getDefaultProviderId());
    assertEquals(primaryProvider.getName(), created.getDefaultProviderName());
    assertEquals(primaryModel.getId(), created.getDefaultModelId());
    assertEquals(primaryModel.getName(), created.getDefaultModelName());
    assertEquals("creative", created.getDefaultVariant());

    AgentDefinitionUpdateDTO updateDTO = new AgentDefinitionUpdateDTO();
    updateDTO.setName("agent_updated_" + suffix);
    updateDTO.setDescription("Fallback agent");
    updateDTO.setSystemPrompt("answer briefly");
    updateDTO.setDefaultProvider(fallbackProvider.getName());
    updateDTO.setDefaultModel(fallbackModel.getName());
    updateDTO.setDefaultVariant("stable");
    updateDTO.setToolsJson("[]");

    // 更新链路必须在切换默认 provider/model 时同步更新引用和对外展示字段。
    AgentDefinitionDTO updated = agentDefinitionService.updateAgent(created.getId(), updateDTO);
    assertEquals(updateDTO.getName(), updated.getName());
    assertEquals(updateDTO.getDescription(), updated.getDescription());
    assertEquals(updateDTO.getSystemPrompt(), updated.getSystemPrompt());
    assertEquals(fallbackProvider.getId(), updated.getDefaultProviderId());
    assertEquals(fallbackProvider.getName(), updated.getDefaultProviderName());
    assertEquals(fallbackModel.getId(), updated.getDefaultModelId());
    assertEquals(fallbackModel.getName(), updated.getDefaultModelName());
    assertEquals(updateDTO.getDefaultVariant(), updated.getDefaultVariant());

    agentDefinitionService.deleteAgent(created.getId());
    Page<AgentDefinitionDTO> afterDelete = agentDefinitionService.pageAgents(new PageQuery(1, 200));
    List<AgentDefinitionDTO> definitions = afterDelete.getResults();
    assertEquals(baseline.getTotalCount(), afterDelete.getTotalCount());
    assertTrue(
        definitions.stream().noneMatch(definition -> created.getId().equals(definition.getId())));
  }

  @Test
  public void shouldRejectMissingResolvedDefaults() {
    AgentDefinitionCreateDTO createDTO = new AgentDefinitionCreateDTO();
    createDTO.setName("agent_missing_" + System.nanoTime());
    createDTO.setDefaultProvider("provider_missing");
    createDTO.setDefaultModel("model_missing");

    // 缺失 provider/model 时必须直接拒绝，避免写入悬空依赖。
    assertThrows(
        IllegalArgumentException.class, () -> agentDefinitionService.createAgent(createDTO));
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
