package fun.fengwk.kkstudio.platform.storage;

/**
 * 固定 bucket 中对象的服务端元数据。
 *
 * <p>{@code checksumSha256} 仅在对象存储以 checksum mode 返回时可用（base64 编码的原始 SHA-256 摘要）， 普通 HEAD 路径下为
 * {@code null}。
 *
 * @author fengwk
 */
public record S3ObjectMetadata(
    long contentLength, String contentType, String eTag, String checksumSha256) {

  public S3ObjectMetadata(long contentLength, String contentType, String eTag) {
    this(contentLength, contentType, eTag, null);
  }

  public S3ObjectMetadata {
    if (contentLength < 0L) {
      throw new IllegalArgumentException("contentLength must be >= 0");
    }
  }
}
