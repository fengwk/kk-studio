package fun.fengwk.kkstudio.platform.catalog.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentInitializer;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentPrompts;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgents;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentUpdateDTO;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.List;

/**
 * 内置 Agent 目录机制的 PostgreSQL 集成测试：add-if-missing 幂等、不覆盖用户编辑、按 type 通用保护、保留名不可被用户覆盖、execution 可显式未配置。
 */
public class BuiltinAgentLifecycleIntegrationTest extends PostgresSpringTestSupport {

  private static final String COMPACTION = BuiltinAgents.COMPACTION_NAME;

  @Autowired private BuiltinAgentInitializer initializer;
  @Autowired private AgentService definitionService;
  @Autowired private AgentRepository definitionRepository;
  @Autowired private AgentConfigCodec configCodec;

  /** 首次初始化写入固定定义；重复启动是 no-op，版本不变。 */
  @Test
  void initializesCompactionIdempotentlyWithUnconfiguredExecution() {
    initializer.afterPropertiesSet();
    Agent first = definitionRepository.getByName(COMPACTION);
    assertNotNull(first);
    assertEquals(AgentType.BUILTIN, first.type());
    assertNull(first.execution());
    assertEquals(BuiltinAgentPrompts.compactionSummarizationSystemPrompt(), first.systemPrompt());
    assertEquals(0L, first.version());

    initializer.afterPropertiesSet();
    Agent second = definitionRepository.getByName(COMPACTION);
    assertEquals(0L, second.version(), "重复初始化绝不能覆盖既有行");

    AgentDTO dto = findAgent(COMPACTION);
    assertEquals(AgentType.BUILTIN, dto.getType());
    assertNull(dto.getExecution());
  }

  /** 用户对内置 Agent prompt/description 的编辑在重启初始化后保留。 */
  @Test
  void preservesUserEditsAcrossRestart() {
    initializer.afterPropertiesSet();
    Agent current = definitionRepository.getByName(COMPACTION);

    AgentUpdateDTO update = new AgentUpdateDTO();
    update.setDescription("edited description");
    update.setSystemPrompt("custom summarization prompt");
    update.setExecution(null);
    update.setConfig(configCodec.decode(current.configJson()));
    update.setExpectedVersion(current.version());
    AgentDTO updated = definitionService.updateAgent(COMPACTION, update);
    assertEquals("custom summarization prompt", updated.getSystemPrompt());
    assertNull(updated.getExecution());

    initializer.afterPropertiesSet();
    Agent reloaded = definitionRepository.getByName(COMPACTION);
    assertEquals("custom summarization prompt", reloaded.systemPrompt());
    assertEquals("edited description", reloaded.description());
  }

  /** 保护按 type 通用：保留名与任意自定义名的 BUILTIN 行都不可删除，不依赖 compaction 分支。 */
  @Test
  void protectsBuiltinByTypeNotByName() {
    initializer.afterPropertiesSet();
    Agent compaction = definitionRepository.getByName(COMPACTION);
    assertThrows(
        DomainException.class,
        () -> definitionService.deleteAgent(COMPACTION, compaction.version()));

    Agent synthetic =
        Agent.builder()
            .name("synthetic-builtin")
            .type(AgentType.BUILTIN)
            .configJson(configCodec.encode(emptyConfig()))
            .build();
    assertTrue(definitionRepository.create(synthetic));
    Agent stored = definitionRepository.getByName("synthetic-builtin");
    assertThrows(
        DomainException.class,
        () -> definitionService.deleteAgent("synthetic-builtin", stored.version()));
  }

  /** 用户不能以保留名创建自己的 Agent 覆盖系统身份。 */
  @Test
  void rejectsUserCreationOfReservedName() {
    initializer.afterPropertiesSet();
    AgentCreateDTO create = new AgentCreateDTO();
    create.setName(COMPACTION);
    create.setExecution(null);
    create.setConfig(emptyConfig());
    assertThrows(DomainException.class, () -> definitionService.createAgent(create));
  }

  /** 内置 Agent 支持显式未配置 execution；用户创建仍要求 execution。 */
  @Test
  void builtinSupportsUnconfiguredExecutionWhileUserRequiresIt() {
    initializer.afterPropertiesSet();
    assertNull(definitionRepository.getByName(COMPACTION).execution());

    AgentCreateDTO user = new AgentCreateDTO();
    user.setName("user-agent-without-model");
    user.setExecution(null);
    user.setConfig(emptyConfig());
    assertThrows(DomainException.class, () -> definitionService.createAgent(user));
  }

  private AgentDTO findAgent(String name) {
    return definitionService.listAgents().stream()
        .filter(agent -> agent.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static AgentConfigDTO emptyConfig() {
    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    return config;
  }
}
