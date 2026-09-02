package fun.fengwk.kkstudio.harness.environment.daemon;

/** Environment Daemon wire 协议的版本与状态码常量。 */
public final class DaemonProtocol {

  /** 当前 Environment Daemon wire 协议版本。 */
  public static final int VERSION = 6;

  /**
   * ERROR payload 的可选 {@code code}：目标 Environment 当前已有活跃连接租约，daemon 应按配置退避重连 （同 registration token
   * 的其他实例稍后接管），而非终态失败。
   */
  public static final String ERROR_CODE_RETRY_LATER = "RETRY_LATER";

  /**
   * ERROR payload 的可选 {@code code}：Daemon 的 registration token 无效或被显式拒绝（终态失败）。daemon 收到后
   * 停止重连并以失败终止。
   */
  public static final String ERROR_CODE_REGISTRATION_REJECTED = "REGISTRATION_REJECTED";

  private DaemonProtocol() {}
}
