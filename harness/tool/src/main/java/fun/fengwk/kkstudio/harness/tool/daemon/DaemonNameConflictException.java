package fun.fengwk.kkstudio.harness.tool.daemon;

/**
 * 终态握手冲突：HELLO 声称的 {@code EnvironmentName} 已被另一个 live 连接持有。
 *
 * <p>收到该 typed 协议错误的 daemon 必须停止重连并以非零状态退出；绝不能被当作瞬时网络失败重试。
 */
public final class DaemonNameConflictException extends DaemonProtocolException {

  public DaemonNameConflictException(String message) {
    super(message);
  }
}
