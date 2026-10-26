package fun.fengwk.kkstudio.ide.protocol;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

/**
 * 反向数据面单向 credit 窗口：初始 credit 固定 262144，发送方只有在未确认的 credit 不超过窗口时才可继续发送数据帧，接收方在实际消费完数据帧后才回 ACK。
 *
 * <p>每个数据帧（HTTP body 或 WS 分段）按 {@link #frameCost(int)} 计价：载荷字节数，但至少 {@link
 * #MIN_FRAME_CREDIT}。因此窗口同时约束在途字节与在途帧数（至多 {@link #MAX_IN_FLIGHT_FRAMES} 个），接收端按 credit
 * 推导出的帧数上界定界即可，合法的小帧突发不会撑爆接收队列；ACK 必须覆盖至少一个数据帧的代价。
 *
 * <p>该类型只表达「已发送但未确认」的有界窗口，不做排队：{@link #onSendFrame(int)} 超窗即 fail-closed（返回 false 由调用方 关闭流），{@link
 * #onAck(long)} 对超窗、不足一帧或负数 ACK 直接拒绝。禁止「先 ACK 后缓冲」：ACK 只能确认已真实消费的数据帧。
 *
 * <p>实例本身不做线程同步；调用方须在单个连接/交换的串行化上下文内使用（与 daemon gate/state 模型一致）。
 */
public final class IdeStreamCreditWindow {

  /** 双方初始 credit：262144。 */
  public static final int INITIAL_CREDIT = 262144;

  /** 每个数据帧至少消耗的 credit，零长度 WS 分段同样按此计价。 */
  public static final int MIN_FRAME_CREDIT = 512;

  /** 一个窗口内在途数据帧的上界。 */
  public static final int MAX_IN_FLIGHT_FRAMES = INITIAL_CREDIT / MIN_FRAME_CREDIT;

  private long outstanding;

  /** 一个载荷为 {@code payloadBytes} 的数据帧消耗的 credit。 */
  public static int frameCost(int payloadBytes) {
    if (payloadBytes < 0) {
      throw new IllegalArgumentException("payloadBytes must not be negative");
    }
    return Math.max(payloadBytes, MIN_FRAME_CREDIT);
  }

  /** 是否允许再发送一个载荷为 {@code payloadBytes} 的数据帧。 */
  public boolean canSendFrame(int payloadBytes) {
    return outstanding + frameCost(payloadBytes) <= INITIAL_CREDIT;
  }

  /**
   * 记录一个数据帧的实际发送。
   *
   * @return 是否在窗口内；false 表示超窗，调用方必须关闭该流而不是继续排队
   */
  public boolean onSendFrame(int payloadBytes) {
    if (!canSendFrame(payloadBytes)) {
      return false;
    }
    outstanding += frameCost(payloadBytes);
    return true;
  }

  /**
   * 处理对端 ACK：释放已确认消费的数据帧 credit。
   *
   * <p>ACK 超过未确认量、不足一个数据帧的最小代价或为负数，都说明对端协议错误，fail-closed 抛异常。
   */
  public void onAck(long credit) {
    if (credit < MIN_FRAME_CREDIT || credit > outstanding) {
      throw new DaemonProtocolException(
          "stream ACK " + credit + " is outside [" + MIN_FRAME_CREDIT + ", " + outstanding + "]");
    }
    outstanding -= credit;
  }

  /** 当前可发送数据帧的最大载荷：剩余 credit 不足一个最小帧时为 0。 */
  public long maxFramePayload() {
    long available = available();
    return available >= MIN_FRAME_CREDIT ? available : 0L;
  }

  /** 当前仍可使用的 credit。 */
  public long available() {
    return INITIAL_CREDIT - outstanding;
  }

  /** 当前已发送但未确认的 credit。 */
  public long outstanding() {
    return outstanding;
  }
}
