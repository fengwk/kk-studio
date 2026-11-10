package fun.fengwk.kkstudio.harness.model.limits;

/**
 * 图片内联限制（PLAN 11.1 图片列）。
 *
 * <p>口径：{@code singleRawBytes} 为单张图片 RAW 字节；{@code singleEncodedBytes} 为单张图片 Base64 编码后字节；{@code
 * totalBytes} 为一个请求全部图片的合计字节；{@code maxSide} 为单张边长像素上限；{@code manyImageThreshold}
 * 是「超过该数量时每张边长受限」的图片张数阈值（含历史与 tool_result 中的图片），{@code manyImageMaxSide} 是命中阈值后的每张边长上限。全部字段可空，null
 * 表示未知，未知即缺省、不补默认值。
 *
 * @param singleRawBytes 单张原始字节上限，或 null
 * @param singleEncodedBytes 单张 Base64 编码后字节上限，或 null
 * @param totalBytes 单请求全部图片合计字节上限，或 null
 * @param maxCount 单请求图片数量上限，或 null
 * @param maxSide 单张边长像素上限，或 null
 * @param manyImageThreshold 触发边长收紧的图片数量阈值，或 null
 * @param manyImageMaxSide 命中阈值后的单张边长像素上限，或 null
 */
public record ImageRequestLimits(
    Long singleRawBytes,
    Long singleEncodedBytes,
    Long totalBytes,
    Integer maxCount,
    Integer maxSide,
    Integer manyImageThreshold,
    Integer manyImageMaxSide) {

  /** 全字段未知的共享实例。 */
  public static final ImageRequestLimits UNKNOWN =
      new ImageRequestLimits(null, null, null, null, null, null, null);

  public ImageRequestLimits {
    requirePositive(singleRawBytes, "singleRawBytes");
    requirePositive(singleEncodedBytes, "singleEncodedBytes");
    requirePositive(totalBytes, "totalBytes");
    requirePositive(maxCount, "maxCount");
    requirePositive(maxSide, "maxSide");
    requirePositive(manyImageThreshold, "manyImageThreshold");
    requirePositive(manyImageMaxSide, "manyImageMaxSide");
  }

  private static void requirePositive(Number value, String name) {
    if (value != null && value.longValue() <= 0) {
      throw new IllegalArgumentException(name + " must be positive when present");
    }
  }

  /** 全部字段都未知时返回 {@code true}。 */
  public boolean unknown() {
    return singleRawBytes == null
        && singleEncodedBytes == null
        && totalBytes == null
        && maxCount == null
        && maxSide == null
        && manyImageThreshold == null
        && manyImageMaxSide == null;
  }
}
