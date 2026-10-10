package fun.fengwk.kkstudio.harness.environment.daemon;

/**
 * 反向数据面单向 credit 窗口：初始 credit 固定 262144 字节，发送方只有在未确认发送量不超过窗口时才可继续发送，接收方在 实际消费完字节后才回 ACK。
 *
 * <p>该类型只表达「已发送但未确认」的有界窗口，不做排队：{@link #onSend(int)} 超窗即 fail-closed（返回 false 由调用方 关闭流），{@link
 * #onAck(long)} 对超窗/负数 ACK 直接拒绝。禁止「先 ACK 后缓冲」：ACK 只能确认已真实消费的字节，因此这里 只允许 ACK 不超过当前未确认发送量。
 *
 * <p>实例本身不做线程同步；调用方须在单个连接/交换的串行化上下文内使用（与 daemon gate/state 模型一致）。
 */
public final class IdeStreamCreditWindow {

  /** 双方初始 credit：262144 字节。 */
  public static final int INITIAL_CREDIT = 262144;

  private long outstanding;

  /** 是否允许再发送 {@code bytes} 字节。 */
  public boolean canSend(int bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must not be negative");
    }
    return outstanding + bytes <= INITIAL_CREDIT;
  }

  /**
   * 记录一次实际发送。
   *
   * @return 是否在窗口内；false 表示超窗，调用方必须关闭该流而不是继续排队
   */
  public boolean onSend(int bytes) {
    if (!canSend(bytes)) {
      return false;
    }
    outstanding += bytes;
    return true;
  }

  /**
   * 处理对端 ACK：释放已确认消费的字节。
   *
   * <p>ACK 超过未确认发送量（含负数）说明对端协议错误，fail-closed 抛异常。
   */
  public void onAck(long bytes) {
    if (bytes < 0 || bytes > outstanding) {
      throw new DaemonProtocolException(
          "stream ACK " + bytes + " exceeds outstanding " + outstanding);
    }
    outstanding -= bytes;
  }

  /** 当前仍可发送的字节数。 */
  public long available() {
    return INITIAL_CREDIT - outstanding;
  }

  /** 当前已发送但未确认的字节数。 */
  public long outstanding() {
    return outstanding;
  }
}
