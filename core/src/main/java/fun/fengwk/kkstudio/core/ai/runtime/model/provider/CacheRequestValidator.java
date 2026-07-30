package fun.fengwk.kkstudio.core.ai.runtime.model.provider;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;

/**
 * 四个 Provider 在把 {@link ProviderCacheControl} 翻译到 SDK 字段前，先经过的统一校验器。
 *
 * <p>校验规则与本地 NOTES 决策一致：
 *
 * <ul>
 *   <li>AFFINITY-only 形态（OpenAI Chat / OpenAI Responses）要求 retention 为 {@link
 *       PromptCacheRetention#SHORT}，且 breakpoints 必须为空；{@link PromptCacheRetention#LONG} 在 OpenAI
 *       协议下不可表达，{@link PromptCacheRetention#NONE} 不下发任何 hint。
 *   <li>BREAKPOINTS 形态（Anthropic）仅支持 {@link PromptCacheRetention#SHORT}，必须显式声明 非空
 *       breakpoints；任何未支持的 retention、未知 breakpoint 或与请求声明矛盾的控制都 fail fast， 避免错误 capability / usage
 *       进入账本。
 *   <li>AUTOMATIC 形态（Google）忽略整个 control，不下发任何 cache hint；NONE 视为合法空操作。
 * </ul>
 *
 * <p>该类不依赖任何 LangChain4j 类型，便于在不同 Adapter 内复用。
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

  /**
   * 把 Anthropic 控制拆成 {@code cacheSystemMessages} / {@code cacheTools} 两个 SDK 字段；NONE 视为全部 false；非
   * SHORT retention 或 breakpoints 与契约不符都会抛 {@link IllegalArgumentException}。
   *
   * @return record {@link AnthropicCacheFlags}；NONE 时两个字段都是 false
   */
  static AnthropicCacheFlags requireAnthropicBreakpoints(ProviderCacheControl control) {
    if (control.retention() == PromptCacheRetention.NONE) {
      return AnthropicCacheFlags.NONE;
    }
    if (control.retention() != PromptCacheRetention.SHORT) {
      throw new IllegalArgumentException(
          "Anthropic prompt cache retention must be SHORT, got " + control.retention());
    }
    boolean system = false;
    boolean tools = false;
    for (PromptCacheBreakpoint breakpoint : control.breakpoints()) {
      switch (breakpoint) {
        case SYSTEM -> system = true;
        case TOOLS -> tools = true;
      }
    }
    if (!system && !tools) {
      throw new IllegalArgumentException("Anthropic BREAKPOINTS requires at least SYSTEM or TOOLS");
    }
    return new AnthropicCacheFlags(system, tools);
  }

  private static void requireEmptyBreakpoints(ProviderCacheControl control) {
    if (!control.breakpoints().isEmpty()) {
      throw new IllegalArgumentException(
          "ProviderCacheControl must not declare breakpoints for this provider");
    }
  }

  /** Anthropic {@code cacheSystemMessages} / {@code cacheTools} 的落地结果。 */
  record AnthropicCacheFlags(boolean cacheSystemMessages, boolean cacheTools) {

    static final AnthropicCacheFlags NONE = new AnthropicCacheFlags(false, false);
  }
}
