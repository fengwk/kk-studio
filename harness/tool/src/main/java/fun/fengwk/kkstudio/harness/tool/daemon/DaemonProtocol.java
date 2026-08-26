package fun.fengwk.kkstudio.harness.tool.daemon;

/** Environment Daemon wire 协议的版本常量。 */
public final class DaemonProtocol {

  /** 尚未迁移生产执行链的 v3 协议（environmentName 作用域、resource 结果段和目录浏览控制面）。 */
  public static final int VERSION_3 = 3;

  /** capability INVOKE 使用 capabilityId wire 字段的下一版协议；HELLO/Runtime/Gateway 暂不切换。 */
  public static final int VERSION_4 = 4;

  /** ERROR payload 的可选 {@code code}：目标名称已被另一个 live daemon 持有，握手失败是终态的。 */
  public static final String ERROR_CODE_ENVIRONMENT_NAME_CONFLICT = "ENVIRONMENT_NAME_CONFLICT";

  private DaemonProtocol() {}
}
