package fun.fengwk.kkstudio.core.agent.model.service;

import static fun.fengwk.kkstudio.core.agent.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;

/** Model and provider references are global. */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentModelServiceTest {

  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;

  @Test
  public void shouldUseGlobalProviderAndModelNames() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("model-provider-" + suffix);
    String modelName = "model-" + suffix;

    AgentModelDTO model = agentModelService.createModel(model(provider.getId(), modelName));
    assertEquals(provider.getId(), model.getProviderId());
    assertThrows(
        IllegalArgumentException.class,
        () -> agentModelService.createModel(model(provider.getId(), modelName)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            agentModelService.createModel(
                model(Long.toString(Long.MAX_VALUE), "missing-" + suffix)));
    assertThrows(
        IllegalArgumentException.class,
        () -> agentModelService.createModel(model("0", "invalid-" + suffix)));
    assertThrows(
        IllegalStateException.class,
        () -> agentProviderService.deleteProvider(id(provider.getId())));

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setName(model.getName());
    update.setDescription("updated");
    update.setConfig(model.getConfig());
    AgentModelDTO updated = agentModelService.updateModel(id(model.getId()), update);
    assertEquals("updated", updated.getDescription());
    assertTrue(
        agentModelService.pageModels(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getId().equals(model.getId())));

    agentModelService.deleteModel(id(model.getId()));
    assertThrows(
        IllegalArgumentException.class, () -> agentModelService.deleteModel(id(model.getId())));
    agentProviderService.deleteProvider(id(provider.getId()));
  }

  private AgentProviderDTO provider(String name) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    return agentProviderService.createProvider(dto);
  }

  private AgentModelCreateDTO model(String providerId, String name) {
    AgentModelCreateDTO dto = new AgentModelCreateDTO();
    dto.setProviderId(providerId);
    dto.setName(name);
    executable(dto);
    return dto;
  }

  private long id(String value) {
    return Long.parseLong(value);
  }
}
