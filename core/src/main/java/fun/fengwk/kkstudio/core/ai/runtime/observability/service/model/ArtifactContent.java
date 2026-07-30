package fun.fengwk.kkstudio.core.ai.runtime.observability.service.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Application-facing immutable artifact content for observability downloads.
 *
 * <p>Independent of the runtime {@code Artifact} persistence type; content bytes are defensively
 * copied on construction and on every read.
 */
public record ArtifactContent(
    long id, String mediaType, String encoding, byte[] content, long sizeBytes, String sha256) {

  public ArtifactContent {
    if (id <= 0 || sizeBytes < 0) {
      throw new IllegalArgumentException("artifact identifier and size must be valid");
    }
    mediaType = requireNonBlank(mediaType, "mediaType");
    encoding = requireNonBlank(encoding, "encoding");
    content = Arrays.copyOf(Objects.requireNonNull(content, "content"), content.length);
    if (content.length != sizeBytes) {
      throw new IllegalArgumentException("artifact content size does not match sizeBytes");
    }
    sha256 = requireNonBlank(sha256, "sha256");
  }

  @Override
  public byte[] content() {
    return Arrays.copyOf(content, content.length);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
