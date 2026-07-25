package fun.fengwk.kkstudio.harness.model.cache;

/**
 * Provider 提示缓存的留存时长。
 *
 * <p>{@link #NONE} 表示 harness 不启用任何缓存策略；这是唯一对所有 {@link PromptCacheMode} 都合法的保留档位。 其余档位仅在 {@link
 * PromptCacheMode#AFFINITY} 或 {@link PromptCacheMode#BREAKPOINTS} 下被允许。
 */
public enum PromptCacheRetention {
  NONE,
  SHORT,
  LONG
}
