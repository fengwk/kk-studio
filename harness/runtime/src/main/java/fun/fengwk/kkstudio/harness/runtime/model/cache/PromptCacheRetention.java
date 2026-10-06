package fun.fengwk.kkstudio.harness.runtime.model.cache;

/**
 * Provider 提示缓存的留存时长。
 *
 * <ul>
 *   <li>{@link #NONE}：不启用 Provider 端缓存，请求不携带 cache hint。
 *   <li>{@link #SHORT}：短留存（各 Provider 默认档位）。
 *   <li>{@link #LONG}：长留存；Provider 协议是否支持由 Adapter 决定。
 * </ul>
 */
public enum PromptCacheRetention {
  NONE,
  SHORT,
  LONG
}
