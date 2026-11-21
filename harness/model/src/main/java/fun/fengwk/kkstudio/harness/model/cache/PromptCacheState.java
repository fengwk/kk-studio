package fun.fengwk.kkstudio.harness.model.cache;

/**
 * 提示缓存的控制状态：回答 Studio 能否影响上游缓存，而不是缓存是否命中。
 *
 * <ul>
 *   <li>{@link #CONFIGURABLE}：协议与端点接受显式缓存意图，可发送的档位见 {@link PromptCacheOption}。
 *   <li>{@link #PROVIDER_MANAGED}：上游已确认自行管理缓存，Studio 不下发缓存意图。
 *   <li>{@link #UNMANAGED}：兼容端点行为未经验证，Studio 不控制，也不声称上游如何处理。
 *   <li>{@link #UNSUPPORTED}：已确认该路由不支持缓存控制或缓存本身；未经验证的路由不得归入此状态。
 * </ul>
 */
public enum PromptCacheState {
  CONFIGURABLE,
  PROVIDER_MANAGED,
  UNMANAGED,
  UNSUPPORTED
}
