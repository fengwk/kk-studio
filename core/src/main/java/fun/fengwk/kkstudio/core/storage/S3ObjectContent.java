package fun.fengwk.kkstudio.core.storage;

import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/**
 * 从固定 S3 bucket 读取的对象内容与元数据。
 *
 * @author fengwk
 */
@Getter
public final class S3ObjectContent {

  /** 对象内容字节：构造与 getter 均防御性拷贝，调用方修改不会影响内部状态。 */
  private final byte[] bytes;

  /** 对象 Content-Type（S3 GET 响应返回，可能为 null）。 */
  private final String contentType;

  public S3ObjectContent(byte[] bytes, String contentType) {
    this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    this.contentType = contentType;
  }

  public byte[] getBytes() {
    return Arrays.copyOf(bytes, bytes.length);
  }
}
