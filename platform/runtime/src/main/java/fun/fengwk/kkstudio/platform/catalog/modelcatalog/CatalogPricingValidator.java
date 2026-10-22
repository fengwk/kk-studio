package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;

/**
 * 用唯一 pricing parser 校验目录候选；只有能完整保存/求值的价格才标记为 supported。
 *
 * <p>这里只做错误类型适配，不复制 DTO → {@code ModelPricing} 转换。
 */
final class CatalogPricingValidator {

  private CatalogPricingValidator() {}

  static void validate(AgentModelPricingDTO pricing) {
    try {
      AgentModelRuntimeConfigParser.parsePricing(pricing);
    } catch (IllegalArgumentException error) {
      String message = error.getMessage();
      throw new CatalogPriceException(
          message == null || message.isBlank() ? "invalid pricing definition" : message);
    }
  }
}
