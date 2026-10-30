package fun.fengwk.kkstudio.platform.harness.model;

import fun.fengwk.kkstudio.harness.model.ModelRequestBodyPricing;

/** 按实际模型名提供 attempt 级 request-body 条件计划；实现必须在每次调用时返回独立计划。 */
@FunctionalInterface
interface ModelPricingPlanSource {

  ModelRequestBodyPricing.Plan plan(String modelName);
}
