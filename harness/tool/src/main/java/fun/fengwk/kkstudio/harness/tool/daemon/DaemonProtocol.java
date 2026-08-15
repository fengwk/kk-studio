package fun.fengwk.kkstudio.harness.tool.daemon;

/** Environment Daemon wire 协议的当前版本。 */
public final class DaemonProtocol {

  /** 当前唯一受支持的 wire 协议版本（v3：environmentName 作用域 + resource 结果段 + 目录浏览控制面）。 */
  public static final int VERSION_3 = 3;

  /** ERROR payload 的可选 {@code code}：目标名称已被另一个 live daemon 持有，握手失败是终态的。 */
  public static final String ERROR_CODE_ENVIRONMENT_NAME_CONFLICT = "ENVIRONMENT_NAME_CONFLICT";

  private DaemonProtocol() {}
}
