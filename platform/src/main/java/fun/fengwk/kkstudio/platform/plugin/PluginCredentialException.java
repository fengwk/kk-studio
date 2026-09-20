package fun.fengwk.kkstudio.platform.plugin;

/**
 * Plugin 凭据生命周期的类型化错误基类。
 *
 * <p>所有消息都必须是可安全进入日志与响应的有界文本：不得包含 access token、回调原文、密文或主密钥。
 */
public class PluginCredentialException extends RuntimeException {

  public PluginCredentialException(String message) {
    super(message);
  }

  public PluginCredentialException(String message, Throwable cause) {
    super(message, cause);
  }
}
