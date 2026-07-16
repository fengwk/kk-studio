package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test-friendly artifact sink retaining immutable copies in memory. */
public final class InMemoryArtifactSink implements ArtifactSink, ArtifactSource {

  private final Map<String, byte[]> artifacts = new ConcurrentHashMap<>();

  @Override
  public ArtifactRef store(byte[] bytes, String mediaType) {
    String id = UUID.randomUUID().toString();
    artifacts.put(id, Arrays.copyOf(bytes, bytes.length));
    return new ArtifactRef(id, mediaType, bytes.length);
  }

  @Override
  public byte[] read(ArtifactRef ref) throws IOException {
    Objects.requireNonNull(ref, "ref");
    byte[] bytes = artifacts.get(ref.artifactId());
    if (bytes == null) {
      throw new IOException(new NoSuchElementException("artifact not found: " + ref.artifactId()));
    }
    if (bytes.length != ref.sizeBytes()) {
      throw new IOException(
          "artifact size mismatch for " + ref.artifactId() + ": declared=" + ref.sizeBytes());
    }
    return Arrays.copyOf(bytes, bytes.length);
  }

  /** Returns an immutable-copy equivalent of previously stored bytes, or {@code null}. */
  public byte[] get(String artifactId) {
    byte[] bytes = artifacts.get(artifactId);
    return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
  }
}
