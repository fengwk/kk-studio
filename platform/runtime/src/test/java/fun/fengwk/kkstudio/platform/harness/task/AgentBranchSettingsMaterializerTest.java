package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.model.Model;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinAgentExecutionMaterializer;

/** 按最新 Agent/Model catalog 为子 Agent 物化 branch settings。 */
class AgentBranchSettingsMaterializerTest {

  private AgentRepository agentRepository;
  private ModelRepository modelRepository;
  private AgentBranchSettingsMaterializer materializer;

  @BeforeEach
  void setUp() {
    agentRepository = mock(AgentRepository.class);
    modelRepository = mock(ModelRepository.class);
    materializer =
        new AgentBranchSettingsMaterializer(
            agentRepository,
            new BuiltinAgentExecutionMaterializer(
                modelRepository, new ModelRuntimeConfigParser(JsonMapper.builder().build())));
  }

  private void stub(String agentName, String variant, String modelConfigJson) {
    Agent agent =
        Agent.builder()
            .name(agentName)
            .execution(BuiltinExecution.of(new ModelSelection("gpt-x", variant)))
            .configJson("{\"tools\":[],\"skills\":[],\"subagents\":[]}")
            .build();
    when(agentRepository.getByName(agentName)).thenReturn(agent);

    Model model =
        Model.builder()
            .kind(ModelKind.MODEL)
            .providerName("openai")
            .name("gpt-x")
            .modelId("gpt-x-wire")
            .configJson(modelConfigJson)
            .build();
    when(modelRepository.getByName("gpt-x")).thenReturn(model);
  }

  /** 最新 catalog 的 Agent/model/variant 物化：默认 variant 由 model catalog 提供。 */
  @Test
  void materializesLatestAgentModelAndVariant() {
    stub("alpha", null, validModelConfig());

    BranchSettings settings = materializer.materialize("alpha");

    // branch settings 只冻结 Agent 与 model selection：环境与目录都不进入历史。
    assertEquals("alpha", settings.agentName());
    assertEquals(BuiltinExecution.of(new ModelSelection("gpt-x", "quality")), settings.execution());
    // root/普通调用没有父调用，environmentName 恒为 null。
    assertNull(settings.environmentName());
  }

  /** Agent 显式 variant 覆盖默认 variant。 */
  @Test
  void honorsExplicitAgentVariantOverride() {
    stub("alpha", "fast", validModelConfig());

    BranchSettings settings = materializer.materialize("alpha");

    assertEquals(BuiltinExecution.of(new ModelSelection("gpt-x", "fast")), settings.execution());
  }

  /** GROUP 原样物化且不能携带组级 Variant。 */
  @Test
  void materializesGroupWithoutVariant() {
    stub("alpha", null, validModelConfig());
    when(modelRepository.getByName("gpt-x"))
        .thenReturn(Model.builder().name("gpt-x").kind(ModelKind.GROUP).build());
    assertEquals(
        BuiltinExecution.of(ModelSelection.group("gpt-x")),
        materializer.materialize("alpha").execution());
    when(agentRepository.getByName("alpha"))
        .thenReturn(
            Agent.builder()
                .name("alpha")
                .execution(BuiltinExecution.of(new ModelSelection("gpt-x", "fast")))
                .build());
    assertThrows(IllegalArgumentException.class, () -> materializer.materialize("alpha"));
  }

  /** 非 builtin execution 原样透传，不访问模型目录。 */
  @Test
  void passesThroughNonBuiltinExecution() {
    ExecutionSelection execution = new ExecutionSelection("custom", "{\"profile\":\"fast\"}");
    when(agentRepository.getByName("alpha"))
        .thenReturn(Agent.builder().name("alpha").execution(execution).build());

    BranchSettings settings = materializer.materialize("alpha");

    assertEquals("alpha", settings.agentName());
    assertEquals(execution, settings.execution());
    assertNull(settings.environmentName());
    verifyNoInteractions(modelRepository);
  }

  /** Agent 缺失、model 缺失、variant 缺失与非法配置都以稳定 IllegalArgumentException 失败。 */
  @Test
  void rejectsMissingOrInvalidCatalogInputs() {
    when(agentRepository.getByName("ghost")).thenReturn(null);
    IllegalArgumentException missingAgent =
        assertThrows(IllegalArgumentException.class, () -> materializer.materialize("ghost"));
    assertTrue(missingAgent.getMessage().contains("subagent not found: ghost"));

    stub("alpha", null, validModelConfig());
    when(modelRepository.getByName("gpt-x")).thenReturn(null);
    IllegalArgumentException missingModel =
        assertThrows(IllegalArgumentException.class, () -> materializer.materialize("alpha"));
    assertTrue(
        missingModel.getMessage().contains("subagent model not found: gpt-x"),
        missingModel.getMessage());

    stub("alpha", "ghost-variant", validModelConfig());
    IllegalArgumentException missingVariant =
        assertThrows(IllegalArgumentException.class, () -> materializer.materialize("alpha"));
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
        assertThrows(IllegalArgumentException.class, () -> materializer.materialize("alpha"));
    assertTrue(
        modelConfigError.getMessage().contains("invalid subagent model configuration: alpha"));
    assertInstanceOf(IllegalArgumentException.class, modelConfigError.getCause());
  }

  /** 测试意图：未配置 execution 的内置 Agent 被明确拒绝，而不是 NPE 或回退到其它模型。 */
  @Test
  void rejectsUnconfiguredAgentModelExplicitly() {
    Agent unconfigured =
        Agent.builder()
            .name("unconfigured-agent")
            .configJson("{\"tools\":[],\"skills\":[],\"subagents\":[]}")
            .build();
    when(agentRepository.getByName("unconfigured-agent")).thenReturn(unconfigured);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> materializer.materialize("unconfigured-agent"));
    assertTrue(
        error.getMessage().contains("agent execution is not configured: " + "unconfigured-agent"));
  }

  private static String validModelConfig() {
    return "{\"limit\":{\"context\":128000,\"output\":8192},"
        + "\"abilities\":{\"tools\":true,\"reasoning\":true,"
        + "\"inputModalities\":[\"TEXT\",\"IMAGE\"]},"
        + "\"defaultVariant\":\"quality\","
        + "\"variants\":[{\"id\":\"quality\",\"reasoningEffort\":\"high\",\"promptCacheRetention\":\"NONE\"},"
        + "{\"id\":\"fast\",\"reasoningEffort\":\"off\",\"promptCacheRetention\":\"NONE\"}],"
        + "\"pricing\":{\"base\":{\"input\":\"1.1\",\"output\":\"2.2\","
        + "\"cacheRead\":\"0.3\",\"cacheWrite\":\"0.4\",\"reasoning\":\"3.6\"},"
        + "\"overrides\":[],\"multipliers\":[]}}";
  }
}
