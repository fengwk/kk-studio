package fun.fengwk.kkstudio.harness.model.cache;

import java.util.Objects;

/**
 * harness 视角下某次请求要应用的缓存策略。
 *
 * <p>{@link PromptCachePolicy} 由 {@link PromptCacheCapability} 与具体 {@link PromptCacheRetention} 组成；
 * capability 必须在构造时支持目标 retention。该类只承担策略形态的固化，不直接映射到 Provider SDK；Provider cache 映射留待
 * Adapter 完成。
 */
public record PromptCachePolicy(PromptCacheCapability capability, PromptCacheRetention retention) {

  public PromptCachePolicy {
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(retention, "retention");
    if (!capability.supports(retention)) {
      throw new IllegalArgumentException(
          "retention " + retention + " is not supported by capability " + capability.mode());
    }
  }

  /** 工厂：禁用任何缓存策略；缺省值。 */
  public static PromptCachePolicy disabled() {
    return new PromptCachePolicy(
        PromptCacheCapability.unsupported(), PromptCacheRetention.NONE);
  }

  /**
   * 工厂：AUTOMATIC 模式下 Provider 自行决定缓存命中；capability 必须是 {@link PromptCacheMode#AUTOMATIC}，retention
   * 必须为 {@link PromptCacheRetention#NONE}。
   */
  public static PromptCachePolicy automatic(PromptCacheCapability capability) {
    requireMode(capability, PromptCacheMode.AUTOMATIC, "automatic");
    return new PromptCachePolicy(capability, PromptCacheRetention.NONE);
  }

  /**
   * 工厂：AFFINITY + SHORT 留存；capability 必须支持 AFFINITY + SHORT。
   */
  public static PromptCachePolicy affinityShort(PromptCacheCapability capability) {
    requireMode(capability, PromptCacheMode.AFFINITY, "affinityShort");
    return new PromptCachePolicy(capability, PromptCacheRetention.SHORT);
  }

  /**
   * 工厂：BREAKPOINTS + SHORT 留存；capability 必须支持 BREAKPOINTS + SHORT。
   */
  public static PromptCachePolicy breakpointsShort(PromptCacheCapability capability) {
    requireMode(capability, PromptCacheMode.BREAKPOINTS, "breakpointsShort");
    return new PromptCachePolicy(capability, PromptCacheRetention.SHORT);
  }

  /** 工厂：BREAKPOINTS + LONG 留存；capability 必须支持 BREAKPOINTS + LONG。 */
  public static PromptCachePolicy breakpointsLong(PromptCacheCapability capability) {
    requireMode(capability, PromptCacheMode.BREAKPOINTS, "breakpointsLong");
    return new PromptCachePolicy(capability, PromptCacheRetention.LONG);
  }

  /**
   * 工厂：自由组合 capability 与 retention；仅做 capability 的 supports 校验，不限制 mode 形态。
   *
   * <p>用于预设工厂未覆盖的场景，例如 AFFINITY + LONG。
   */
  public static PromptCachePolicy of(
      PromptCacheCapability capability, PromptCacheRetention retention) {
    return new PromptCachePolicy(capability, retention);
  }

  private static void requireMode(
      PromptCacheCapability capability, PromptCacheMode expected, String factory) {
    Objects.requireNonNull(capability, "capability");
    if (capability.mode() != expected) {
      throw new IllegalArgumentException(
          factory + " requires " + expected + " capability, got " + capability.mode());
    }
  }
}
