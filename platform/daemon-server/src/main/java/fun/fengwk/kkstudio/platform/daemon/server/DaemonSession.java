package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;

/** 已建立的 Daemon 环境会话。 */
public interface DaemonSession {

  /**
   * 按控制流到达顺序接收并处理对端发送的信封。
   *
   * <p>入站信封已由传输层经 ControlProtocolCodec 解码校验，本方法只处理会话层语义校验与状态机流转。
   *
   * @param envelope 到达的信封（已由传输层经 ControlProtocolCodec 解码校验）
   */
  void receive(Envelope envelope);

  /** 主动关闭会话。 */
  void close();

  /** 底层传输已关闭时通知会话清理状态；该方法必须是幂等的。 */
  void closed();
}
