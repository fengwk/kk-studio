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

  private final byte[] bytes;
  private final String contentType;

  public S3ObjectContent(byte[] bytes, String contentType) {
    this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    this.contentType = contentType;
  }

  public byte[] getBytes() {
    return Arrays.copyOf(bytes, bytes.length);
  }
}
