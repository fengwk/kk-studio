package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;

/** 传输层提供给环境会话核心的出站控制流通道。 */
public interface DaemonControlChannel {

  /**
   * 当前通道是否处于可用状态。
   *
   * @return 若通道可用且尚未关闭则返回 true
   */
  boolean isOpen();

  /**
   * 尝试向控制通道递交一条控制协议信封。
   *
   * @param envelope 待递交的信封
   * @return 递交结果
   */
  DaemonOfferResult offer(Envelope envelope);

  /** 触发通道在排空当前所有在途出站帧后优雅关闭。 */
  void closeAfterFlush();

  /** 立即强行关闭通道并丢弃未发送数据。 */
  void close();
}
