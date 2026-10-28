package fun.fengwk.kkstudio.ide.mux;

/** IDE 数据 wire v2 的固定资源预算与长度限制。 */
public final class MuxLimits {

  /** 当前 wire 版本。 */
  public static final int WIRE_VERSION = 2;

  /** 每帧固定头长度。 */
  public static final int HEADER_BYTES = 12;

  /** 单帧 payload 上限。 */
  public static final int MAX_PAYLOAD_BYTES = 65536;

  /** OPEN / HEADERS / RESET 元数据 JSON 上限。 */
  public static final int MAX_JSON_BYTES = 16 * 1024;

  /** 单条数据连接最多并发流数。 */
  public static final int MAX_STREAMS = 64;

  /** 每流每方向初始 credit。 */
  public static final int INITIAL_STREAM_CREDIT = 256 * 1024;

  /** 整连接每方向初始 credit。 */
  public static final int INITIAL_CONNECTION_CREDIT = 4 * 1024 * 1024;

  /** 每个 DATA 的最小 credit 成本。 */
  public static final int MIN_DATA_CREDIT = 512;

  /** 累计消费至少达到该值，或接收队列排空时归还 credit。 */
  public static final int WINDOW_ACK_THRESHOLD = 64 * 1024;

  /** 管理帧队列长度上限。 */
  public static final int MAX_MANAGEMENT_FRAMES = 256;

  /** 管理帧队列字节上限。 */
  public static final int MAX_MANAGEMENT_BYTES = 256 * 1024;

  /** OPEN 携带的 header 条数上限。 */
  public static final int MAX_OPEN_HEADERS = 100;

  /** HTTP method token 长度上限。 */
  public static final int MAX_METHOD_CHARS = 32;

  /** 请求路径的 UTF-8 字节上限。 */
  public static final int MAX_PATH_BYTES = 8192;

  /** RESET code 长度上限。 */
  public static final int MAX_RESET_CODE_CHARS = 64;

  /** RESET message 的 UTF-16 code unit 上限。 */
  public static final int MAX_RESET_MESSAGE_CHARS = 500;

  /** CLOSE reason 的 UTF-8 字节上限。 */
  public static final int MAX_CLOSE_REASON_BYTES = 123;

  private MuxLimits() {}

  /** 一个 DATA 的成本：载荷长度与最小帧成本取较大值。 */
  public static int dataCost(int payloadBytes) {
    if (payloadBytes < 0) {
      throw new IllegalArgumentException("payloadBytes must not be negative");
    }
    return Math.max(payloadBytes, MIN_DATA_CREDIT);
  }
}
