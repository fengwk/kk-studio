package fun.fengwk.kkstudio.harness.tool;

import java.util.Arrays;
import java.util.Objects;

/**
 * 内联二进制内容单元，用于远程终态结果的临时承载。
 *
 * <p>字节仅在内存中暂存；daemon COMPLETED 编码时先经 DaemonResourceStore 落盘，再以 resource 引用上 wire。 PARTIAL
 * 路径不得使用本类型。
 */
public record BinaryToolContent(String mediaType, byte[] content) implements ToolContent {

  public BinaryToolContent {
    if (mediaType == null || mediaType.isBlank()) {
      throw new IllegalArgumentException("mediaType must not be blank");
    }
    content = Objects.requireNonNull(content, "content").clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof BinaryToolContent that)) {
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
    return "BinaryToolContent[mediaType=" + mediaType + ", content.length=" + content.length + "]";
  }
}
