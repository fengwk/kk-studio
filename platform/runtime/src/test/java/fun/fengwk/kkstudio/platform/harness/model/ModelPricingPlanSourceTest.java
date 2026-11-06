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
import fun.fengwk.kkstudio.platform.catalog.model.Model;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;

import java.nio.charset.StandardCharsets;
import java.util.Map;

class ModelPricingPlanSourceTest {

  private final ModelRepository repository = mock(ModelRepository.class);
  private final ModelPricingPlanSource source =
      new ModelPricingPlanSource(
          repository, new ModelRuntimeConfigParser(JsonMapper.builder().build()));

  /** 每次 attempt 读取当前模型，已冻结的计划不因后续配置变化而改变。 */
  @Test
  void freezesCurrentPricingForEachAttempt() {
    String config =
        """
        {"limit":{"context":1024,"output":256},
         "abilities":{"tools":true,"reasoning":false,"inputModalities":["TEXT"]},
         "defaultVariant":"default","variants":[{"id":"default","promptCacheRetention":"NONE"}],
         "pricing":{"overrides":[{"id":"fast","when":{"requestBody":{"path":"/speed","equals":"fast"}},
          "rates":{"input":"1"}}]}}
        """;
    when(repository.getByName("m"))
        .thenReturn(model(ModelKind.MODEL, config), model(ModelKind.MODEL, "{}"));

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
    when(repository.getByName("group")).thenReturn(model(ModelKind.GROUP, null));
    when(repository.getByName("invalid")).thenReturn(model(ModelKind.MODEL, "invalid"));
    assertFalse(source.plan("missing").hasConditions());
    assertFalse(source.plan("group").hasConditions());
    assertFalse(source.plan("invalid").hasConditions());
  }

  private static Model model(ModelKind kind, String config) {
    return Model.builder().kind(kind).configJson(config).build();
  }
}
