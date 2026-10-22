package fun.fengwk.kkstudio.platform.catalog.model.service;

import static fun.fengwk.kkstudio.platform.catalog.model.AgentModelTestData.buildConfig;
import static fun.fengwk.kkstudio.platform.catalog.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupMemberDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;

import java.util.List;

/** 全局模型名称跨 MODEL/GROUP 唯一，并验证组 CRUD、引用维护与冷却清理。 */
public class AgentModelServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;
  @Autowired private AgentDefinitionService agentDefinitionService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  public void shouldEnforceGlobalModelNamesAcrossProviders() {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = provider("model-provider-" + suffix);
    AgentProviderDTO otherProvider = provider("model-provider-other-" + suffix);
    String modelName = "shared-model-" + suffix;

    AgentModelDTO model = agentModelService.createModel(model(provider.getName(), modelName));
    assertEquals(provider.getName(), model.getProviderName());
    assertEquals(AgentModelKind.MODEL, model.getKind());
    assertEquals("0", model.getVersion());
    assertThrows(
        AiDuplicateException.class,
        () -> agentModelService.createModel(model(provider.getName(), modelName)));
    // 同一逻辑名称即使换 Provider 也不可重复。
    assertThrows(
        AiDuplicateException.class,
        () -> agentModelService.createModel(model(otherProvider.getName(), modelName)));

    assertThrows(
        AiResourceNotFoundException.class,
        () -> agentModelService.createModel(model("missing-" + suffix, "missing-" + suffix)));
    assertThrows(
        AiValidationException.class,
        () -> agentModelService.createModel(model(" ", "invalid-" + suffix)));
    assertThrows(
        AiInUseException.class,
        () -> agentProviderService.deleteProvider(provider.getName(), provider.getVersion()));

    AgentModelUpdateDTO update = modelUpdate(model, modelName, "updated");
    AgentModelDTO updated = agentModelService.updateModel(modelName, update);
    assertEquals("updated", updated.getDescription());
    assertEquals("1", updated.getVersion());
    assertTrue(
        agentModelService.pageModels(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(candidate -> candidate.getName().equals(modelName)));

    AgentModelUpdateDTO stale = modelUpdate(model, modelName, "stale");
    stale.setExpectedVersion(model.getVersion());
    assertThrows(
        AiVersionConflictException.class, () -> agentModelService.updateModel(modelName, stale));

    agentModelService.deleteModel(modelName, updated.getVersion());
    assertThrows(
        AiResourceNotFoundException.class, () -> agentModelService.deleteModel(modelName, "0"));
    // 硬删除：同名立即可重建，重建后解析到新行。
    AgentModelDTO recreated = agentModelService.createModel(model(provider.getName(), modelName));
    assertEquals("0", recreated.getVersion());
    agentModelService.deleteModel(modelName, recreated.getVersion());
    agentProviderService.deleteProvider(provider.getName(), "0");
    agentProviderService.deleteProvider(otherProvider.getName(), "0");
  }

  @Test
  public void shouldRenameModelAndAtomicallyUpdateAgentsGroupsAndCooldowns() {
    String suffix = Long.toString(System.nanoTime());
    String providerName = "rename-provider-" + suffix;
    provider(providerName);

    String originalModel = "model-orig-" + suffix;
    String renamedModel = "model-renamed-" + suffix;
    String existingModel = "model-existing-" + suffix;

    AgentModelDTO origDto = agentModelService.createModel(model(providerName, originalModel));
    agentModelService.createModel(model(providerName, existingModel));
    AgentModelDTO group =
        agentModelService.createModel(group("group-" + suffix, originalModel, "default"));

    String agentName = "agent-ref-" + suffix;
    AgentDefinitionDTO agentDto =
        agentDefinitionService.createAgent(agent(originalModel, agentName));
    assertEquals(originalModel, agentDto.getModel());

    insertCooldown(group.getName(), originalModel, "default", Long.parseLong(group.getVersion()));

    AgentModelUpdateDTO renameReq = modelUpdate(origDto, renamedModel, "renamed desc");
    AgentModelDTO renamedDto = agentModelService.updateModel(originalModel, renameReq);
    assertEquals(renamedModel, renamedDto.getName());
    assertEquals(providerName, renamedDto.getProviderName());
    assertEquals("1", renamedDto.getVersion());
    assertEquals(origDto.getCreateTime(), renamedDto.getCreateTime());

    assertThrows(
        AiResourceNotFoundException.class,
        () -> agentModelService.updateModel(originalModel, renameReq));

    AgentDefinitionDTO updatedAgent =
        agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
            .filter(candidate -> candidate.getName().equals(agentName))
            .findFirst()
            .orElseThrow();
    assertEquals(renamedModel, updatedAgent.getModel());
    assertEquals("1", updatedAgent.getVersion());

    AgentModelDTO renamedGroup =
        agentModelService.pageModels(new PageQuery(1, 100)).getResults().stream()
            .filter(candidate -> candidate.getName().equals(group.getName()))
            .findFirst()
            .orElseThrow();
    assertEquals("1", renamedGroup.getVersion());
    assertEquals(renamedModel, renamedGroup.getGroup().getMembers().get(0).getModelName());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from agent_model_group_cooldown where group_name = ?",
            Integer.class,
            group.getName()));

    AgentModelUpdateDTO collisionReq = modelUpdate(renamedDto, existingModel, null);
    assertThrows(
        AiDuplicateException.class,
        () -> agentModelService.updateModel(renamedModel, collisionReq));
    AgentModelDTO unchanged =
        agentModelService.pageModels(new PageQuery(1, 100)).getResults().stream()
            .filter(candidate -> candidate.getName().equals(renamedModel))
            .findFirst()
            .orElseThrow();
    assertEquals("1", unchanged.getVersion());
    assertEquals("renamed desc", unchanged.getDescription());

    // 组仍引用单 Model 时不可删除；删除组后引用消失。
    assertThrows(
        AiInUseException.class,
        () -> agentModelService.deleteModel(renamedModel, unchanged.getVersion()));
    agentModelService.deleteModel(renamedGroup.getName(), renamedGroup.getVersion());
    AgentDefinitionDTO agentAfterCollision =
        agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
            .filter(candidate -> candidate.getName().equals(agentName))
            .findFirst()
            .orElseThrow();
    assertThrows(
        AiInUseException.class,
        () -> agentModelService.deleteModel(renamedModel, unchanged.getVersion()));
    agentDefinitionService.deleteAgent(agentName, agentAfterCollision.getVersion());
    agentModelService.deleteModel(renamedModel, unchanged.getVersion());
    agentModelService.deleteModel(existingModel, "0");
    agentProviderService.deleteProvider(providerName, "0");
  }

  @Test
  public void shouldRejectRemovingVariantStillReferencedAndKeepGroupShapeStrict() {
    String suffix = Long.toString(System.nanoTime());
    String providerName = "variant-provider-" + suffix;
    provider(providerName);

    AgentModelCreateDTO create = model(providerName, "variant-model-" + suffix);
    create.setConfig(
        buildConfig(
            32768, 4096, true, false, "standard", "v1", List.of("default", "fast"), "default"));
    AgentModelDTO model = agentModelService.createModel(create);

    AgentModelDTO group =
        agentModelService.createModel(group("variant-group-" + suffix, model.getName(), "fast"));
    assertEquals(AgentModelKind.GROUP, group.getKind());
    assertNull(group.getProviderName());
    assertNull(group.getModelId());
    assertNull(group.getConfig().getPricing());
    assertEquals(32768, group.getConfig().getLimit().getContext());
    assertEquals(4096, group.getConfig().getLimit().getOutput());
    assertEquals(List.of(), group.getConfig().getVariants());
    assertNull(group.getConfig().getDefaultVariant());
    assertEquals(1, group.getGroup().getMembers().size());

    AgentModelUpdateDTO removeVariant = modelUpdate(model, model.getName(), null);
    removeVariant.setConfig(
        buildConfig(32768, 4096, true, false, "standard", "v1", List.of("default"), "default"));
    assertThrows(
        AiInUseException.class,
        () -> agentModelService.updateModel(model.getName(), removeVariant));

    AgentModelCreateDTO invalidGroup = group("invalid-group-" + suffix, model.getName(), "fast");
    invalidGroup.setProviderName(providerName);
    assertThrows(AiValidationException.class, () -> agentModelService.createModel(invalidGroup));

    agentModelService.deleteModel(group.getName(), group.getVersion());
    agentModelService.deleteModel(model.getName(), model.getVersion());
    agentProviderService.deleteProvider(providerName, "0");
  }

  private void insertCooldown(
      String groupName, String memberModelName, String variant, long groupVersion) {
    jdbc.update(
        "insert into agent_model_group_cooldown"
            + " (group_name, member_model_name, member_variant, group_version, cooldown_until)"
            + " values (?, ?, ?, ?, current_timestamp + interval '1 hour')",
        groupName,
        memberModelName,
        variant,
        groupVersion);
  }

  private AgentDefinitionCreateDTO agent(String model, String name) {
    AgentDefinitionCreateDTO dto = new AgentDefinitionCreateDTO();
    dto.setName(name);
    dto.setModel(model);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    dto.setConfig(config);
    return dto;
  }

  private AgentProviderDTO provider(String name) {
    AgentProviderCreateDTO dto = new AgentProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType("openai");
    return agentProviderService.createProvider(dto);
  }

  private AgentModelCreateDTO model(String providerName, String name) {
    AgentModelCreateDTO dto = new AgentModelCreateDTO();
    dto.setKind(AgentModelKind.MODEL);
    dto.setProviderName(providerName);
    dto.setName(name);
    executable(dto);
    return dto;
  }

  private AgentModelCreateDTO group(String name, String modelName, String variant) {
    AgentModelGroupMemberDTO member = new AgentModelGroupMemberDTO();
    member.setModelName(modelName);
    member.setVariant(variant);
    AgentModelGroupDTO group = new AgentModelGroupDTO();
    group.setMembers(List.of(member));
    group.setFallbackStatusCodes(List.of(429));
    group.setCooldownSeconds(30);
    AgentModelCreateDTO dto = new AgentModelCreateDTO();
    dto.setName(name);
    dto.setKind(AgentModelKind.GROUP);
    dto.setGroup(group);
    return dto;
  }

  private AgentModelUpdateDTO modelUpdate(AgentModelDTO current, String name, String description) {
    AgentModelUpdateDTO dto = new AgentModelUpdateDTO();
    dto.setKind(AgentModelKind.MODEL);
    dto.setName(name);
    dto.setProviderName(current.getProviderName());
    dto.setModelId(current.getModelId());
    dto.setDescription(description);
    AgentModelConfigDTO config = current.getConfig();
    dto.setConfig(config);
    dto.setExpectedVersion(current.getVersion());
    return dto;
  }
}
