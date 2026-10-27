package fun.fengwk.kkstudio.ide.mux;

import java.util.Objects;

/**
 * 一个已接收的 DATA 片段。
 *
 * <p>对象本身不计费；连接在接收时已经占用流与连接 credit。下游必须真实消费后调用 {@link #consumed()}，且同一对象重复调用只生效一次。流被
 * RESET/关闭后，未消费片段由连接统一丢弃并归还连接级 credit，此时再调用 {@link #consumed()} 不会重复归还。
 */
public final class MuxReceivedData {

  private final MuxConnection connection;
  private final MuxStream stream;
  private final MuxDataKind kind;
  private final boolean fin;
  private final byte[] payload;
  private final int creditCost;
  private boolean consumed;

  MuxReceivedData(
      MuxConnection connection,
      MuxStream stream,
      MuxDataKind kind,
      boolean fin,
      byte[] payload,
      int creditCost) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.stream = Objects.requireNonNull(stream, "stream");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.fin = fin;
    this.payload = Objects.requireNonNull(payload, "payload");
    this.creditCost = creditCost;
  }

  /** 所属逻辑流。 */
  public MuxStream stream() {
    return stream;
  }

  /** HTTP / WS text / WS binary。 */
  public MuxDataKind kind() {
    return kind;
  }

  /** WS 消息是否在本片段结束；HTTP DATA 恒为 false。 */
  public boolean fin() {
    return fin;
  }

  /** 载荷字节；调用方不得修改。 */
  public byte[] payload() {
    return payload;
  }

  /** 本片段占用的 credit（{@code max(payloadLength, 512)}）。 */
  public int creditCost() {
    return creditCost;
  }

  /** 下游已真实消费该片段；幂等。 */
  public void consumed() {
    connection.consumed(this);
  }

  boolean isConsumed() {
    return consumed;
  }

  void markConsumed() {
    consumed = true;
  }
}
