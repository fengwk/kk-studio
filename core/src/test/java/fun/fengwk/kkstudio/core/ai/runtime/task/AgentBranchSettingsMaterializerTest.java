package fun.fengwk.kkstudio.core.ai.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.testing.TestEnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;

import java.time.Duration;
import java.util.List;

/** 按最新 Agent/Model catalog 为子 Agent 物化 branch settings。 */
class AgentBranchSettingsMaterializerTest {

  private static final EnvironmentBinding ENV = TestEnvironmentBindings.binding("prod");
  private static final int MAX_DEPTH = 2;

  private AgentDefinitionRepository agentRepository;
  private AgentModelRepository modelRepository;
  private AgentBranchSettingsMaterializer materializer;
  private SubagentConfig config;

  @BeforeEach
  void setUp() {
    agentRepository = mock(AgentDefinitionRepository.class);
    modelRepository = mock(AgentModelRepository.class);
    materializer =
        new AgentBranchSettingsMaterializer(
            agentRepository,
            modelRepository,
            new AgentDefinitionConfigCodec(new ObjectMapper()),
            new AgentModelRuntimeConfigParser(new ObjectMapper()));
    config = new SubagentConfig(MAX_DEPTH, 10, null, Duration.ZERO, 50);
  }

  private void stub(
      String agentName, String variant, String agentConfigJson, String modelConfigJson) {
    AgentDefinition agent = new AgentDefinition();
    agent.setName(agentName);
    agent.setModelProviderName("openai");
    agent.setModelName("gpt-x");
    agent.setVariant(variant);
    agent.setConfigJson(agentConfigJson);
    when(agentRepository.getByName(agentName)).thenReturn(agent);

    AgentModel model = new AgentModel();
    model.setProviderName("openai");
    model.setName("gpt-x");
    model.setConfigJson(modelConfigJson);
    when(modelRepository.getByProviderNameAndName("openai", "gpt-x")).thenReturn(model);
  }

  /** 最新 catalog 的 Agent/model/variant 物化：默认 variant、skills 自动补 load_skill、深度内补 task。 */
  @Test
  void materializesLatestAgentModelAndVariant() {
    stub(
        "alpha",
        null,
        "{\"tools\":[\"get_goal\"],\"skills\":[\"math\"],\"subagents\":[\"beta\"]}",
        validModelConfig());

    BranchSettings settings = materializer.materialize("alpha", ENV, 1, config);

    assertEquals(ENV, settings.environment());
    assertEquals("alpha", settings.agentName());
    assertEquals(new ModelSelection("openai", "gpt-x", "quality"), settings.model());
    assertEquals(List.of("get_goal", "load_skill", "task"), settings.activeTools());
  }

  /** 达到 maxDepth 时不再自动添加 task；无 skills 时不添加 load_skill。 */
  @Test
  void stopsAddingTaskAtMaxDepth() {
    stub(
        "alpha",
        null,
        "{\"tools\":[\"get_goal\"],\"skills\":[],\"subagents\":[\"beta\"]}",
        validModelConfig());

    BranchSettings atLimit = materializer.materialize("alpha", ENV, MAX_DEPTH, config);
    assertEquals(List.of("get_goal"), atLimit.activeTools());
    assertFalse(atLimit.activeTools().contains(TaskTool.NAME));

    BranchSettings beyond = materializer.materialize("alpha", ENV, MAX_DEPTH + 1, config);
    assertFalse(beyond.activeTools().contains(TaskTool.NAME));
  }

  /** Agent 显式 variant 覆盖默认 variant。 */
  @Test
  void honorsExplicitAgentVariantOverride() {
    stub("alpha", "fast", "{\"tools\":[],\"skills\":[],\"subagents\":[]}", validModelConfig());

    BranchSettings settings = materializer.materialize("alpha", ENV, 1, config);

    assertEquals(new ModelSelection("openai", "gpt-x", "fast"), settings.model());
  }

  /** Agent 缺失、model 缺失、variant 缺失与非法配置都以稳定 IllegalArgumentException 失败。 */
  @Test
  void rejectsMissingOrInvalidCatalogInputs() {
    when(agentRepository.getByName("ghost")).thenReturn(null);
    IllegalArgumentException missingAgent =
        assertThrows(
            IllegalArgumentException.class,
            () -> materializer.materialize("ghost", ENV, 1, config));
    assertTrue(missingAgent.getMessage().contains("subagent not found: ghost"));

    stub("alpha", null, "{\"tools\":[],\"skills\":[],\"subagents\":[]}", validModelConfig());
    when(modelRepository.getByProviderNameAndName("openai", "gpt-x")).thenReturn(null);
    IllegalArgumentException missingModel =
        assertThrows(
            IllegalArgumentException.class,
            () -> materializer.materialize("alpha", ENV, 1, config));
    assertTrue(
        missingModel.getMessage().contains("subagent model not found: openai/gpt-x"),
        missingModel.getMessage());

    stub(
        "alpha",
        "ghost-variant",
        "{\"tools\":[],\"skills\":[],\"subagents\":[]}",
        validModelConfig());
    IllegalArgumentException missingVariant =
        assertThrows(
            IllegalArgumentException.class,
            () -> materializer.materialize("alpha", ENV, 1, config));
    assertTrue(
        missingVariant
            .getMessage()
            .contains("subagent model variant not found: alpha variant=ghost-variant"),
        missingVariant.getMessage());
  }

  /** 持久化 config JSON 损坏时抛出带原因链的稳定异常入口。 */
  @Test
  void wrapsCorruptPersistedConfigsWithCauses() {
    stub("alpha", null, "not-json", validModelConfig());
    IllegalArgumentException agentConfigError =
        assertThrows(
            IllegalArgumentException.class,
            () -> materializer.materialize("alpha", ENV, 1, config));
    assertTrue(agentConfigError.getMessage().contains("invalid subagent configuration: alpha"));
    assertInstanceOf(IllegalStateException.class, agentConfigError.getCause());

    stub("alpha", null, "{\"tools\":[],\"skills\":[],\"subagents\":[]}", "not-json");
    IllegalArgumentException modelConfigError =
        assertThrows(
            IllegalArgumentException.class,
            () -> materializer.materialize("alpha", ENV, 1, config));
    assertTrue(
        modelConfigError.getMessage().contains("invalid subagent model configuration: alpha"));
    assertInstanceOf(IllegalArgumentException.class, modelConfigError.getCause());
  }

  private static String validModelConfig() {
    return "{\"limit\":{\"context\":128000,\"output\":8192},"
        + "\"abilities\":{\"tools\":true,\"reasoning\":true,"
        + "\"inputModalities\":[\"TEXT\",\"IMAGE\"]},"
        + "\"defaultVariant\":\"quality\","
        + "\"variants\":[{\"id\":\"quality\",\"maxOutputTokens\":4096,"
        + "\"temperature\":0.4,\"topP\":0.8,\"topK\":20,"
        + "\"frequencyPenalty\":0.1,\"presencePenalty\":0.2,"
        + "\"stopSequences\":[\"done\"],\"reasoningEffort\":\"high\"},"
        + "{\"id\":\"fast\",\"maxOutputTokens\":2048,"
        + "\"temperature\":0.8,\"reasoningEffort\":\"off\"}],"
        + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"batch\","
        + "\"serviceTier\":\"priority\",\"serviceTierMultiplier\":1.25,"
        + "\"version\":\"2026-07-16\",\"inputPerMillionTokens\":1.1,"
        + "\"outputPerMillionTokens\":2.2,\"cacheReadPerMillionTokens\":0.3,"
        + "\"cacheWritePerMillionTokens\":0.4,"
        + "\"cacheWriteLongPerMillionTokens\":0.5,"
        + "\"reasoningPerMillionTokens\":3.6}}";
  }
}
