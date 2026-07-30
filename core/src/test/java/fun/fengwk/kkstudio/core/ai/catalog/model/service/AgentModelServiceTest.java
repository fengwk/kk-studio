package fun.fengwk.kkstudio.core.ai.catalog.model.service;

import static fun.fengwk.kkstudio.core.ai.catalog.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;

/** Model names are unique per provider; providers remain globally unique by name. */
public class AgentModelServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;

  @Test
  public void shouldScopeModelNamesToProvider() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("model-provider-" + suffix);
    AgentProviderDTO otherProvider = provider("model-provider-other-" + suffix);
    String modelName = "MiniMax-M2.7-" + suffix;

    AgentModelDTO model = agentModelService.createModel(model(provider.getId(), modelName));
    assertEquals(provider.getId(), model.getProviderId());
    assertEquals("0", model.getVersion());
    // same provider + same name is rejected
    assertThrows(
        AiDuplicateException.class,
        () -> agentModelService.createModel(model(provider.getId(), modelName)));
    // different providers may reuse the same model name
    AgentModelDTO sameNameOtherProvider =
        agentModelService.createModel(model(otherProvider.getId(), modelName));
    assertEquals(otherProvider.getId(), sameNameOtherProvider.getProviderId());
    assertEquals(modelName, sameNameOtherProvider.getName());

    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            agentModelService.createModel(
                model(Long.toString(Long.MAX_VALUE), "missing-" + suffix)));
    assertThrows(
        AiValidationException.class,
        () -> agentModelService.createModel(model("0", "invalid-" + suffix)));
    assertThrows(
        AiInUseException.class,
        () -> agentProviderService.deleteProvider(id(provider.getId()), provider.getVersion()));

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setName(model.getName());
    update.setDescription("updated");
    update.setConfig(model.getConfig());
    update.setExpectedVersion(model.getVersion());
    AgentModelDTO updated = agentModelService.updateModel(id(model.getId()), update);
    assertEquals("updated", updated.getDescription());
    assertEquals("1", updated.getVersion());
    assertTrue(
        agentModelService.pageModels(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getId().equals(model.getId())));

    // stale version conflict
    AgentModelUpdateDTO stale = new AgentModelUpdateDTO();
    stale.setName(model.getName());
    stale.setDescription("stale");
    stale.setConfig(model.getConfig());
    stale.setExpectedVersion(model.getVersion());
    assertThrows(
        AiVersionConflictException.class,
        () -> agentModelService.updateModel(id(model.getId()), stale));

    agentModelService.deleteModel(id(model.getId()), updated.getVersion());
    agentModelService.deleteModel(id(sameNameOtherProvider.getId()), "0");
    assertThrows(
        AiResourceNotFoundException.class,
        () -> agentModelService.deleteModel(id(model.getId()), "0"));
    agentProviderService.deleteProvider(id(provider.getId()), "0");
    agentProviderService.deleteProvider(id(otherProvider.getId()), "0");
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
