package fun.fengwk.kkstudio.platform.cloudfs.blob;

import java.util.Arrays;
import java.util.Objects;

/** BLOB 节点或 Tool Artifact 权威内容的读取结果。 */
public sealed interface BlobReadResult {

  String mediaType();

  long sizeBytes();

  /** UTF-8 文本型 BLOB 解码结果。 */
  record Text(String text, String mediaType, long sizeBytes) implements BlobReadResult {
    public Text {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(mediaType, "mediaType");
    }
  }

  /** 图片或其他二进制媒体结果。 */
  record Binary(byte[] bytes, String mediaType, long sizeBytes) implements BlobReadResult {
    public Binary {
      Objects.requireNonNull(bytes, "bytes");
      Objects.requireNonNull(mediaType, "mediaType");
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Binary binary)) {
        return false;
      }
      return sizeBytes == binary.sizeBytes
          && Objects.equals(mediaType, binary.mediaType)
          && Arrays.equals(bytes, binary.bytes);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(mediaType, sizeBytes);
      result = 31 * result + Arrays.hashCode(bytes);
      return result;
    }
  }
}
