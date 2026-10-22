package fun.fengwk.kkstudio.harness.environment.server;

/**
 * 一次本地出站递交的确定性结果：只描述传输是否接受该帧，不描述对端是否已收到。
 *
 * <p>传输接受后帧进入自身的串行发送通道，其后真正的发送失败只体现为连接失效（{@link
 * fun.fengwk.kkstudio.harness.environment.server.DaemonChannel#isOpen()} 变为 false
 * 并由核心断开连接），不会回传给递交方。
 */
public enum DaemonOfferResult {

  /** 帧已交给传输的本地队列；连接可用，后续异步失败由连接失效表达。 */
  ACCEPTED,

  /** 本地队列容量或字节预算拒绝该帧：帧肯定未发送，连接仍然可用，调用方可按“未执行”处理。 */
  BUSY,

  /** 连接已关闭或已不可用：帧肯定未发送。 */
  CLOSED
}
