package fun.fengwk.kkstudio.harness.model.limits;

/**
 * 文档内联限制（PLAN 11.1 文档列）。
 *
 * <p>口径全部为 RAW 字节：{@code singleRawBytes} 为单个文档原始字节，{@code totalRawBytes} 为一个请求全部文档的合计原始字节，{@code
 * maxPages} 为单个文档页数上限。全部可空，null 表示未知。
 *
 * @param singleRawBytes 单个文档 RAW 字节上限，或 null
 * @param totalRawBytes 单请求全部文档合计 RAW 字节上限，或 null
 * @param maxPages 单个文档页数上限，或 null
 */
public record DocumentRequestLimits(Long singleRawBytes, Long totalRawBytes, Integer maxPages) {

  /** 全字段未知的共享实例。 */
  public static final DocumentRequestLimits UNKNOWN = new DocumentRequestLimits(null, null, null);

  public DocumentRequestLimits {
    requirePositive(singleRawBytes, "singleRawBytes");
    requirePositive(totalRawBytes, "totalRawBytes");
    requirePositive(maxPages, "maxPages");
  }

  private static void requirePositive(Number value, String name) {
    if (value != null && value.longValue() <= 0) {
      throw new IllegalArgumentException(name + " must be positive when present");
    }
  }

  /** 全部字段都未知时返回 {@code true}。 */
  public boolean unknown() {
    return singleRawBytes == null && totalRawBytes == null && maxPages == null;
  }
}
