package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;

/** 已建立的 Daemon 环境会话。 */
public interface DaemonSession {

  /**
   * 按控制流到达顺序接收并处理对端发送的信封。
   *
   * @param envelope 到达的信封
   */
  void receive(Envelope envelope);

  /** 底层传输已关闭时通知会话清理状态；该方法必须是幂等的。 */
  void closed();
}
