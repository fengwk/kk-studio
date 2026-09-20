package fun.fengwk.kkstudio.platform.plugin;

/**
 * 部署级凭据主密钥不可用：key file 缺失、非 owner-only、长度不是 32 bytes，或密文认证解密失败。
 *
 * <p>该状态下没有任何可用的读写路径，认证与刷新都 fail closed。
 */
public class PluginKeyUnavailableException extends PluginCredentialException {

  public PluginKeyUnavailableException(String message) {
    super(message);
  }

  public PluginKeyUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
