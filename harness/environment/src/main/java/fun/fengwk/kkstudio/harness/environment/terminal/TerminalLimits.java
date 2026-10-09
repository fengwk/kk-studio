package fun.fengwk.kkstudio.harness.environment.terminal;

/**
 * 终端结构化画面协议的固定尺寸与预算常量。
 *
 * <p>这些值同时约束 {@link TerminalViewUpdate} 的模型校验与 {@link TerminalViewUpdateCodec} 的 wire 边界；本片
 * 不提供配置开关，也不存在随部署变化的第二组取值。
 */
public final class TerminalLimits {

  /** 活动屏最小列数。 */
  public static final int MIN_COLUMNS = 5;

  /** 活动屏最小行数。 */
  public static final int MIN_ROWS = 2;

  /** 活动屏最大列数。 */
  public static final int MAX_COLUMNS = 300;

  /** 活动屏最大行数。 */
  public static final int MAX_ROWS = 100;

  /** 单条消息允许携带的最大历史行数。 */
  public static final int MAX_HISTORY_LINES = 512;

  /** RESET/PATCH 编码后的 UTF-8 字节上限，与已生效的 notification 逻辑消息预算一致。 */
  public static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

  /** JS（浏览器）侧可精确表示的最大整数；版本、行 id 与 revision 都不超过它。 */
  public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  private TerminalLimits() {}
}
