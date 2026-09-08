package fun.fengwk.kkstudio.platform.harness.model.provider;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;

/**
 * OpenAI Chat 与 Responses adapter 的 cache control 校验器。
 *
 * <p>AFFINITY 形态要求 SHORT retention 与空 breakpoints；NONE 不下发任何 hint。该类不依赖 LangChain4j 类型。
 */
final class CacheRequestValidator {

  private CacheRequestValidator() {}

  /**
   * 检查 {@link ProviderCacheControl} 与 OpenAI AFFINITY 形态一致：SHORT + 空 breakpoints，否则抛出 {@link
   * IllegalArgumentException}；NONE 视为合法空操作。
   *
   * @return 非 NONE 时返回 {@link ProviderCacheControl#affinityKey()}，NONE 时返回 {@code null}
   */
  static String requireOpenAiAffinity(ProviderCacheControl control) {
    if (control.retention() == PromptCacheRetention.NONE) {
      return null;
    }
    if (control.retention() != PromptCacheRetention.SHORT) {
      throw new IllegalArgumentException(
          "OpenAI prompt cache retention must be SHORT, got " + control.retention());
    }
    requireEmptyBreakpoints(control);
    return control.affinityKey();
  }

  private static void requireEmptyBreakpoints(ProviderCacheControl control) {
    if (!control.breakpoints().isEmpty()) {
      throw new IllegalArgumentException(
          "ProviderCacheControl must not declare breakpoints for this provider");
    }
  }
}
