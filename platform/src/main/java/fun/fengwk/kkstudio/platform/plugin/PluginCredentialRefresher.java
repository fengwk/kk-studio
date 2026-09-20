package fun.fengwk.kkstudio.platform.plugin;

/**
 * 凭据自动刷新：Plugin 用当前快照向自己的服务端换一次新凭据，并交还替换用的新材料。
 *
 * <p>实现必须最多发送一次外部请求、绝不自动重试，并按下述语义抛出异常，因为 Platform 只能据此区分终态：
 *
 * <ul>
 *   <li>{@link PluginAuthRejectedException}：服务端确定性拒绝当前凭据，必须重新登录；
 *   <li>{@link PluginRenewalNotSentException}：可以证明请求根本没有发出（例如本地校验失败），允许有界延迟重试；
 *   <li>其它任何异常：请求可能已经发出但结果未知，Platform 收敛为 {@code REFRESH_UNCERTAIN} 且永不重放。
 * </ul>
 *
 * <p>因此「连接被拒绝」「发送中途断连」「响应超时」都属于未知结果：调用方无法证明服务端没有签发新 token，不得把它们上报为未发送。
 */
public interface PluginCredentialRefresher {

  /** 用当前快照换取新凭据材料；返回材料的 region 必须与快照一致。 */
  PluginCredentialMaterial refresh(PluginCredentialSnapshot snapshot);
}
