package fun.fengwk.kkstudio.harness.environment.daemon;

/**
 * 反向数据面的二进制帧：byte1..byte5 判别字节 + 载荷。
 *
 * <ul>
 *   <li>1：HTTP body 分片；
 *   <li>2：HTTP END（半关闭；请求 END 不等于响应结束）；
 *   <li>3：增量 ACK 实际消费的 payload 字节数（unsigned big-endian uint32）；
 *   <li>4 / 5：WS text / binary 分段，第二字节是 FIN 标记（0=非末段，1=末段），之后是载荷。
 * </ul>
 */
public sealed interface IdeStreamBinaryFrame {

  record HttpBody(byte[] bytes) implements IdeStreamBinaryFrame {}

  record HttpEnd() implements IdeStreamBinaryFrame {}

  /** {@code byteCount} 为本次 ACK 确认实际消费的 payload 字节数（不含帧头）。 */
  record Ack(long byteCount) implements IdeStreamBinaryFrame {}

  /** {@code text=true} 对应 byte4（UTF-8 文本），{@code false} 对应 byte5（二进制）；{@code last} 为 WS FIN。 */
  record WsSegment(boolean text, boolean last, byte[] bytes) implements IdeStreamBinaryFrame {}
}
