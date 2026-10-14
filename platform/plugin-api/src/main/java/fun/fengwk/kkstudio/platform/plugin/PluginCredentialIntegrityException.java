package fun.fengwk.kkstudio.platform.plugin;

/**
 * 密文 envelope 不可信：格式版本未知、长度不足、GCM tag 校验失败或 AAD 与 {@code pluginId/region/formatVersion} 不匹配。
 *
 * <p>调用方必须先报告 {@link PluginKeyUnavailableException}（状态投影为 {@code KEY_UNAVAILABLE}）语义，绝不复用或猜测其它
 * pluginId / region 的密文。
 */
public class PluginCredentialIntegrityException extends PluginCredentialException {

  public PluginCredentialIntegrityException(String message) {
    super(message);
  }

  public PluginCredentialIntegrityException(String message, Throwable cause) {
    super(message, cause);
  }
}
