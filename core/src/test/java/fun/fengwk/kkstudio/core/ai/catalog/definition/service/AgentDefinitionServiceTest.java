package fun.fengwk.kkstudio.core.ai.catalog.definition.service;

import static fun.fengwk.kkstudio.core.ai.catalog.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;

import java.util.List;

/** Agent 定义全局唯一并引用当前模型名称；记录存续期间名称不可变，硬删除后可同名重建。 */
public class AgentDefinitionServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;
  @Autowired private AgentDefinitionService agentDefinitionService;

  @Test
  public void shouldPersistGlobalStructuredDefinition() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("agent-provider-" + suffix);
    AgentModelDTO model = model(provider.getName(), "agent-model-" + suffix);
    String name = "agent-definition-" + suffix;
    String modelRef = provider.getName() + "/" + model.getName();

    AgentDefinitionDTO definition = agentDefinitionService.createAgent(agent(modelRef, name));
    assertEquals(modelRef, definition.getModel());
    assertEquals(List.of(), definition.getConfig().getTools());
    assertEquals(List.of(), definition.getConfig().getSkills());
    assertEquals(List.of(), definition.getConfig().getSubagents());
    assertEquals("0", definition.getVersion());
    assertThrows(
        AiDuplicateException.class,
        () -> agentDefinitionService.createAgent(agent(modelRef, name)));
    assertThrows(
        AiInUseException.class,
        () -> agentModelService.deleteModel(provider.getName(), model.getName(), "0"));

    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setDescription("updated");
    update.setSystemPrompt(definition.getSystemPrompt());
    update.setModel(definition.getModel());
    update.setVariant(definition.getVariant());
    update.setConfig(definition.getConfig());
    update.setExpectedVersion(definition.getVersion());
    AgentDefinitionDTO updated = agentDefinitionService.updateAgent(name, update);
    assertEquals(name, updated.getName());
    assertEquals(modelRef, updated.getModel());
    assertEquals("updated", updated.getDescription());
    assertEquals("1", updated.getVersion());
    assertTrue(
        agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getName().equals(name)));

    AgentDefinitionUpdateDTO stale = new AgentDefinitionUpdateDTO();
    stale.setDescription("stale");
    stale.setModel(definition.getModel());
    stale.setConfig(definition.getConfig());
    stale.setExpectedVersion(definition.getVersion());
    assertThrows(
        AiVersionConflictException.class, () -> agentDefinitionService.updateAgent(name, stale));

    agentDefinitionService.deleteAgent(name, updated.getVersion());
    // 硬删除：同名立即可重建。
    AgentDefinitionDTO recreated = agentDefinitionService.createAgent(agent(modelRef, name));
    assertEquals("0", recreated.getVersion());
    agentDefinitionService.deleteAgent(name, recreated.getVersion());
    assertThrows(
        AiResourceNotFoundException.class, () -> agentDefinitionService.deleteAgent(name, "0"));
    agentModelService.deleteModel(provider.getName(), model.getName(), model.getVersion());
    agentProviderService.deleteProvider(provider.getName(), provider.getVersion());
  }

  @Test
  void subagentAllowlistRequiresLiveReferencesAndPreventsDeletion() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("subagent-provider-" + suffix);
    AgentModelDTO model = model(provider.getName(), "subagent-model-" + suffix);
    String modelRef = provider.getName() + "/" + model.getName();
    AgentDefinitionDTO child =
        agentDefinitionService.createAgent(agent(modelRef, "subagent-child-" + suffix));
    AgentDefinitionCreateDTO parentCreate = agent(modelRef, "subagent-parent-" + suffix);
    parentCreate.getConfig().setSubagents(List.of(child.getName()));
    AgentDefinitionDTO parent = agentDefinitionService.createAgent(parentCreate);
    try {
      assertEquals(List.of(child.getName()), parent.getConfig().getSubagents());
      assertThrows(
          AiInUseException.class,
          () -> agentDefinitionService.deleteAgent(child.getName(), child.getVersion()));

      AgentDefinitionCreateDTO missing = agent(modelRef, "subagent-missing-" + suffix);
      missing.getConfig().setSubagents(List.of("missing-" + suffix));
      assertThrows(
          AiResourceNotFoundException.class, () -> agentDefinitionService.createAgent(missing));

      AgentDefinitionUpdateDTO removeReference = new AgentDefinitionUpdateDTO();
      removeReference.setDescription(parent.getDescription());
      removeReference.setSystemPrompt(parent.getSystemPrompt());
      removeReference.setModel(parent.getModel());
      removeReference.setVariant(parent.getVariant());
      removeReference.setConfig(parent.getConfig());
      removeReference.getConfig().setSubagents(List.of());
      removeReference.setExpectedVersion(parent.getVersion());
      parent = agentDefinitionService.updateAgent(parent.getName(), removeReference);

      agentDefinitionService.deleteAgent(child.getName(), child.getVersion());
      agentDefinitionService.deleteAgent(parent.getName(), parent.getVersion());
    } finally {
      if (agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
          .anyMatch(candidate -> candidate.getName().equals(parentCreate.getName()))) {
        AgentDefinitionDTO current =
            agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
                .filter(candidate -> candidate.getName().equals(parentCreate.getName()))
                .findFirst()
                .orElseThrow();
        agentDefinitionService.deleteAgent(current.getName(), current.getVersion());
      }
      agentModelService.deleteModel(provider.getName(), model.getName(), model.getVersion());
      agentProviderService.deleteProvider(provider.getName(), provider.getVersion());
    }
  }

  @Test
  public void rejectsVariantOutsideTheSelectedModelConfiguration() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("agent-variant-provider-" + suffix);
    AgentModelDTO model = model(provider.getName(), "agent-variant-model-" + suffix);
    String modelRef = provider.getName() + "/" + model.getName();
    AgentDefinitionCreateDTO invalid = agent(modelRef, "agent-invalid-variant-" + suffix);
    invalid.setVariant("missing");

    assertThrows(AiValidationException.class, () -> agentDefinitionService.createAgent(invalid));

    AgentDefinitionCreateDTO valid = agent(modelRef, "agent-valid-variant-" + suffix);
    valid.setVariant(null);
    AgentDefinitionDTO created = agentDefinitionService.createAgent(valid);
    try {
      assertNull(created.getVariant());
      AgentDefinitionUpdateDTO invalidUpdate = new AgentDefinitionUpdateDTO();
      invalidUpdate.setDescription(created.getDescription());
      invalidUpdate.setModel(created.getModel());
      invalidUpdate.setVariant("missing");
      invalidUpdate.setConfig(created.getConfig());
      invalidUpdate.setExpectedVersion(created.getVersion());
      assertThrows(
          AiValidationException.class,
          () -> agentDefinitionService.updateAgent(created.getName(), invalidUpdate));
    } finally {
      agentDefinitionService.deleteAgent(created.getName(), created.getVersion());
      agentModelService.deleteModel(provider.getName(), model.getName(), model.getVersion());
      agentProviderService.deleteProvider(provider.getName(), provider.getVersion());
    }
  }

  @Test
  public void rebindsDefaultModelOnUpdateAndRejectsMissingTarget() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("agent-rebind-provider-" + suffix);
    AgentModelDTO original = model(provider.getName(), "agent-rebind-original-" + suffix);
    AgentModelDTO next = model(provider.getName(), "agent-rebind-next-" + suffix);
    String originalRef = provider.getName() + "/" + original.getName();
    String nextRef = provider.getName() + "/" + next.getName();
    AgentDefinitionDTO definition =
        agentDefinitionService.createAgent(agent(originalRef, "agent-rebind-" + suffix));
    try {
      AgentDefinitionUpdateDTO rebind = new AgentDefinitionUpdateDTO();
      rebind.setDescription(definition.getDescription());
      rebind.setSystemPrompt(definition.getSystemPrompt());
      rebind.setModel(nextRef);
      rebind.setVariant(null);
      rebind.setConfig(definition.getConfig());
      rebind.setExpectedVersion(definition.getVersion());
      AgentDefinitionDTO updated = agentDefinitionService.updateAgent(definition.getName(), rebind);
      assertEquals(nextRef, updated.getModel());
      assertNull(updated.getVariant());

      AgentDefinitionUpdateDTO missing = new AgentDefinitionUpdateDTO();
      missing.setDescription(updated.getDescription());
      missing.setModel(provider.getName() + "/missing-" + suffix);
      missing.setConfig(updated.getConfig());
      missing.setExpectedVersion(updated.getVersion());
      assertThrows(
          AiResourceNotFoundException.class,
          () -> agentDefinitionService.updateAgent(updated.getName(), missing));
      definition = updated;
    } finally {
      agentDefinitionService.deleteAgent(definition.getName(), definition.getVersion());
      agentModelService.deleteModel(provider.getName(), next.getName(), next.getVersion());
      agentModelService.deleteModel(provider.getName(), original.getName(), original.getVersion());
      agentProviderService.deleteProvider(provider.getName(), provider.getVersion());
    }
  }

  private AgentProviderDTO provider(String name) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    return agentProviderService.createProvider(dto);
  }

  private AgentModelDTO model(String providerName, String name) {
    AgentModelCreateDTO dto = new AgentModelCreateDTO();
    dto.setProviderName(providerName);
    dto.setName(name);
    executable(dto);
    return agentModelService.createModel(dto);
  }

  private AgentDefinitionCreateDTO agent(String model, String name) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    AgentDefinitionCreateDTO dto = new AgentDefinitionCreateDTO();
    dto.setName(name);
    dto.setModel(model);
    dto.setVariant("default");
    dto.setConfig(config);
    return dto;
  }
}
