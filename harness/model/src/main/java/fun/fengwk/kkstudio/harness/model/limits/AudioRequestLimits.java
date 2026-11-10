package fun.fengwk.kkstudio.harness.model.limits;

/**
 * 音频内联限制（PLAN 11.1 audio 列）。
 *
 * <p>口径：{@code singleRawBytes} 为单个音频 RAW 字节；{@code maxCount} 为单请求音频数量上限；{@code maxDurationSeconds}
 * 为单个音频时长上限。全部可空，null 表示未知。
 *
 * @param singleRawBytes 单个音频 RAW 字节上限，或 null
 * @param maxCount 单请求音频数量上限，或 null
 * @param maxDurationSeconds 单个音频时长（秒）上限，或 null
 */
public record AudioRequestLimits(Long singleRawBytes, Integer maxCount, Long maxDurationSeconds) {

  /** 全字段未知的共享实例。 */
  public static final AudioRequestLimits UNKNOWN = new AudioRequestLimits(null, null, null);

  public AudioRequestLimits {
    requirePositive(singleRawBytes, "singleRawBytes");
    requirePositive(maxCount, "maxCount");
    requirePositive(maxDurationSeconds, "maxDurationSeconds");
  }

  private static void requirePositive(Number value, String name) {
    if (value != null && value.longValue() <= 0) {
      throw new IllegalArgumentException(name + " must be positive when present");
    }
  }

  /** 全部字段都未知时返回 {@code true}。 */
  public boolean unknown() {
    return singleRawBytes == null && maxCount == null && maxDurationSeconds == null;
  }
}
