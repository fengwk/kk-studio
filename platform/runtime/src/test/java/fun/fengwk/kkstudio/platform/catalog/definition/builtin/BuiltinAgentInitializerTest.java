package fun.fengwk.kkstudio.platform.catalog.definition.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentType;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;

/** 测试意图：内置 Agent 初始化 add-if-missing、幂等、不覆盖既有定义，并以真实主键冲突收敛多实例并发。 */
class BuiltinAgentInitializerTest {

  private final AgentRepository repository = mock(AgentRepository.class);
  private final AgentConfigCodec codec = new AgentConfigCodec(JsonMapper.builder().build());
  private final BuiltinAgentInitializer initializer =
      new BuiltinAgentInitializer(repository, codec);

  @Test
  void createsMissingBuiltinWithTypeAndUnconfiguredModel() {
    when(repository.getByName(BuiltinAgents.COMPACTION_NAME)).thenReturn(null);

    initializer.afterPropertiesSet();

    ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
    verify(repository).create(captor.capture());
    Agent created = captor.getValue();
    assertEquals(BuiltinAgents.COMPACTION_NAME, created.name());
    assertEquals(AgentType.BUILTIN, created.type());
    // 独立未配置 execution，不选首个 model，也不继承父配置。
    assertNull(created.execution());
    assertEquals(BuiltinAgentPrompts.compactionSummarizationSystemPrompt(), created.systemPrompt());
    AgentConfigDTO config = codec.decode(created.configJson());
    assertTrue(config.getTools().isEmpty());
    assertTrue(config.getSkills().isEmpty());
    assertTrue(config.getSubagents().isEmpty());
  }

  @Test
  void skipsExistingBuiltinWithoutOverwritingUserEdits() {
    Agent existing =
        Agent.builder()
            .name(BuiltinAgents.COMPACTION_NAME)
            .type(AgentType.BUILTIN)
            .systemPrompt("user edited prompt")
            .build();
    when(repository.getByName(BuiltinAgents.COMPACTION_NAME)).thenReturn(existing);

    // 幂等：重复启动仍是 no-op，绝不覆盖既有编辑。
    initializer.afterPropertiesSet();
    initializer.afterPropertiesSet();

    verify(repository, never()).create(any());
  }

  @Test
  void convergesOnConcurrentInsertConflict() {
    when(repository.getByName(BuiltinAgents.COMPACTION_NAME)).thenReturn(null);
    when(repository.create(any())).thenThrow(new DuplicateKeyException("duplicate"));

    // 并发实例已插入同名行：冲突视为初始化完成，不抛出、不覆盖。
    initializer.afterPropertiesSet();

    verify(repository).create(any());
  }

  /** 测试意图：内置压缩 Agent 的规范身份固定为字面量 {@code Compaction}。 */
  @Test
  void canonicalCompactionNameIsCompaction() {
    assertEquals("Compaction", BuiltinAgents.COMPACTION_NAME);
    assertTrue(BuiltinAgents.isReservedName(BuiltinAgents.COMPACTION_NAME));
  }
}
