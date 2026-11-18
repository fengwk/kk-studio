package fun.fengwk.kkstudio.harness.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 固定三组定价契约不再携带通用 request-body 条件树。 */
class ModelRequestBodyPricingTest {

  @Test
  void producesNoRequestBodyPricingPlanForFixedRuleContract() {
    ModelRequestBodyPricing.Plan plan = ModelRequestBodyPricing.plan(ModelPricing.ZERO);

    assertFalse(plan.hasConditions());
    assertTrue(plan.evaluate("{}".getBytes()).matches().isEmpty());
  }
}
