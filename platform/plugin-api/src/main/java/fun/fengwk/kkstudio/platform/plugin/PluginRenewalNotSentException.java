package fun.fengwk.kkstudio.platform.plugin;

/**
 * 可以证明凭据刷新请求根本没有发出：刷新收敛为 {@code REFRESH_FAILED} 并允许有界延迟重试。
 *
 * <p>只有「尚未触碰网络」的失败（例如本地签名/编码校验不通过）才允许抛出本异常。连接被拒绝、发送中途断连、响应超时都无法证明服务端未签发新 token， 必须抛出普通异常，让 Platform
 * 收敛为 {@code REFRESH_UNCERTAIN} 且永不重放。
 */
public class PluginRenewalNotSentException extends PluginCredentialException {

  public PluginRenewalNotSentException(String message) {
    super(message);
  }

  public PluginRenewalNotSentException(String message, Throwable cause) {
    super(message, cause);
  }
}
