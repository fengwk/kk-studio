package fun.fengwk.kkstudio.core.storage;

/** 固定 bucket 中对象的服务端元数据。 */
public record S3ObjectMetadata(long contentLength, String contentType, String eTag) {

  public S3ObjectMetadata {
    if (contentLength < 0L) {
      throw new IllegalArgumentException("contentLength must be >= 0");
    }
  }
}
