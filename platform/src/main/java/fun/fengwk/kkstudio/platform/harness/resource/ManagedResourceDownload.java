package fun.fengwk.kkstudio.platform.harness.resource;

import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/** 由 Platform 重建并读取的 managed Resource 下载结果。 */
@Getter
public final class ManagedResourceDownload {

  private final String filename;
  private final String mediaType;
  private final String sha256;
  private final byte[] content;

  public ManagedResourceDownload(String filename, String mediaType, String sha256, byte[] content) {
    this.filename = Objects.requireNonNull(filename, "filename");
    this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
    this.sha256 = Objects.requireNonNull(sha256, "sha256");
    this.content = Arrays.copyOf(Objects.requireNonNull(content, "content"), content.length);
  }

  public byte[] getContent() {
    return Arrays.copyOf(content, content.length);
  }
}
