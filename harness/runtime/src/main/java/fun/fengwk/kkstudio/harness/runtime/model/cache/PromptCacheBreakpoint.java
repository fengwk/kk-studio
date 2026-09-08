package fun.fengwk.kkstudio.harness.runtime.model.cache;

/**
 * {@link PromptCacheMode#BREAKPOINTS} 模式下可显式打 cache 标记的请求前缀位置。
 *
 * <ul>
 *   <li>{@link #SYSTEM}：system 段（system prompt + 引导性 system 消息）作为缓存前缀。
 *   <li>{@link #TOOLS}：tool definitions 作为缓存前缀。
 *   <li>{@link #CONVERSATION}：最新合格非 SYSTEM 对话消息内容作为缓存前缀。
 * </ul>
 */
public enum PromptCacheBreakpoint {
  SYSTEM,
  TOOLS,
  CONVERSATION
}
