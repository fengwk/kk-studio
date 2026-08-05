package fun.fengwk.kkstudio.harness.tool.daemon;

/** Environment Daemon wire 协议的当前版本。 */
public final class DaemonProtocol {

  /** 当前唯一受支持的 wire 协议版本（v2：environmentId 作用域 + resource 结果段）。 */
  public static final int VERSION_2 = 2;

  private DaemonProtocol() {}
}
