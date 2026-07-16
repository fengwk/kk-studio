package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/** Local filesystem artifact sink for standalone Daemon deployments. */
public final class LocalFileArtifactSink implements ArtifactSink, ArtifactSource {

  private final Path directory;

  public LocalFileArtifactSink(Path directory) {
    this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
  }

  @Override
  public ArtifactRef store(byte[] bytes, String mediaType) throws IOException {
    Files.createDirectories(directory);
    String id = UUID.randomUUID().toString();
    Files.write(
        directory.resolve(id), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    return new ArtifactRef(id, mediaType, bytes.length);
  }

  @Override
  public byte[] read(ArtifactRef ref) throws IOException {
    Objects.requireNonNull(ref, "ref");
    Path resolved = resolveSafe(ref.artifactId());
    byte[] bytes = Files.readAllBytes(resolved);
    if (bytes.length != ref.sizeBytes()) {
      throw new IOException(
          "artifact size mismatch for "
              + ref.artifactId()
              + ": declared="
              + ref.sizeBytes()
              + " actual="
              + bytes.length);
    }
    return bytes;
  }

  /** Returns the directory used by this local sink. */
  public Path directory() {
    return directory;
  }

  /**
   * 将 {@code artifactId} 作为单段文件名解析到配置目录内，禁止 {@code ..}、绝对路径或符号链接越界到目录之外。
   *
   * <p>本方法只信任本组件生成的 UUID artifactId；任何其它输入一律拒绝，避免协议/索引错配导致路径越界。
   */
  private Path resolveSafe(String artifactId) throws IOException {
    if (artifactId == null || artifactId.isBlank()) {
      throw new IOException("artifactId must not be blank");
    }
    if (artifactId.indexOf('/') >= 0
        || artifactId.indexOf('\\') >= 0
        || artifactId.equals(".")
        || artifactId.equals("..")) {
      throw new IOException("artifactId escapes artifact directory: " + artifactId);
    }
    Path candidate = directory.resolve(artifactId);
    Path canonicalDirectory = directory.toRealPath();
    Path canonical = candidate.toRealPath();
    if (!canonical.startsWith(canonicalDirectory)) {
      throw new IOException("artifactId resolves outside artifact directory: " + artifactId);
    }
    return canonical;
  }
}
