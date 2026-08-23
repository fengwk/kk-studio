package fun.fengwk.kkstudio.platform.studio.function.h3;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 必须关闭的 ComfyUI `/view` 下载流。 */
public record H3ComfyDownload(InputStream content, long length, String mediaType)
    implements AutoCloseable {

  public H3ComfyDownload {
    content = Objects.requireNonNull(content, "content");
    if (length <= 0L) {
      throw new IllegalArgumentException("length must be positive");
    }
    if (mediaType == null || mediaType.isBlank()) {
      mediaType = "application/octet-stream";
    }
  }

  @Override
  public void close() throws IOException {
    content.close();
  }
}
