package fun.fengwk.kkstudio.platform.catalog.definition.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;
import fun.fengwk.kkstudio.share.ai.catalog.BuiltinAgentPrompts;

/** 测试意图：内置 Agent 初始化 add-if-missing、幂等、不覆盖既有定义，并以真实主键冲突收敛多实例并发。 */
class BuiltinAgentInitializerTest {

  private final AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
  private final AgentDefinitionConfigCodec codec =
      new AgentDefinitionConfigCodec(JsonMapper.builder().build());
  private final BuiltinAgentInitializer initializer =
      new BuiltinAgentInitializer(repository, codec);

  @Test
  void createsMissingBuiltinWithTypeAndUnconfiguredModel() {
    when(repository.getByName(BuiltinAgentDefinitions.COMPACTION_NAME)).thenReturn(null);

    initializer.afterPropertiesSet();

    ArgumentCaptor<AgentDefinition> captor = ArgumentCaptor.forClass(AgentDefinition.class);
    verify(repository).create(captor.capture());
    AgentDefinition created = captor.getValue();
    assertEquals(BuiltinAgentDefinitions.COMPACTION_NAME, created.name());
    assertEquals(AgentDefinitionType.BUILTIN, created.type());
    // 独立未配置模型：model 与 variant 同为 null，不选首个 model，也不继承父配置。
    assertNull(created.modelName());
    assertNull(created.variant());
    assertEquals(BuiltinAgentPrompts.compactionSummarizationSystemPrompt(), created.systemPrompt());
    AgentDefinitionConfigDTO config = codec.decode(created.configJson());
    assertTrue(config.getTools().isEmpty());
    assertTrue(config.getSkills().isEmpty());
    assertTrue(config.getSubagents().isEmpty());
    assertEquals(Boolean.TRUE, config.getInheritParentEnvironment());
  }

  @Test
  void skipsExistingBuiltinWithoutOverwritingUserEdits() {
    AgentDefinition existing =
        AgentDefinition.builder()
            .name(BuiltinAgentDefinitions.COMPACTION_NAME)
            .type(AgentDefinitionType.BUILTIN)
            .systemPrompt("user edited prompt")
            .build();
    when(repository.getByName(BuiltinAgentDefinitions.COMPACTION_NAME)).thenReturn(existing);

    // 幂等：重复启动仍是 no-op，绝不覆盖既有编辑。
    initializer.afterPropertiesSet();
    initializer.afterPropertiesSet();

    verify(repository, never()).create(any());
  }

  @Test
  void convergesOnConcurrentInsertConflict() {
    when(repository.getByName(BuiltinAgentDefinitions.COMPACTION_NAME)).thenReturn(null);
    when(repository.create(any())).thenThrow(new DuplicateKeyException("duplicate"));

    // 并发实例已插入同名行：冲突视为初始化完成，不抛出、不覆盖。
    initializer.afterPropertiesSet();

    verify(repository).create(any());
  }

  /** 测试意图：内置压缩 Agent 的规范身份固定为字面量 {@code Compaction}，旧的小写 {@code compaction} 不再是保留名。 */
  @Test
  void canonicalCompactionNameIsCompactionAndLowercaseIsNotReserved() {
    assertEquals("Compaction", BuiltinAgentDefinitions.COMPACTION_NAME);
    assertTrue(BuiltinAgentDefinitions.isReservedName(BuiltinAgentDefinitions.COMPACTION_NAME));
    assertFalse(BuiltinAgentDefinitions.isReservedName("compaction"));
  }
}
