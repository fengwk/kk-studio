package fun.fengwk.kkstudio.platform.catalog.model.service;

import static fun.fengwk.kkstudio.platform.catalog.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;

import java.util.List;

/** 模型名称在 Provider 内唯一并按复合名称寻址；支持事务原子重命名并同步更新引用 Agent，硬删除后可同名重建。 */
public class AgentModelServiceTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService agentProviderService;
  @Autowired private AgentModelService agentModelService;
  @Autowired private AgentDefinitionService agentDefinitionService;

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
    update.setName(modelName);
    update.setModelId(model.getModelId());
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
    stale.setName(modelName);
    stale.setModelId(model.getModelId());
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

  @Test
  public void shouldRenameModelAndAtomicallyUpdateReferencingAgents() {
    String suffix = Long.toString(System.nanoTime());
    String providerName = "rename-provider-" + suffix;
    provider(providerName);

    String originalModel = "model-orig-" + suffix;
    String renamedModel = "model-renamed-" + suffix;
    String existingModel = "model-existing-" + suffix;

    AgentModelDTO origDto = agentModelService.createModel(model(providerName, originalModel));
    agentModelService.createModel(model(providerName, existingModel));

    String agentName = "agent-ref-" + suffix;
    AgentDefinitionDTO agentDto =
        agentDefinitionService.createAgent(agent(providerName + "/" + originalModel, agentName));
    assertEquals(providerName + "/" + originalModel, agentDto.getModel());
    assertEquals("0", agentDto.getVersion());

    // 1. 成功重命名：原名称变为新名称，version 递增，保留 createTime，引用它的 agent_definition 原子同步且 version 递增
    AgentModelUpdateDTO renameReq = new AgentModelUpdateDTO();
    renameReq.setName(renamedModel);
    renameReq.setModelId(origDto.getModelId());
    renameReq.setDescription("renamed desc");
    renameReq.setConfig(origDto.getConfig());
    renameReq.setExpectedVersion(origDto.getVersion());

    AgentModelDTO renamedDto =
        agentModelService.updateModel(providerName, originalModel, renameReq);
    assertEquals(renamedModel, renamedDto.getName());
    assertEquals(providerName, renamedDto.getProviderName());
    assertEquals("1", renamedDto.getVersion());
    assertEquals("renamed desc", renamedDto.getDescription());
    assertEquals(origDto.getCreateTime(), renamedDto.getCreateTime());
    assertTrue(renamedDto.getUpdateTime().compareTo(origDto.getUpdateTime()) >= 0);

    // 验证旧名称已不存在，新名称存在
    assertThrows(
        AiResourceNotFoundException.class,
        () -> agentModelService.updateModel(providerName, originalModel, renameReq));
    assertTrue(
        agentModelService.pageModels(new PageQuery(1, 100)).getResults().stream()
            .noneMatch(
                m ->
                    m.getProviderName().equals(providerName) && m.getName().equals(originalModel)));
    assertTrue(
        agentModelService.pageModels(new PageQuery(1, 100)).getResults().stream()
            .anyMatch(
                m -> m.getProviderName().equals(providerName) && m.getName().equals(renamedModel)));

    // 验证引用的 Agent definition 原子更新到 provider/renamedModel，其 version 递增为 1 且 updateTime 推进
    AgentDefinitionDTO updatedAgent =
        agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
            .filter(a -> a.getName().equals(agentName))
            .findFirst()
            .orElseThrow();
    assertEquals(providerName + "/" + renamedModel, updatedAgent.getModel());
    assertEquals("1", updatedAgent.getVersion());
    assertTrue(updatedAgent.getUpdateTime().compareTo(agentDto.getUpdateTime()) >= 0);

    // 2. 目标名称冲突：重命名到同 Provider 下已有的 existingModel 触发 409 duplicate
    AgentModelUpdateDTO collisionReq = new AgentModelUpdateDTO();
    collisionReq.setName(existingModel);
    collisionReq.setModelId(renamedDto.getModelId());
    collisionReq.setConfig(renamedDto.getConfig());
    collisionReq.setExpectedVersion(renamedDto.getVersion());
    assertThrows(
        AiDuplicateException.class,
        () -> agentModelService.updateModel(providerName, renamedModel, collisionReq));

    // 验证目标冲突回滚：原模型数据/版本及引用 Agent 保持冲突尝试前的状态不变
    AgentModelDTO sourceAfterCollision =
        agentModelService.pageModels(new PageQuery(1, 100)).getResults().stream()
            .filter(
                m -> m.getProviderName().equals(providerName) && m.getName().equals(renamedModel))
            .findFirst()
            .orElseThrow();
    assertEquals("1", sourceAfterCollision.getVersion());
    assertEquals("renamed desc", sourceAfterCollision.getDescription());
    assertEquals(origDto.getCreateTime(), sourceAfterCollision.getCreateTime());

    AgentModelDTO targetAfterCollision =
        agentModelService.pageModels(new PageQuery(1, 100)).getResults().stream()
            .filter(
                m -> m.getProviderName().equals(providerName) && m.getName().equals(existingModel))
            .findFirst()
            .orElseThrow();
    assertEquals("0", targetAfterCollision.getVersion());

    AgentDefinitionDTO agentAfterCollision =
        agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
            .filter(a -> a.getName().equals(agentName))
            .findFirst()
            .orElseThrow();
    assertEquals(providerName + "/" + renamedModel, agentAfterCollision.getModel());
    assertEquals(updatedAgent.getVersion(), agentAfterCollision.getVersion());
    assertEquals(updatedAgent.getUpdateTime(), agentAfterCollision.getUpdateTime());

    // 3. expectedVersion 过期：CAS 检查失败触发版本冲突
    AgentModelUpdateDTO staleReq = new AgentModelUpdateDTO();
    staleReq.setName("model-another-" + suffix);
    staleReq.setModelId(renamedDto.getModelId());
    staleReq.setConfig(renamedDto.getConfig());
    staleReq.setExpectedVersion("0");
    assertThrows(
        AiVersionConflictException.class,
        () -> agentModelService.updateModel(providerName, renamedModel, staleReq));

    // 4. 非法名称：空白、首尾空格、超出 128 字符被校验拦截
    AgentModelUpdateDTO blankReq = new AgentModelUpdateDTO();
    blankReq.setName("   ");
    blankReq.setModelId(renamedDto.getModelId());
    blankReq.setConfig(renamedDto.getConfig());
    blankReq.setExpectedVersion(renamedDto.getVersion());
    assertThrows(
        AiValidationException.class,
        () -> agentModelService.updateModel(providerName, renamedModel, blankReq));

    AgentModelUpdateDTO paddedReq = new AgentModelUpdateDTO();
    paddedReq.setName(" " + renamedModel + " ");
    paddedReq.setModelId(renamedDto.getModelId());
    paddedReq.setConfig(renamedDto.getConfig());
    paddedReq.setExpectedVersion(renamedDto.getVersion());
    assertThrows(
        AiValidationException.class,
        () -> agentModelService.updateModel(providerName, renamedModel, paddedReq));

    AgentModelUpdateDTO oversizedReq = new AgentModelUpdateDTO();
    oversizedReq.setName("a".repeat(129));
    oversizedReq.setModelId(renamedDto.getModelId());
    oversizedReq.setConfig(renamedDto.getConfig());
    oversizedReq.setExpectedVersion(renamedDto.getVersion());
    assertThrows(
        AiValidationException.class,
        () -> agentModelService.updateModel(providerName, renamedModel, oversizedReq));

    // 5. 同名更新走正常单行 CAS，不使用 insert/delete，引用它的 Agent 保持不变
    AgentModelUpdateDTO noOpRenameReq = new AgentModelUpdateDTO();
    noOpRenameReq.setName(renamedModel);
    noOpRenameReq.setModelId(renamedDto.getModelId());
    noOpRenameReq.setDescription("updated again");
    noOpRenameReq.setConfig(renamedDto.getConfig());
    noOpRenameReq.setExpectedVersion(renamedDto.getVersion());
    AgentModelDTO noOpUpdated =
        agentModelService.updateModel(providerName, renamedModel, noOpRenameReq);
    assertEquals("2", noOpUpdated.getVersion());
    assertEquals("updated again", noOpUpdated.getDescription());
    assertEquals(origDto.getCreateTime(), noOpUpdated.getCreateTime());

    AgentDefinitionDTO agentAfterSameName =
        agentDefinitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
            .filter(a -> a.getName().equals(agentName))
            .findFirst()
            .orElseThrow();
    assertEquals(updatedAgent.getVersion(), agentAfterSameName.getVersion());

    // 6. 支持包含斜杠的 model name 进行重命名
    String slashModel = "org/model-v1-" + suffix;
    String slashModelRenamed = "org/model-v2-" + suffix;
    AgentModelDTO slashDto = agentModelService.createModel(model(providerName, slashModel));
    AgentModelUpdateDTO slashRenameReq = new AgentModelUpdateDTO();
    slashRenameReq.setName(slashModelRenamed);
    slashRenameReq.setModelId(slashDto.getModelId());
    slashRenameReq.setConfig(slashDto.getConfig());
    slashRenameReq.setExpectedVersion("0");
    AgentModelDTO slashRenamedDto =
        agentModelService.updateModel(providerName, slashModel, slashRenameReq);
    assertEquals(slashModelRenamed, slashRenamedDto.getName());
    assertEquals("1", slashRenamedDto.getVersion());

    // 清理
    agentDefinitionService.deleteAgent(agentName, agentAfterSameName.getVersion());
    agentModelService.deleteModel(providerName, renamedModel, noOpUpdated.getVersion());
    agentModelService.deleteModel(providerName, existingModel, "0");
    agentModelService.deleteModel(providerName, slashModelRenamed, slashRenamedDto.getVersion());
    agentProviderService.deleteProvider(providerName, "0");
  }

  private AgentDefinitionCreateDTO agent(String model, String name) {
    AgentDefinitionCreateDTO dto = new AgentDefinitionCreateDTO();
    dto.setName(name);
    dto.setModel(model);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setToolIds(List.of());
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
    dto.setProviderName(providerName);
    dto.setName(name);
    executable(dto);
    return dto;
  }
}
