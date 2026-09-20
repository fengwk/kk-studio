package fun.fengwk.kkstudio.platform.plugin;

/**
 * 服务端确定性拒绝当前凭据：刷新收敛为 {@code REAUTH_REQUIRED}，该凭据不再自动重试。
 *
 * <p>Plugin 只有在收到明确的认证拒绝（例如 HTTP 401 或业务认证错误码）时才抛出本异常；超时、断连与未知结果都不是认证拒绝。
 */
public class PluginAuthRejectedException extends PluginCredentialException {

  public PluginAuthRejectedException(String message) {
    super(message);
  }

  public PluginAuthRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
