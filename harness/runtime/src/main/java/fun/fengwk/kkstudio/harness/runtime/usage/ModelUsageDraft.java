package fun.fengwk.kkstudio.harness.runtime.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import java.math.BigDecimal;
import java.util.Objects;

/** Provider 完成事实对应的不可变 usage ledger draft。 */
public record ModelUsageDraft(
    long providerResourceId,
    long modelResourceId,
    ProviderType providerType,
    String providerModelId,
    PromptCacheMode promptCacheMode,
    PromptCacheRetention promptCacheRetention,
    boolean cacheEligible,
    String cacheAffinityKey,
    ProviderStopReason stopReason,
    ModelUsage usage,
    ModelCost cost,
    ModelPricing pricing,
    String requestId,
    String reportedServiceTier,
    String rawUsageJson) {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String EMPTY_USAGE_JSON = "{}";

  public ModelUsageDraft {
    if (providerResourceId <= 0) {
      throw new IllegalArgumentException("providerResourceId must be positive");
    }
    if (modelResourceId <= 0) {
      throw new IllegalArgumentException("modelResourceId must be positive");
    }
    providerType = Objects.requireNonNull(providerType, "providerType");
    providerModelId = requireNonBlank(providerModelId, "providerModelId");
    promptCacheMode = Objects.requireNonNull(promptCacheMode, "promptCacheMode");
    promptCacheRetention = Objects.requireNonNull(promptCacheRetention, "promptCacheRetention");
    if (cacheAffinityKey != null && cacheAffinityKey.isBlank()) {
      throw new IllegalArgumentException("cacheAffinityKey must be null or non-blank");
    }
    if (promptCacheRetention == PromptCacheRetention.NONE && cacheAffinityKey != null) {
      throw new IllegalArgumentException("cacheAffinityKey must be null when retention is NONE");
    }
    if (cacheEligible != cacheEligible(promptCacheMode, promptCacheRetention)) {
      throw new IllegalArgumentException("cacheEligible does not match prompt cache facts");
    }
    stopReason = Objects.requireNonNull(stopReason, "stopReason");
    usage = Objects.requireNonNull(usage, "usage");
    cost = Objects.requireNonNull(cost, "cost");
    pricing = Objects.requireNonNull(pricing, "pricing");
    if (!pricing.currency().equals(cost.currency())) {
      throw new IllegalArgumentException("pricing.currency must equal cost.currency");
    }
    validateCost(cost, ModelCost.calculate(pricing, usage));
    requestId = optionalNonBlank(requestId, "requestId");
    reportedServiceTier = optionalNonBlank(reportedServiceTier, "reportedServiceTier");
    rawUsageJson = normalizeRawUsageJson(rawUsageJson);
  }

  /** 从 Provider 完成时绑定的最终请求和响应冻结一次 ledger 记录。 */
  public static ModelUsageDraft from(ProviderRequest finalRequest, ProviderResponse response) {
    Objects.requireNonNull(finalRequest, "finalRequest");
    Objects.requireNonNull(response, "response");
    ModelDescriptor model = finalRequest.model();
    ProviderCacheControl cacheControl = finalRequest.cacheControl();
    PromptCacheMode cacheMode = model.promptCachePolicy().capability().mode();
    PromptCacheRetention retention = cacheControl.retention();
    String affinityKey = retention == PromptCacheRetention.NONE ? null : cacheControl.affinityKey();
    return new ModelUsageDraft(
        model.providerResourceId(),
        model.modelResourceId(),
        model.providerType(),
        model.modelId(),
        cacheMode,
        retention,
        cacheEligible(cacheMode, retention),
        affinityKey,
        response.stopReason(),
        response.usage(),
        response.cost(),
        model.pricing(),
        response.requestId(),
        response.serviceTier(),
        response.rawUsageJson());
  }

  private static boolean cacheEligible(PromptCacheMode mode, PromptCacheRetention retention) {
    return mode == PromptCacheMode.AUTOMATIC
        || ((mode == PromptCacheMode.AFFINITY || mode == PromptCacheMode.BREAKPOINTS)
            && retention != PromptCacheRetention.NONE);
  }

  private static void validateCost(ModelCost actual, ModelCost expected) {
    requireSame(actual.input(), expected.input(), "input");
    requireSame(actual.output(), expected.output(), "output");
    requireSame(actual.cacheRead(), expected.cacheRead(), "cacheRead");
    requireSame(actual.cacheWrite(), expected.cacheWrite(), "cacheWrite");
    requireSame(actual.cacheWriteLong(), expected.cacheWriteLong(), "cacheWriteLong");
    requireSame(actual.reasoning(), expected.reasoning(), "reasoning");
    requireSame(actual.total(), expected.total(), "total");
  }

  private static void requireSame(BigDecimal actual, BigDecimal expected, String name) {
    if (actual.compareTo(expected) != 0) {
      throw new IllegalArgumentException("cost." + name + " must equal calculated model cost");
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String optionalNonBlank(String value, String name) {
    if (value != null && value.isBlank()) {
      throw new IllegalArgumentException(name + " must be null or non-blank");
    }
    return value;
  }

  private static String normalizeRawUsageJson(String value) {
    if (value == null) {
      return EMPTY_USAGE_JSON;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("rawUsageJson must contain JSON");
    }
    JsonNode node;
    try {
      node = OBJECT_MAPPER.readTree(trimmed);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("rawUsageJson must contain JSON", exception);
    }
    if (!node.isObject() && !node.isArray()) {
      throw new IllegalArgumentException("rawUsageJson must be a JSON object or array");
    }
    return trimmed;
  }
}
