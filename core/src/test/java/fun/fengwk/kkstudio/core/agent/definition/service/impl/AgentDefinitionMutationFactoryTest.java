package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import org.junit.jupiter.api.Test;

/**
 * AgentDefinitionMutationFactory 的聚焦行为测试。
 *
 * @author fengwk
 */
public class AgentDefinitionMutationFactoryTest {

  /** 校验创建 mutation 会补齐默认 variant/数组 JSON，并完整组装 agent 定义实体。 */
  @Test
  public void shouldCreateAgentDefinitionWithNormalizedDefaults() {
    AgentDefinitionMutationFactory factory = newFactory();
    AgentDefinitionCreateDTO createDTO = new AgentDefinitionCreateDTO();
    createDTO.setName("  assistant-a  ");
    createDTO.setDescription("  helpful  ");
    createDTO.setSystemPrompt("  system prompt  ");
    createDTO.setDefaultProvider("  openai  ");
    createDTO.setDefaultModel("  gpt-4.1  ");

    AgentDefinitionMutationFactory.Mutation mutation = factory.newCreateMutation(createDTO);
    AgentDefinition agent = factory.newAgent(3L, 5L, mutation);

    assertEquals("assistant-a", mutation.name());
    assertEquals("helpful", mutation.description());
    assertEquals("system prompt", mutation.systemPrompt());
    assertEquals("openai", mutation.defaultProvider());
    assertEquals("gpt-4.1", mutation.defaultModel());
    assertEquals("default", mutation.defaultVariant());
    assertEquals("[]", mutation.toolsJson());
    assertEquals("[]", mutation.subagentsJson());
    assertEquals("[]", mutation.skillsJson());
    assertNotNull(agent.getId());
    assertEquals(3L, agent.getDefaultProviderId());
    assertEquals(5L, agent.getDefaultModelId());
    assertEquals("assistant-a", agent.getName());
  }

  /** 校验更新 mutation 会复用旧名称，并把空白描述类字段收敛为 null。 */
  @Test
  public void shouldApplyUpdateMutationWithCurrentNameFallback() {
    AgentDefinitionMutationFactory factory = newFactory();
    AgentDefinitionUpdateDTO updateDTO = new AgentDefinitionUpdateDTO();
    updateDTO.setName(" ");
    updateDTO.setDescription(" ");
    updateDTO.setSystemPrompt(" ");
    updateDTO.setDefaultProvider("openai");
    updateDTO.setDefaultModel("gpt-4.1");
    updateDTO.setDefaultVariant("  stable  ");
    updateDTO.setToolsJson("[\"browser\"]");
    updateDTO.setSubagentsJson("[\"planner\"]");
    updateDTO.setSkillsJson("[\"writer\"]");

    AgentDefinition agent = new AgentDefinition();
    factory.apply(agent, 7L, 9L, factory.newUpdateMutation("assistant-a", updateDTO));

    assertEquals("assistant-a", agent.getName());
    assertNull(agent.getDescription());
    assertNull(agent.getSystemPrompt());
    assertEquals(7L, agent.getDefaultProviderId());
    assertEquals(9L, agent.getDefaultModelId());
    assertEquals("stable", agent.getDefaultVariant());
    assertEquals("[\"browser\"]", agent.getToolsJson());
    assertEquals("[\"planner\"]", agent.getSubagentsJson());
    assertEquals("[\"writer\"]", agent.getSkillsJson());
  }

  /** 校验缺失关键引用名或非法数组 JSON 会被拒绝，避免 agent 定义进入坏状态。 */
  @Test
  public void shouldRejectInvalidArguments() {
    AgentDefinitionMutationFactory factory = newFactory();
    AgentDefinitionCreateDTO createDTO = new AgentDefinitionCreateDTO();
    createDTO.setName("assistant-a");
    createDTO.setDefaultProvider("openai");
    createDTO.setDefaultModel("gpt-4.1");

    assertThrows(IllegalArgumentException.class, () -> new AgentDefinitionMutationFactory(null));
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(null));

    createDTO.setName(" ");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setName("assistant-a");
    createDTO.setDefaultProvider(" ");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setDefaultProvider("openai");
    createDTO.setDefaultModel(" ");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setDefaultModel("gpt-4.1");
    createDTO.setToolsJson("{}");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    AgentDefinitionUpdateDTO updateDTO = new AgentDefinitionUpdateDTO();
    updateDTO.setDefaultProvider("openai");
    updateDTO.setDefaultModel("gpt-4.1");
    updateDTO.setSkillsJson("bad-json");
    assertThrows(
        IllegalArgumentException.class, () -> factory.newUpdateMutation("assistant-a", updateDTO));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newAgent(0L, 1L, factory.newCreateMutation(baseCreate())));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.apply(null, 1L, 2L, factory.newCreateMutation(baseCreate())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            factory.apply(new AgentDefinition(), 0L, 2L, factory.newCreateMutation(baseCreate())));
    assertThrows(
        IllegalArgumentException.class, () -> factory.apply(new AgentDefinition(), 1L, 2L, null));
  }

  private static AgentDefinitionCreateDTO baseCreate() {
    AgentDefinitionCreateDTO createDTO = new AgentDefinitionCreateDTO();
    createDTO.setName("assistant-a");
    createDTO.setDefaultProvider("openai");
    createDTO.setDefaultModel("gpt-4.1");
    return createDTO;
  }

  private static AgentDefinitionMutationFactory newFactory() {
    return new AgentDefinitionMutationFactory(new AgentEditableSupport(new ObjectMapper()));
  }
}
