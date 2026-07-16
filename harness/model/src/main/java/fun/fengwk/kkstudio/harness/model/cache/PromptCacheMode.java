package fun.fengwk.kkstudio.harness.model.cache;

/**
 * Provider 提示缓存能力的形态，决定 Adapter 与 harness 如何计算 cache key 并把缓存策略映射到 Provider 协议。
 *
 * <ul>
 *   <li>{@link #UNKNOWN}：Provider 尚未声明能力；harness 视为无缓存能力。
 *   <li>{@link #UNSUPPORTED}：Provider 明确不支持任何提示缓存；不允许启用任何 retention。
 *   <li>{@link #AUTOMATIC}：Provider 自行决定缓存命中；harness 不发送任何 cache hint，且需要 Provider 报告 cache
 *       使用量。
 *   <li>{@link #AFFINITY}：harness 通过 Provider 资源 ID 和会话前导内容派生 cache key（如 {@code prompt_cache_key}）。
 *       该 mode 不依赖显式 breakpoint。
 *   <li>{@link #BREAKPOINTS}：harness 在 system 和/或 tools 上显式打 cache_control 标记，Provider 据此控制缓存片段。
 * </ul>
 */
public enum PromptCacheMode {
  UNKNOWN,
  UNSUPPORTED,
  AUTOMATIC,
  AFFINITY,
  BREAKPOINTS
}
