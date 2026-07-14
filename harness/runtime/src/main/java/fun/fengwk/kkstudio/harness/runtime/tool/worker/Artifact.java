package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import java.util.Arrays;
import java.util.Objects;

/** Workspace-scoped immutable full tool output. */
public record Artifact(
    long id,
    long workspaceId,
    String mediaType,
    String encoding,
    byte[] content,
    long sizeBytes,
    String sha256) {
  public Artifact {
    if (id <= 0 || workspaceId <= 0 || sizeBytes < 0) {
      throw new IllegalArgumentException("artifact identifiers and size must be valid");
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
