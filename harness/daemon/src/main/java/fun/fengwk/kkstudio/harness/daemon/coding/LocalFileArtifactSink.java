package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/** Local filesystem artifact sink for standalone Daemon deployments. */
public final class LocalFileArtifactSink implements ArtifactSink {

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

  /** Returns the directory used by this local sink. */
  public Path directory() {
    return directory;
  }
}
