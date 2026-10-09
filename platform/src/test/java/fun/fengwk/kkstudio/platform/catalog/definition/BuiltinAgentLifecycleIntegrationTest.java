package fun.fengwk.kkstudio.platform.catalog.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentDefinitions;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentInitializer;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;

import java.util.List;

/** 内置 Agent 目录机制的 PostgreSQL 集成测试：add-if-missing 幂等、不覆盖用户编辑、按 type 通用保护、保留名不可被用户覆盖、模型可显式未配置。 */
public class BuiltinAgentLifecycleIntegrationTest extends PostgresSpringTestSupport {

  private static final String COMPACTION = BuiltinAgentDefinitions.COMPACTION_NAME;

  @Autowired private BuiltinAgentInitializer initializer;
  @Autowired private AgentDefinitionService definitionService;
  @Autowired private AgentDefinitionRepository definitionRepository;
  @Autowired private AgentDefinitionConfigCodec configCodec;

  /** 首次初始化写入固定定义；重复启动是 no-op，版本不变。 */
  @Test
  void initializesCompactionIdempotentlyWithUnconfiguredModel() {
    initializer.afterPropertiesSet();
    AgentDefinition first = definitionRepository.getByName(COMPACTION);
    assertNotNull(first);
    assertEquals(AgentDefinitionType.BUILTIN, first.getType());
    assertNull(first.getModelProviderName());
    assertNull(first.getModelName());
    assertNull(first.getVariant());
    assertEquals(CompactionPrompts.summarizationSystemPrompt(), first.getSystemPrompt());
    assertEquals(0L, first.getVersion());

    initializer.afterPropertiesSet();
    AgentDefinition second = definitionRepository.getByName(COMPACTION);
    assertEquals(0L, second.getVersion(), "重复初始化绝不能覆盖既有行");

    AgentDefinitionDTO dto = findPage(COMPACTION);
    assertEquals(AgentDefinitionType.BUILTIN, dto.getType());
    assertNull(dto.getModel());
  }

  /** 用户对内置 Agent prompt/description 的编辑在重启初始化后保留。 */
  @Test
  void preservesUserEditsAcrossRestart() {
    initializer.afterPropertiesSet();
    AgentDefinition current = definitionRepository.getByName(COMPACTION);

    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setDescription("edited description");
    update.setSystemPrompt("custom summarization prompt");
    update.setModel(null);
    update.setConfig(configCodec.decode(current.getConfigJson()));
    update.setExpectedVersion(CatalogVersions.format(current.getVersion()));
    AgentDefinitionDTO updated = definitionService.updateAgent(COMPACTION, update);
    assertEquals("custom summarization prompt", updated.getSystemPrompt());
    assertNull(updated.getModel());

    initializer.afterPropertiesSet();
    AgentDefinition reloaded = definitionRepository.getByName(COMPACTION);
    assertEquals("custom summarization prompt", reloaded.getSystemPrompt());
    assertEquals("edited description", reloaded.getDescription());
  }

  /** 保护按 type 通用：保留名与任意自定义名的 BUILTIN 行都不可删除，不依赖 compaction 分支。 */
  @Test
  void protectsBuiltinByTypeNotByName() {
    initializer.afterPropertiesSet();
    AgentDefinition compaction = definitionRepository.getByName(COMPACTION);
    assertThrows(
        AiInUseException.class,
        () ->
            definitionService.deleteAgent(
                COMPACTION, CatalogVersions.format(compaction.getVersion())));

    AgentDefinition synthetic = new AgentDefinition();
    synthetic.setName("synthetic-builtin");
    synthetic.setType(AgentDefinitionType.BUILTIN);
    synthetic.setConfigJson(configCodec.encode(emptyConfig()));
    assertTrue(definitionRepository.create(synthetic));
    AgentDefinition stored = definitionRepository.getByName("synthetic-builtin");
    assertThrows(
        AiInUseException.class,
        () ->
            definitionService.deleteAgent(
                "synthetic-builtin", CatalogVersions.format(stored.getVersion())));
  }

  /** 用户不能以保留名创建自己的 Agent 覆盖系统身份。 */
  @Test
  void rejectsUserCreationOfReservedName() {
    initializer.afterPropertiesSet();
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName(COMPACTION);
    create.setModel(null);
    create.setConfig(emptyConfig());
    assertThrows(AiDuplicateException.class, () -> definitionService.createAgent(create));
  }

  /** 内置 Agent 支持显式未配置模型；用户创建仍要求模型。 */
  @Test
  void builtinSupportsUnconfiguredModelWhileUserRequiresIt() {
    initializer.afterPropertiesSet();
    assertNull(definitionRepository.getByName(COMPACTION).getModelName());

    AgentDefinitionCreateDTO user = new AgentDefinitionCreateDTO();
    user.setName("user-agent-without-model");
    user.setModel(null);
    user.setConfig(emptyConfig());
    assertThrows(AiValidationException.class, () -> definitionService.createAgent(user));
  }

  private AgentDefinitionDTO findPage(String name) {
    return definitionService.pageAgents(new PageQuery(1, 100)).getResults().stream()
        .filter(agent -> agent.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static AgentDefinitionConfigDTO emptyConfig() {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    return config;
  }
}
