package fun.fengwk.kkstudio.harness.environment.daemon;

/** Environment Daemon wire 协议的版本与状态码常量。 */
public final class DaemonProtocol {

  /**
   * 当前 Environment Daemon wire 协议版本。
   *
   * <p>本版本只有固定的五个 envelope 字段（{@code protocolVersion}、{@code messageType}、nullable {@code
   * environmentId}、nullable {@code invocationId}、{@code payload}）、HELLO 携带的 {@code
   * daemonInstanceId} 与 manifest {@code daemonVersion} 上报、以及强制协商的 {@code permessage-deflate}。人工
   * shell 控制/事件复用 {@link DaemonMessageType#SHELL_COMMAND}/{@link
   * DaemonMessageType#SHELL_EVENT}：两者都要求非空 Environment scope、禁止 invocationId，payload 直接是终端控制
   * Request/Response 对象。其他版本帧一律拒绝，不做双协议 fallback。
   */
  public static final int VERSION = 3;

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
