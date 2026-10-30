package fun.fengwk.kkstudio.platform.harness.model;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelRequestBodyPricing;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.util.Objects;

/**
 * 一次 Model attempt 开始时按实际模型名读取当前 catalog pricing，并冻结 request-body 条件计划。
 *
 * <p>定价不是执行准入条件：模型缺失、不是单模型或当前配置不可解析时返回空计划，只让未知的 request-body 条件保持未知， 不阻止 Provider 调用。计划绝不跨 attempt
 * 复用，模型组回退或并发 fallback 各自读取自己的实际成员。
 */
@Component
final class DatabaseModelPricingPlanSource implements ModelPricingPlanSource {

  private final AgentModelRepository modelRepository;
  private final AgentModelRuntimeConfigParser configParser;

  DatabaseModelPricingPlanSource(
      AgentModelRepository modelRepository, AgentModelRuntimeConfigParser configParser) {
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.configParser = Objects.requireNonNull(configParser, "configParser");
  }

  @Override
  public ModelRequestBodyPricing.Plan plan(String modelName) {
    AgentModel model = modelRepository.getByName(modelName);
    if (model == null || model.kind() != AgentModelKind.MODEL) {
      return ModelRequestBodyPricing.plan(ModelPricing.ZERO);
    }
    try {
      return ModelRequestBodyPricing.plan(configParser.parse(model.configJson()).pricing());
    } catch (IllegalArgumentException invalidConfig) {
      return ModelRequestBodyPricing.plan(ModelPricing.ZERO);
    }
  }
}
