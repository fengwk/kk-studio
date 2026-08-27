package fun.fengwk.kkstudio.harness.tool.daemon;

/** Environment Daemon wire 协议的版本常量。 */
public final class DaemonProtocol {

  /** 当前 Environment Daemon wire 协议版本。 */
  public static final int VERSION = 4;

  /** ERROR payload 的可选 {@code code}：目标名称已被另一个 live daemon 持有，握手失败是终态的。 */
  public static final String ERROR_CODE_ENVIRONMENT_NAME_CONFLICT = "ENVIRONMENT_NAME_CONFLICT";

  private DaemonProtocol() {}
}
