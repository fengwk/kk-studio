package fun.fengwk.kkstudio.harness.environment.daemon;

/** Environment Daemon wire 协议的版本与状态码常量。 */
public final class DaemonProtocol {

  /** 当前 Environment Daemon wire 协议版本。 */
  public static final int VERSION = 5;

  /** ERROR payload 的可选 {@code code}：目标名称已被另一个 live daemon 持有，握手失败是终态的。 */
  public static final String ERROR_CODE_ENVIRONMENT_NAME_CONFLICT = "ENVIRONMENT_NAME_CONFLICT";

  /** ERROR payload 的可选 {@code code}：服务端临时不可用或忙，daemon 应按配置退避重连而非终态失败。 */
  public static final String ERROR_CODE_RETRY_LATER = "RETRY_LATER";

  private DaemonProtocol() {}
}
