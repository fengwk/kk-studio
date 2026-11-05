package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.ModelPricingCondition;
import fun.fengwk.kkstudio.harness.model.ModelRequestBodyPricing;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.nio.charset.StandardCharsets;
import java.util.Map;

class ModelPricingPlanSourceTest {

  private final AgentModelRepository repository = mock(AgentModelRepository.class);
  private final ModelPricingPlanSource source =
      new ModelPricingPlanSource(
          repository, new AgentModelRuntimeConfigParser(JsonMapper.builder().build()));

  /** 每次 attempt 读取当前模型，已冻结的计划不因后续配置变化而改变。 */
  @Test
  void freezesCurrentPricingForEachAttempt() {
    String config =
        """
        {"limit":{"context":1024,"output":256},
         "abilities":{"tools":true,"reasoning":false,"inputModalities":["TEXT"]},
         "defaultVariant":"default","variants":[{"id":"default"}],
         "pricing":{"overrides":[{"id":"fast","when":{"requestBody":{"path":"/speed","equals":"fast"}},
          "rates":{"input":"1"}}]}}
        """;
    when(repository.getByName("m"))
        .thenReturn(model(AgentModelKind.MODEL, config), model(AgentModelKind.MODEL, "{}"));

    ModelRequestBodyPricing.Plan frozen = source.plan("m");
    assertTrue(frozen.hasConditions());
    String fingerprint =
        ModelRequestBodyPricing.fingerprint(
            ModelPricingCondition.RequestBody.stringValue("/speed", "fast"));
    assertFalse(source.plan("m").hasConditions());
    assertEquals(
        Map.of(fingerprint, true),
        frozen.evaluate("{\"speed\":\"fast\"}".getBytes(StandardCharsets.UTF_8)).matches());
  }

  /** 缺失、组类型和损坏配置不阻塞模型调用，均产生空计划。 */
  @Test
  void absentGroupAndInvalidConfigProduceEmptyPlans() {
    when(repository.getByName("group")).thenReturn(model(AgentModelKind.GROUP, null));
    when(repository.getByName("invalid")).thenReturn(model(AgentModelKind.MODEL, "invalid"));
    assertFalse(source.plan("missing").hasConditions());
    assertFalse(source.plan("group").hasConditions());
    assertFalse(source.plan("invalid").hasConditions());
  }

  private static AgentModel model(AgentModelKind kind, String config) {
    return AgentModel.builder().kind(kind).configJson(config).build();
  }
}
