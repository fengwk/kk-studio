package fun.fengwk.kkstudio.harness.model.cache;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 描述 Provider 模型支持的提示缓存能力快照。
 *
 * <p>{@code supportedRetentions} 与 {@code supportedBreakpoints} 都是不可变集合；任何 null 元素或空 mode 都会被规整为
 * {@link Collections#emptySet()}。下列不变量必须在构造时成立：
 *
 * <ul>
 *   <li>{@link PromptCacheRetention#NONE} 永远有效，harness 调用方无需在集合中显式声明。
 *   <li>{@link PromptCacheMode#UNKNOWN} / {@link PromptCacheMode#UNSUPPORTED} / {@link PromptCacheMode#AUTOMATIC} 的 retentions
 *       和 breakpoints 必须同时为空。
 *   <li>{@link PromptCacheMode#AFFINITY} 至少支持一个非 NONE 的 retention，且 breakpoints 必须为空。
 *   <li>{@link PromptCacheMode#BREAKPOINTS} 至少支持一个非 NONE 的 retention 和至少一个 breakpoint。
 * </ul>
 */
public record PromptCacheCapability(
    PromptCacheMode mode,
    Set<PromptCacheRetention> supportedRetentions,
    Set<PromptCacheBreakpoint> supportedBreakpoints) {

  public PromptCacheCapability {
    Objects.requireNonNull(mode, "mode");
    supportedRetentions =
        immutable(
            supportedRetentions, PromptCacheRetention.class, "supportedRetentions");
    supportedBreakpoints =
        immutable(
            supportedBreakpoints, PromptCacheBreakpoint.class, "supportedBreakpoints");
    validate(mode, supportedRetentions, supportedBreakpoints);
  }

  /** capability 是否允许在请求中携带给定 retention。NONE 永远视为合法保留档位。 */
  public boolean supports(PromptCacheRetention retention) {
    Objects.requireNonNull(retention, "retention");
    if (retention == PromptCacheRetention.NONE) {
      return true;
    }
    return supportedRetentions.contains(retention);
  }

  /** 工厂：Provider 尚未声明能力，harness 视为无缓存。 */
  public static PromptCacheCapability unknown() {
    return new PromptCacheCapability(PromptCacheMode.UNKNOWN, Set.of(), Set.of());
  }

  /** 工厂：Provider 明确不支持任何提示缓存。 */
  public static PromptCacheCapability unsupported() {
    return new PromptCacheCapability(PromptCacheMode.UNSUPPORTED, Set.of(), Set.of());
  }

  /**
   * 工厂：Provider 自行决定缓存命中，harness 不发送任何 cache hint；只有 {@link PromptCacheRetention#NONE} 合法。
   */
  public static PromptCacheCapability automatic() {
    return new PromptCacheCapability(PromptCacheMode.AUTOMATIC, Set.of(), Set.of());
  }

  /**
   * 工厂：harness 派生 cache key（如 {@code prompt_cache_key}）模式。
   *
   * @param supportedRetentions 至少包含一个非 NONE 的 retention
   */
  public static PromptCacheCapability affinity(Set<PromptCacheRetention> supportedRetentions) {
    return new PromptCacheCapability(PromptCacheMode.AFFINITY, supportedRetentions, Set.of());
  }

  /**
   * 工厂：harness 在 system 和/或 tools 上显式打 cache_control 标记模式。
   *
   * @param supportedRetentions 至少包含一个非 NONE 的 retention
   * @param supportedBreakpoints 至少包含一个 breakpoint
   */
  public static PromptCacheCapability breakpoints(
      Set<PromptCacheRetention> supportedRetentions,
      Set<PromptCacheBreakpoint> supportedBreakpoints) {
    return new PromptCacheCapability(
        PromptCacheMode.BREAKPOINTS, supportedRetentions, supportedBreakpoints);
  }

  private static <E extends Enum<E>> Set<E> immutable(
      Set<E> source, Class<E> elementType, String name) {
    Objects.requireNonNull(source, name);
    if (source.isEmpty()) {
      return Set.of();
    }
    EnumSet<E> copy = EnumSet.noneOf(elementType);
    for (E element : source) {
      Objects.requireNonNull(element, name);
      copy.add(element);
    }
    return Collections.unmodifiableSet(copy);
  }

  private static void validate(
      PromptCacheMode mode,
      Set<PromptCacheRetention> retentions,
      Set<PromptCacheBreakpoint> breakpoints) {
    if (mode == PromptCacheMode.UNKNOWN
        || mode == PromptCacheMode.UNSUPPORTED
        || mode == PromptCacheMode.AUTOMATIC) {
      if (!retentions.isEmpty()) {
        throw new IllegalArgumentException(mode + " capability must have empty retentions");
      }
      if (!breakpoints.isEmpty()) {
        throw new IllegalArgumentException(mode + " capability must have empty breakpoints");
      }
      return;
    }
    if (retentions.isEmpty()) {
      throw new IllegalArgumentException(mode + " capability requires at least one retention");
    }
    boolean hasNonNone = false;
    for (PromptCacheRetention retention : retentions) {
      if (retention != PromptCacheRetention.NONE) {
        hasNonNone = true;
        break;
      }
    }
    if (!hasNonNone) {
      throw new IllegalArgumentException(mode + " capability requires at least one non-NONE retention");
    }
    if (mode == PromptCacheMode.AFFINITY) {
      if (!breakpoints.isEmpty()) {
        throw new IllegalArgumentException("AFFINITY capability must not declare breakpoints");
      }
    } else if (mode == PromptCacheMode.BREAKPOINTS) {
      if (breakpoints.isEmpty()) {
        throw new IllegalArgumentException("BREAKPOINTS capability requires at least one breakpoint");
      }
    }
  }
}
