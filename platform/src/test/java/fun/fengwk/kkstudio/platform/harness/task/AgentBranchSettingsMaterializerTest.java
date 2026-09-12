package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;

/** 按最新 Agent/Model catalog 为子 Agent 物化 branch settings。 */
class AgentBranchSettingsMaterializerTest {

  private static final String WORKSPACE_PATH = "prod";

  private AgentDefinitionRepository agentRepository;
  private AgentModelRepository modelRepository;
  private AgentBranchSettingsMaterializer materializer;

  @BeforeEach
  void setUp() {
    agentRepository = mock(AgentDefinitionRepository.class);
    modelRepository = mock(AgentModelRepository.class);
    materializer =
        new AgentBranchSettingsMaterializer(
            agentRepository,
            modelRepository,
            new AgentModelRuntimeConfigParser(new ObjectMapper()));
  }

  private void stub(String agentName, String variant, String modelConfigJson) {
    AgentDefinition agent = new AgentDefinition();
    agent.setName(agentName);
    agent.setModelProviderName("openai");
    agent.setModelName("gpt-x");
    agent.setVariant(variant);
    when(agentRepository.getByName(agentName)).thenReturn(agent);

    AgentModel model = new AgentModel();
    model.setProviderName("openai");
    model.setName("gpt-x");
    model.setModelId("gpt-x-wire");
    model.setConfigJson(modelConfigJson);
    when(modelRepository.getByProviderNameAndName("openai", "gpt-x")).thenReturn(model);
  }

  /** 最新 catalog 的 Agent/model/variant 物化：默认 variant 由 model catalog 提供。 */
  @Test
  void materializesLatestAgentModelAndVariant() {
    stub("alpha", null, validModelConfig());

    BranchSettings settings = materializer.materialize("alpha", WORKSPACE_PATH);

    assertEquals(WORKSPACE_PATH, settings.workspacePath());
    assertEquals("alpha", settings.agentName());
    assertEquals(new ModelSelection("openai", "gpt-x", "quality"), settings.model());
  }

  /** Agent 显式 variant 覆盖默认 variant。 */
  @Test
  void honorsExplicitAgentVariantOverride() {
    stub("alpha", "fast", validModelConfig());

    BranchSettings settings = materializer.materialize("alpha", WORKSPACE_PATH);

    assertEquals(new ModelSelection("openai", "gpt-x", "fast"), settings.model());
  }

  /** Agent 缺失、model 缺失、variant 缺失与非法配置都以稳定 IllegalArgumentException 失败。 */
  @Test
  void rejectsMissingOrInvalidCatalogInputs() {
    when(agentRepository.getByName("ghost")).thenReturn(null);
    IllegalArgumentException missingAgent =
        assertThrows(
            IllegalArgumentException.class,
            () -> materializer.materialize("ghost", WORKSPACE_PATH));
    assertTrue(missingAgent.getMessage().contains("subagent not found: ghost"));

    stub("alpha", null, validModelConfig());
    when(modelRepository.getByProviderNameAndName("openai", "gpt-x")).thenReturn(null);
    IllegalArgumentException missingModel =
        assertThrows(
            IllegalArgumentException.class,
            () -> materializer.materialize("alpha", WORKSPACE_PATH));
    assertTrue(
        missingModel.getMessage().contains("subagent model not found: openai/gpt-x"),
        missingModel.getMessage());

    stub("alpha", "ghost-variant", validModelConfig());
    IllegalArgumentException missingVariant =
        assertThrows(
            IllegalArgumentException.class,
            () -> materializer.materialize("alpha", WORKSPACE_PATH));
    assertTrue(
        missingVariant
            .getMessage()
            .contains("subagent model variant not found: alpha variant=ghost-variant"),
        missingVariant.getMessage());
  }

  /** 持久化 model config 损坏时抛出带原因链的稳定异常入口。 */
  @Test
  void wrapsCorruptModelConfigsWithCauses() {
    stub("alpha", null, "not-json");
    IllegalArgumentException modelConfigError =
        assertThrows(
            IllegalArgumentException.class,
            () -> materializer.materialize("alpha", WORKSPACE_PATH));
    assertTrue(
        modelConfigError.getMessage().contains("invalid subagent model configuration: alpha"));
    assertInstanceOf(IllegalArgumentException.class, modelConfigError.getCause());
  }

  private static String validModelConfig() {
    return "{\"limit\":{\"context\":128000,\"output\":8192},"
        + "\"abilities\":{\"tools\":true,\"reasoning\":true,"
        + "\"inputModalities\":[\"TEXT\",\"IMAGE\"]},"
        + "\"defaultVariant\":\"quality\","
        + "\"variants\":[{\"id\":\"quality\",\"reasoningEffort\":\"high\"},"
        + "{\"id\":\"fast\",\"reasoningEffort\":\"off\"}],"
        + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"batch\","
        + "\"serviceTier\":\"priority\",\"serviceTierMultiplier\":1.25,"
        + "\"version\":\"2026-07-16\",\"inputPerMillionTokens\":1.1,"
        + "\"outputPerMillionTokens\":2.2,\"cacheReadPerMillionTokens\":0.3,"
        + "\"cacheWritePerMillionTokens\":0.4,"
        + "\"cacheWriteLongPerMillionTokens\":0.5,"
        + "\"reasoningPerMillionTokens\":3.6}}";
  }
}
