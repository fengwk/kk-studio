package fun.fengwk.kkstudio.ide.mux;

import java.util.concurrent.CompletionStage;

/**
 * 数据连接的物理传输窄端口。
 *
 * <p>{@link #write(byte[])} 必须异步完成且由 {@link MuxConnection} 串行调用；实现只负责把完整 binary message 交给底层
 * socket/WebSocket， 不得重试、合并或拆分帧。返回的 stage 异常完成表示底层写状态已损坏，连接会立即 fail-stop。
 */
public interface MuxTransport {

  /** 写入一条完整 wire 帧；返回 stage 完成表示该帧已交给物理传输。 */
  CompletionStage<Void> write(byte[] frame);

  /** 关闭底层连接；实现必须幂等，且不得再接受写入。 */
  void close();
}
