package fun.fengwk.kkstudio.studio.runtime;

import java.io.InputStream;
import java.util.Objects;

public record ResourceReadHandle(String mediaType, long sizeBytes, InputStream content) {

  public ResourceReadHandle {
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(content, "content");
  }
}
