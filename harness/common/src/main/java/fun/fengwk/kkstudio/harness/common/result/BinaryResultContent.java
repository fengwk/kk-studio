package fun.fengwk.kkstudio.harness.common.result;

import java.util.Arrays;
import java.util.Objects;

/**
 * 内联二进制结果内容单元，用于远程终态结果的临时承载。
 *
 * <p>字节仅在内存中暂存；终态持久化时先经持久化存储落盘，再以 resource 引用上 wire。
 */
public record BinaryResultContent(String mediaType, byte[] content) implements ResultContent {

  public BinaryResultContent {
    if (mediaType == null || mediaType.isBlank()) {
      throw new IllegalArgumentException("mediaType must not be blank");
    }
    content = Objects.requireNonNull(content, "content").clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  /** 原始字节长度；不复制、不物化，供尺寸校验直接使用。 */
  public int size() {
    return content.length;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof BinaryResultContent that)) {
      return false;
    }
    return mediaType.equals(that.mediaType) && Arrays.equals(content, that.content);
  }

  @Override
  public int hashCode() {
    return 31 * mediaType.hashCode() + Arrays.hashCode(content);
  }

  @Override
  public String toString() {
    return "BinaryResultContent[mediaType="
        + mediaType
        + ", content.length="
        + content.length
        + "]";
  }
}
