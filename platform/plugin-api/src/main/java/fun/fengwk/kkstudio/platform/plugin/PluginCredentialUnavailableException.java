package fun.fengwk.kkstudio.platform.plugin;

import java.util.Objects;

/** Tool 侧凭据不可用的类型化错误：调用方据此给出确定性结果，绝不静默回退到过期凭据或重放刷新。 */
public class PluginCredentialUnavailableException extends PluginCredentialException {

  private final PluginCredentialUnavailableReason reason;

  public PluginCredentialUnavailableException(
      PluginCredentialUnavailableReason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public PluginCredentialUnavailableException(
      PluginCredentialUnavailableReason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public PluginCredentialUnavailableReason reason() {
    return reason;
  }
}
