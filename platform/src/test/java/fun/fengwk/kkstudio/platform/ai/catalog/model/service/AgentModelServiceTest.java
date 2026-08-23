package fun.fengwk.kkstudio.platform.ai.catalog.model.service;

import static fun.fengwk.kkstudio.platform.ai.catalog.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.platform.ai.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.ai.error.AiInUseException;
import fun.fengwk.kkstudio.platform.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.ai.error.AiValidationException;
import fun.fengwk.kkstudio.platform.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;

/** 模型名称在 Provider 内唯一并按复合名称寻址；记录存续期间名称不可变，硬删除后可同名重建。 */
public class AgentModelServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;

  @Test
  public void shouldScopeModelNamesToProvider() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("model-provider-" + suffix);
    AgentProviderDTO otherProvider = provider("model-provider-other-" + suffix);
    String modelName = "MiniMax-M2.7-" + suffix;

    AgentModelDTO model = agentModelService.createModel(model(provider.getName(), modelName));
    assertEquals(provider.getName(), model.getProviderName());
    assertEquals("0", model.getVersion());
    assertThrows(
        AiDuplicateException.class,
        () -> agentModelService.createModel(model(provider.getName(), modelName)));

    AgentModelDTO sameNameOtherProvider =
        agentModelService.createModel(model(otherProvider.getName(), modelName));
    assertEquals(otherProvider.getName(), sameNameOtherProvider.getProviderName());
    assertEquals(modelName, sameNameOtherProvider.getName());

    assertThrows(
        AiResourceNotFoundException.class,
        () -> agentModelService.createModel(model("missing-" + suffix, "missing-" + suffix)));
    assertThrows(
        AiValidationException.class,
        () -> agentModelService.createModel(model(" ", "invalid-" + suffix)));
    assertThrows(
        AiInUseException.class,
        () -> agentProviderService.deleteProvider(provider.getName(), provider.getVersion()));

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setDescription("updated");
    update.setConfig(model.getConfig());
    update.setExpectedVersion(model.getVersion());
    AgentModelDTO updated = agentModelService.updateModel(provider.getName(), modelName, update);
    assertEquals("updated", updated.getDescription());
    assertEquals("1", updated.getVersion());
    assertTrue(
        agentModelService.pageModels(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(
                candidate ->
                    candidate.getProviderName().equals(provider.getName())
                        && candidate.getName().equals(modelName)));

    AgentModelUpdateDTO stale = new AgentModelUpdateDTO();
    stale.setDescription("stale");
    stale.setConfig(model.getConfig());
    stale.setExpectedVersion(model.getVersion());
    assertThrows(
        AiVersionConflictException.class,
        () -> agentModelService.updateModel(provider.getName(), modelName, stale));

    agentModelService.deleteModel(provider.getName(), modelName, updated.getVersion());
    agentModelService.deleteModel(otherProvider.getName(), modelName, "0");
    // 硬删除：同名立即可重建，重建后解析到新行。
    AgentModelDTO recreated = agentModelService.createModel(model(provider.getName(), modelName));
    assertEquals("0", recreated.getVersion());
    agentModelService.deleteModel(provider.getName(), modelName, recreated.getVersion());
    assertThrows(
        AiResourceNotFoundException.class,
        () -> agentModelService.deleteModel(provider.getName(), modelName, "0"));
    agentProviderService.deleteProvider(provider.getName(), "0");
    agentProviderService.deleteProvider(otherProvider.getName(), "0");
  }

  private AgentProviderDTO provider(String name) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    return agentProviderService.createProvider(dto);
  }

  private AgentModelCreateDTO model(String providerName, String name) {
    AgentModelCreateDTO dto = new AgentModelCreateDTO();
    dto.setProviderName(providerName);
    dto.setName(name);
    executable(dto);
    return dto;
  }
}
