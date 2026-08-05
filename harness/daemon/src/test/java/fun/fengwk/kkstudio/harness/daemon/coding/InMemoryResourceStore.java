package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Test-friendly resource store retaining immutable copies in memory with canonical file URIs. */
public final class InMemoryResourceStore implements ResourceStore {

  private final Map<String, byte[]> resources = new ConcurrentHashMap<>();

  @Override
  public ResourceRef store(byte[] bytes, String mediaType) {
    Objects.requireNonNull(bytes, "bytes");
    String digest = sha256Hex(bytes);
    resources.put(digest, Arrays.copyOf(bytes, bytes.length));
    return new ResourceRef(
        "file:///export/" + digest, mediaType, null, (long) bytes.length, digest);
  }

  @Override
  public byte[] read(ResourceRef ref) throws IOException {
    Objects.requireNonNull(ref, "ref");
    byte[] bytes = resources.get(ref.sha256());
    if (bytes == null) {
      throw new IOException(
          new NoSuchElementException("resource not found: " + ref.sha256() + " " + ref.uri()));
    }
    if (ref.size() == null || bytes.length != ref.size()) {
      throw new IOException("resource size mismatch for " + ref.uri() + ": declared=" + ref.size());
    }
    if (ref.sha256() == null || !ref.sha256().equals(sha256Hex(bytes))) {
      throw new IOException("resource sha256 mismatch for " + ref.uri());
    }
    return Arrays.copyOf(bytes, bytes.length);
  }

  /** Returns an immutable-copy equivalent of previously stored bytes, or {@code null}. */
  public byte[] get(String digest) {
    byte[] bytes = resources.get(digest);
    return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  static String sha256Of(String text) {
    return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
  }
}
