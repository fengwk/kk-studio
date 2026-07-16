package fun.fengwk.kkstudio.harness.model.cache;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Provider 请求要携带的最终缓存控制指令；这是 {@link PromptCachePolicy} 派发给 Provider Adapter 的不可变快照。
 *
 * <p>不变量：
 *
 * <ul>
 *   <li>{@link PromptCacheRetention#NONE} 时 {@code affinityKey} 必须为空且 {@code breakpoints} 为空。
 *   <li>非 NONE 时 {@code affinityKey} 必须非空白；{@code breakpoints} 为不可变集合。
 * </ul>
 */
public record ProviderCacheControl(
    PromptCacheRetention retention, String affinityKey, Set<PromptCacheBreakpoint> breakpoints) {

  public ProviderCacheControl {
    Objects.requireNonNull(retention, "retention");
    Objects.requireNonNull(breakpoints, "breakpoints");
    if (retention == PromptCacheRetention.NONE) {
      if (affinityKey != null && !affinityKey.isEmpty()) {
        throw new IllegalArgumentException("affinityKey must be null or empty when retention is NONE");
      }
      affinityKey = null;
      if (!breakpoints.isEmpty()) {
        throw new IllegalArgumentException("breakpoints must be empty when retention is NONE");
      }
      breakpoints = Set.of();
    } else {
      if (affinityKey == null || affinityKey.isBlank()) {
        throw new IllegalArgumentException("affinityKey must be non-blank when retention is " + retention);
      }
      breakpoints = immutable(breakpoints);
    }
  }

  /** 工厂：禁用 Provider 端缓存控制；breakpoints 视为空。 */
  public static ProviderCacheControl none() {
    return new ProviderCacheControl(PromptCacheRetention.NONE, null, Set.of());
  }

  /**
   * 工厂：AFFINITY 模式，harness 派生 affinityKey（Provider 资源 ID 与请求前缀的稳定哈希），breakpoints 留空。
   *
   * @param retention 不可为 NONE
   * @param affinityKey 非空白
   */
  public static ProviderCacheControl affinity(PromptCacheRetention retention, String affinityKey) {
    Objects.requireNonNull(retention, "retention");
    if (retention == PromptCacheRetention.NONE) {
      throw new IllegalArgumentException("affinity retention must not be NONE");
    }
    return new ProviderCacheControl(retention, requireKey(affinityKey), Set.of());
  }

  /**
   * 工厂：BREAKPOINTS 模式，在 system 和/或 tools 上显式打 cache_control 标记；affinityKey 仍作为前缀标识。
   *
   * @param retention 不可为 NONE
   * @param affinityKey 非空白
   * @param breakpoints 不可空集合
   */
  public static ProviderCacheControl breakpoints(
      PromptCacheRetention retention, String affinityKey, Set<PromptCacheBreakpoint> breakpoints) {
    Objects.requireNonNull(retention, "retention");
    if (retention == PromptCacheRetention.NONE) {
      throw new IllegalArgumentException("breakpoints retention must not be NONE");
    }
    if (breakpoints == null || breakpoints.isEmpty()) {
      throw new IllegalArgumentException("breakpoints must not be empty");
    }
    return new ProviderCacheControl(retention, requireKey(affinityKey), immutable(breakpoints));
  }

  private static String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("affinityKey must not be blank");
    }
    return key;
  }

  private static Set<PromptCacheBreakpoint> immutable(Set<PromptCacheBreakpoint> source) {
    Objects.requireNonNull(source, "breakpoints");
    if (source.isEmpty()) {
      return Set.of();
    }
    EnumSet<PromptCacheBreakpoint> copy = EnumSet.noneOf(PromptCacheBreakpoint.class);
    for (PromptCacheBreakpoint element : source) {
      Objects.requireNonNull(element, "breakpoints");
      copy.add(element);
    }
    return Collections.unmodifiableSet(copy);
  }
}
