package fun.fengwk.kkstudio.platform.daemon;

/**
 * Daemon 下载已登记归档所需的预签名 GET 事实。
 *
 * <p>只包含发起下载所必需的信息：方法、已签名 URL 与签名过期 epoch 毫秒。归档的物理位置由服务端从 transferId 推导，绝不进入 wire。
 */
public record DaemonPresignedGet(String method, String url, long expiresAtEpochMs) {

  /** URL 的字符上限；与预签名 PUT 共用同一 wire 限额。 */
  public static final int MAX_URL_CHARS = DaemonPresignedPut.MAX_URL_CHARS;

  public DaemonPresignedGet {
    if (!"GET".equals(method)) {
      throw new IllegalArgumentException("method must be GET");
    }
    if (url == null || url.isBlank() || url.length() > MAX_URL_CHARS) {
      throw new IllegalArgumentException("url must be non-blank and within the URL limit");
    }
    if (expiresAtEpochMs < 0) {
      throw new IllegalArgumentException("expiresAtEpochMs must not be negative");
    }
  }
}
