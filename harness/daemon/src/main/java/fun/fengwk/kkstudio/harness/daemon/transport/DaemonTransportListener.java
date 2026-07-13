package fun.fengwk.kkstudio.harness.daemon.transport;

/** Transport 向 Daemon Runtime 投递连接事件的回调。 */
public interface DaemonTransportListener {

  /** 接收一条完整的文本消息。 */
  void onMessage(String message);

  /** 连接被远端关闭、被本地关闭或因 I/O 失败而失效。 */
  void onDisconnected(Throwable cause);
}
