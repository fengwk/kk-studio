package fun.fengwk.kkstudio.studio.runtime;

import java.io.InputStream;
import java.util.Objects;

public record ResourceWriteRequest(
    String mediaType, long sizeBytes, InputStream content, String contentHash) {

  public ResourceWriteRequest {
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(contentHash, "contentHash");
  }
}
