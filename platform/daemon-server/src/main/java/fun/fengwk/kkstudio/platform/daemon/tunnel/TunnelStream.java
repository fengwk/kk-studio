package fun.fengwk.kkstudio.platform.daemon.tunnel;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

/**
 * 一条可双向并发、但每个方向最多一个未完成操作的字节流。
 *
 * <p>未请求读取时不得无限预读。正常 EOF 用完成值为 {@code null} 表示，之后每次读取都完成 {@code null}。传输背压沿用标准协议窗口，本接口不提供第二套
 * credit。
 */
public interface TunnelStream extends AutoCloseable {

  /**
   * 读取下一段非空字节。
   *
   * <p>调用下一次读取表示调用方已消费上一段，不再使用其缓冲。
   *
   * @return 一段 {@code remaining > 0} 的字节；正常 EOF 及此后的读取完成 {@code null}
   * @throws IllegalStateException 同方向已有未完成读取
   */
  CompletionStage<ByteBuffer> read();

  /**
   * 写入 {@code source} 的全部剩余字节。
   *
   * <p>成功时把 position 推进到 limit。完成前调用方不得修改 {@code source}；完成后实现不再引用它。完成只表示本端已接收这些字节，不表示远端应用已经消费。
   *
   * @param source 待写字节，不能为 {@code null}；空缓冲不是有效写入
   * @return 全部剩余字节已被接收时完成
   * @throws IllegalArgumentException {@code source} 为 {@code null} 或没有剩余字节
   * @throws IllegalStateException 同方向已有未完成写入，或写方向已经关闭
   */
  CompletionStage<Void> write(ByteBuffer source);

  /**
   * 正常半关闭写方向并保留读方向。
   *
   * <p>完成顺序排在已有写入之后。重复调用幂等，返回同一次半关闭的完成结果。
   *
   * @return 写方向已关闭时完成
   */
  CompletionStage<Void> endOutput();

  /**
   * 取消双向流，使未完成操作异常结束并释放资源。
   *
   * <p>关闭幂等。尚未完成的读写以 {@link java.util.concurrent.CancellationException} 结束。
   */
  @Override
  void close();
}
