package fun.fengwk.kkstudio.platform.catalog.definition;

import static fun.fengwk.kkstudio.platform.catalog.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderService;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessExecutionSelectionDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Agent 定义全局唯一并引用当前模型名称；记录存续期间名称不可变，硬删除后可同名重建。 */
public class AgentServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;
  @Autowired private AgentService agentService;

  @Test
  public void shouldPersistGlobalStructuredDefinition() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("agent-provider-" + suffix);
    AgentModelDTO model = model(provider.getName(), "vendor/agent model-" + suffix);
    String name = "agent-definition-" + suffix;
    String modelRef = model.getName();
    assertEquals("vendor/agent model-" + suffix, modelRef);
    assertEquals(AgentModelKind.MODEL, model.getKind());

    AgentDTO definition = agentService.createAgent(agent(modelRef, name));
    assertEquals(execution(modelRef, "default"), definition.getExecution());
    assertEquals(List.of(), definition.getConfig().getTools());
    assertEquals(List.of(), definition.getConfig().getSkills());
    assertEquals(List.of(), definition.getConfig().getSubagents());
    assertEquals("0", definition.getVersion());
    assertThrows(DomainException.class, () -> agentService.createAgent(agent(modelRef, name)));
    assertThrows(DomainException.class, () -> agentModelService.deleteModel(model.getName(), "0"));

    AgentUpdateDTO update = new AgentUpdateDTO();
    update.setDescription("updated");
    update.setSystemPrompt(definition.getSystemPrompt());
    update.setExecution(definition.getExecution());
    update.setConfig(definition.getConfig());
    update.setExpectedVersion(definition.getVersion());
    AgentDTO updated = agentService.updateAgent(name, update);
    assertEquals(name, updated.getName());
    assertEquals(modelRef, updated.getExecution().getSettings().get("modelName"));
    assertEquals("updated", updated.getDescription());
    assertEquals("1", updated.getVersion());
    assertTrue(
        agentService.listAgents().stream().anyMatch(candidate -> candidate.getName().equals(name)));

    AgentUpdateDTO stale = new AgentUpdateDTO();
    stale.setDescription("stale");
    stale.setExecution(definition.getExecution());
    stale.setConfig(definition.getConfig());
    stale.setExpectedVersion(definition.getVersion());
    assertThrows(DomainException.class, () -> agentService.updateAgent(name, stale));

    agentService.deleteAgent(name, updated.getVersion());
    // 硬删除：同名立即可重建。
    AgentDTO recreated = agentService.createAgent(agent(modelRef, name));
    assertEquals("0", recreated.getVersion());
    agentService.deleteAgent(name, recreated.getVersion());
    assertThrows(DomainException.class, () -> agentService.deleteAgent(name, "0"));
    agentModelService.deleteModel(model.getName(), model.getVersion());
    agentProviderService.deleteProvider(provider.getName(), provider.getVersion());
  }

  @Test
  void subagentAllowlistRequiresLiveReferencesAndPreventsDeletion() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("subagent-provider-" + suffix);
    AgentModelDTO model = model(provider.getName(), "subagent-model-" + suffix);
    String modelRef = model.getName();
    AgentDTO child = agentService.createAgent(agent(modelRef, "subagent-child-" + suffix));
    AgentCreateDTO parentCreate = agent(modelRef, "subagent-parent-" + suffix);
    parentCreate.getConfig().setSubagents(List.of(child.getName()));
    AgentDTO parent = agentService.createAgent(parentCreate);
    try {
      assertEquals(List.of(child.getName()), parent.getConfig().getSubagents());
      assertThrows(
          DomainException.class,
          () -> agentService.deleteAgent(child.getName(), child.getVersion()));

      AgentCreateDTO missing = agent(modelRef, "subagent-missing-" + suffix);
      missing.getConfig().setSubagents(List.of("missing-" + suffix));
      assertThrows(DomainException.class, () -> agentService.createAgent(missing));

      AgentUpdateDTO removeReference = new AgentUpdateDTO();
      removeReference.setDescription(parent.getDescription());
      removeReference.setSystemPrompt(parent.getSystemPrompt());
      removeReference.setExecution(parent.getExecution());
      removeReference.setConfig(parent.getConfig());
      removeReference.getConfig().setSubagents(List.of());
      removeReference.setExpectedVersion(parent.getVersion());
      parent = agentService.updateAgent(parent.getName(), removeReference);

      agentService.deleteAgent(child.getName(), child.getVersion());
      agentService.deleteAgent(parent.getName(), parent.getVersion());
    } finally {
      if (agentService.listAgents().stream()
          .anyMatch(candidate -> candidate.getName().equals(parentCreate.getName()))) {
        AgentDTO current =
            agentService.listAgents().stream()
                .filter(candidate -> candidate.getName().equals(parentCreate.getName()))
                .findFirst()
                .orElseThrow();
        agentService.deleteAgent(current.getName(), current.getVersion());
      }
      agentModelService.deleteModel(model.getName(), model.getVersion());
      agentProviderService.deleteProvider(provider.getName(), provider.getVersion());
    }
  }

  /** 测试意图：Agent 委派图允许自引用和环；自身引用不要求预先存在且不阻止删除自身，其它 Agent 的入边仍受删除保护。 */
  @Test
  void subagentAllowlistAllowsSelfReferenceAndCycles() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("recursive-provider-" + suffix);
    AgentModelDTO model = model(provider.getName(), "recursive-model-" + suffix);
    String modelRef = model.getName();
    String selfName = "recursive-self-" + suffix;
    String leftName = "recursive-left-" + suffix;
    String rightName = "recursive-right-" + suffix;

    AgentCreateDTO selfCreate = agent(modelRef, selfName);
    selfCreate.getConfig().setSubagents(List.of(selfName));
    AgentDTO self = agentService.createAgent(selfCreate);
    assertEquals(List.of(selfName), self.getConfig().getSubagents());
    agentService.deleteAgent(selfName, self.getVersion());

    AgentDTO left = agentService.createAgent(agent(modelRef, leftName));
    AgentCreateDTO rightCreate = agent(modelRef, rightName);
    rightCreate.getConfig().setSubagents(List.of(leftName));
    AgentDTO right = agentService.createAgent(rightCreate);

    AgentUpdateDTO closeCycle = new AgentUpdateDTO();
    closeCycle.setDescription(left.getDescription());
    closeCycle.setSystemPrompt(left.getSystemPrompt());
    closeCycle.setExecution(left.getExecution());
    closeCycle.setConfig(left.getConfig());
    closeCycle.getConfig().setSubagents(List.of(rightName));
    closeCycle.setExpectedVersion(left.getVersion());
    left = agentService.updateAgent(leftName, closeCycle);
    assertEquals(List.of(rightName), left.getConfig().getSubagents());
    assertEquals(List.of(leftName), right.getConfig().getSubagents());

    assertThrows(
        DomainException.class, () -> agentService.deleteAgent(rightName, right.getVersion()));

    AgentUpdateDTO openCycle = new AgentUpdateDTO();
    openCycle.setDescription(left.getDescription());
    openCycle.setSystemPrompt(left.getSystemPrompt());
    openCycle.setExecution(left.getExecution());
    openCycle.setConfig(left.getConfig());
    openCycle.getConfig().setSubagents(List.of());
    openCycle.setExpectedVersion(left.getVersion());
    left = agentService.updateAgent(leftName, openCycle);

    agentService.deleteAgent(rightName, right.getVersion());
    agentService.deleteAgent(leftName, left.getVersion());
    agentModelService.deleteModel(model.getName(), model.getVersion());
    agentProviderService.deleteProvider(provider.getName(), provider.getVersion());
  }

  @Test
  public void rejectsVariantOutsideTheSelectedModelConfiguration() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("agent-variant-provider-" + suffix);
    AgentModelDTO model = model(provider.getName(), "agent-variant-model-" + suffix);
    String modelRef = model.getName();
    AgentCreateDTO invalid = agent(modelRef, "agent-invalid-variant-" + suffix);
    invalid.setExecution(execution(modelRef, "missing"));

    assertThrows(DomainException.class, () -> agentService.createAgent(invalid));

    AgentCreateDTO valid = agent(modelRef, "agent-valid-variant-" + suffix);
    valid.setExecution(execution(modelRef, null));
    AgentDTO created = agentService.createAgent(valid);
    try {
      assertNull(created.getExecution().getSettings().get("variant"));
      AgentUpdateDTO invalidUpdate = new AgentUpdateDTO();
      invalidUpdate.setDescription(created.getDescription());
      invalidUpdate.setExecution(execution(modelRef, "missing"));
      invalidUpdate.setConfig(created.getConfig());
      invalidUpdate.setExpectedVersion(created.getVersion());
      assertThrows(
          DomainException.class, () -> agentService.updateAgent(created.getName(), invalidUpdate));
    } finally {
      agentService.deleteAgent(created.getName(), created.getVersion());
      agentModelService.deleteModel(model.getName(), model.getVersion());
      agentProviderService.deleteProvider(provider.getName(), provider.getVersion());
    }
  }

  @Test
  public void rebindsDefaultModelOnUpdateAndRejectsMissingTarget() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("agent-rebind-provider-" + suffix);
    AgentModelDTO original = model(provider.getName(), "agent-rebind-original-" + suffix);
    AgentModelDTO next = model(provider.getName(), "agent-rebind-next-" + suffix);
    String originalRef = original.getName();
    String nextRef = next.getName();
    AgentDTO definition = agentService.createAgent(agent(originalRef, "agent-rebind-" + suffix));
    try {
      AgentUpdateDTO rebind = new AgentUpdateDTO();
      rebind.setDescription(definition.getDescription());
      rebind.setSystemPrompt(definition.getSystemPrompt());
      rebind.setExecution(execution(nextRef, null));
      rebind.setConfig(definition.getConfig());
      rebind.setExpectedVersion(definition.getVersion());
      AgentDTO updated = agentService.updateAgent(definition.getName(), rebind);
      assertEquals(nextRef, updated.getExecution().getSettings().get("modelName"));
      assertNull(updated.getExecution().getSettings().get("variant"));

      AgentUpdateDTO missing = new AgentUpdateDTO();
      missing.setDescription(updated.getDescription());
      missing.setExecution(execution("missing-" + suffix, null));
      missing.setConfig(updated.getConfig());
      missing.setExpectedVersion(updated.getVersion());
      assertThrows(
          DomainException.class, () -> agentService.updateAgent(updated.getName(), missing));
      definition = updated;
    } finally {
      agentService.deleteAgent(definition.getName(), definition.getVersion());
      agentModelService.deleteModel(next.getName(), next.getVersion());
      agentModelService.deleteModel(original.getName(), original.getVersion());
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
    dto.setKind(AgentModelKind.MODEL);
    dto.setProviderName(providerName);
    dto.setName(name);
    executable(dto);
    return agentModelService.createModel(dto);
  }

  private AgentCreateDTO agent(String model, String name) {
    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    AgentCreateDTO dto = new AgentCreateDTO();
    dto.setName(name);
    dto.setExecution(execution(model, "default"));
    dto.setConfig(config);
    return dto;
  }

  private static HarnessExecutionSelectionDTO execution(String model, String variant) {
    Map<String, Object> settings = new HashMap<>();
    settings.put("modelName", model);
    settings.put("variant", variant);
    HarnessExecutionSelectionDTO selection = new HarnessExecutionSelectionDTO();
    selection.setDriver(BuiltinExecution.DRIVER);
    selection.setSettings(settings);
    return selection;
  }
}
