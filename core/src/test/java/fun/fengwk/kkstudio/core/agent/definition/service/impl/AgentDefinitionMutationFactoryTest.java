package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;

import java.util.Arrays;
import java.util.List;

/** Structured definition config is normalized before it is stored. */
public class AgentDefinitionMutationFactoryTest {

  @Test
  public void shouldNormalizeListsAndPersistTypedPolicy() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(8);
    policy.setMaxDepth(3);
    policy.setIdleTimeoutMillis(1_000L);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(Arrays.asList(" browser ", "browser", "", null));
    config.setSkills(Arrays.asList(" java ", "java"));
    config.setAllowedSubagents(Arrays.asList(" reviewer ", "reviewer"));
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setConfig(config);

    AgentDefinition definition = factory.newAgent(2L, create);
    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class);

    assertEquals(List.of("browser"), stored.getTools());
    assertEquals(List.of("java"), stored.getSkills());
    assertEquals(List.of("reviewer"), stored.getAllowedSubagents());
    assertEquals(8, stored.getExecutionPolicy().getMaxTurns());
    assertEquals(3, stored.getExecutionPolicy().getMaxDepth());
    assertEquals(1_000L, stored.getExecutionPolicy().getIdleTimeoutMillis());
  }

  @Test
  public void shouldDefaultAndPatchDefinitionConfiguration() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    AgentDefinition definition = factory.newAgent(2L, create);
    assertEquals("default", definition.getVariant());

    String originalConfig = definition.getConfigJson();
    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setDescription("updated");
    factory.update(definition, update);
    assertEquals("agent", definition.getName());
    assertEquals(originalConfig, definition.getConfigJson());
    assertEquals(
        List.of(),
        objectMapper
            .readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class)
            .getAllowedSubagents());
  }

  @Test
  public void shouldRejectInvalidDefinitionConfiguration() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    assertThrows(
        IllegalArgumentException.class, () -> factory.newAgent(0L, new AgentDefinitionCreateDTO()));
    assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, null));

    AgentDefinitionCreateDTO blank = new AgentDefinitionCreateDTO();
    blank.setName(" ");
    assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, blank));

    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(0);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setConfig(config);
    assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, create));
  }

  /** steering/followUp 缺失时持久化为 null，由 frozen snapshot decode 决定默认值，不机械写入 DTO。 */
  @Test
  public void shouldLeaveControlModesUnsetWhenAbsent() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    AgentDefinition definition = factory.newAgent(2L, create);

    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class);
    assertEquals(null, stored.getExecutionPolicy().getSteeringMode());
    assertEquals(null, stored.getExecutionPolicy().getFollowUpMode());
  }

  /** 合法控制模式值必须原样持久化。 */
  @Test
  public void shouldPersistValidControlModes() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setSteeringMode("ALL");
    policy.setFollowUpMode("ONE_AT_A_TIME");
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setConfig(config);
    AgentDefinition definition = factory.newAgent(2L, create);

    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class);
    assertEquals("ALL", stored.getExecutionPolicy().getSteeringMode());
    assertEquals("ONE_AT_A_TIME", stored.getExecutionPolicy().getFollowUpMode());
  }

  /** 控制模式值非法时必须报错且字段名清楚。 */
  @Test
  public void shouldRejectInvalidControlModes() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());

    AgentExecutionPolicyDTO badSteering = new AgentExecutionPolicyDTO();
    badSteering.setSteeringMode("WHENEVER");
    AgentDefinitionConfigDTO badSteeringConfig = new AgentDefinitionConfigDTO();
    badSteeringConfig.setExecutionPolicy(badSteering);
    AgentDefinitionCreateDTO badSteeringCreate = new AgentDefinitionCreateDTO();
    badSteeringCreate.setName("agent");
    badSteeringCreate.setConfig(badSteeringConfig);
    IllegalArgumentException steeringException =
        assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, badSteeringCreate));
    assertEquals(
        "executionPolicy.steeringMode must be one of ONE_AT_A_TIME/ALL but was: WHENEVER",
        steeringException.getMessage());

    AgentExecutionPolicyDTO badFollowUp = new AgentExecutionPolicyDTO();
    badFollowUp.setFollowUpMode("WHENEVER");
    AgentDefinitionConfigDTO badFollowUpConfig = new AgentDefinitionConfigDTO();
    badFollowUpConfig.setExecutionPolicy(badFollowUp);
    AgentDefinitionCreateDTO badFollowUpCreate = new AgentDefinitionCreateDTO();
    badFollowUpCreate.setName("agent");
    badFollowUpCreate.setConfig(badFollowUpConfig);
    IllegalArgumentException followUpException =
        assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, badFollowUpCreate));
    assertEquals(
        "executionPolicy.followUpMode must be one of ONE_AT_A_TIME/ALL but was: WHENEVER",
        followUpException.getMessage());
  }

  /** 控制模式非字符串空白时也明确报错。 */
  @Test
  public void shouldRejectBlankControlModes() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setSteeringMode("   ");
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setExecutionPolicy(policy);
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setConfig(config);
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> factory.newAgent(2L, create));
    assertEquals(
        "executionPolicy.steeringMode must not be blank when present", exception.getMessage());
  }

  private AgentDefinitionMutationFactory factory(ObjectMapper objectMapper) {
    return new AgentDefinitionMutationFactory(new AgentEditableSupport(objectMapper), objectMapper);
  }
}
