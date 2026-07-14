package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Immutable local execution configuration shared by Daemon coding tools. */
public record CodingToolsConfig(
    Path workspaceRoot,
    Path defaultWorkdir,
    int previewMaxLines,
    int previewMaxBytes,
    String bashExecutable,
    String rgExecutable,
    String fdExecutable,
    ArtifactSink artifactSink) {

  public static final int DEFAULT_PREVIEW_MAX_LINES = 2000;
  public static final int DEFAULT_PREVIEW_MAX_BYTES = 50 * 1024;

  public CodingToolsConfig {
    workspaceRoot = canonicalDirectory(workspaceRoot, "workspaceRoot");
    defaultWorkdir =
        Objects.requireNonNull(defaultWorkdir, "defaultWorkdir").toAbsolutePath().normalize();
    if (!defaultWorkdir.startsWith(workspaceRoot)) {
      throw new IllegalArgumentException("defaultWorkdir must be inside workspaceRoot");
    }
    defaultWorkdir = canonicalDirectory(defaultWorkdir, "defaultWorkdir");
    if (!defaultWorkdir.startsWith(workspaceRoot)) {
      throw new IllegalArgumentException("defaultWorkdir resolves outside workspaceRoot");
    }
    if (previewMaxLines < 1 || previewMaxBytes < 1) {
      throw new IllegalArgumentException("preview output limits must be positive");
    }
    bashExecutable = requireNonBlank(bashExecutable, "bashExecutable");
    rgExecutable = requireNonBlank(rgExecutable, "rgExecutable");
    fdExecutable = requireNonBlank(fdExecutable, "fdExecutable");
    artifactSink = Objects.requireNonNull(artifactSink, "artifactSink");
  }

  /** Builds the standalone configuration from stable Daemon system properties. */
  public static CodingToolsConfig fromSystemProperties() {
    Path root =
        Path.of(
            System.getProperty("kkstudio.daemon.workspace-root", System.getProperty("user.dir")));
    Path defaultWorkdir =
        Path.of(System.getProperty("kkstudio.daemon.default-workdir", root.toString()));
    Path artifactDirectory =
        Path.of(
            System.getProperty(
                "kkstudio.daemon.artifact-directory",
                root.resolve(".kkstudio-artifacts").toString()));
    return new CodingToolsConfig(
        root,
        defaultWorkdir,
        DEFAULT_PREVIEW_MAX_LINES,
        DEFAULT_PREVIEW_MAX_BYTES,
        System.getProperty("kkstudio.daemon.bash", "bash"),
        System.getProperty("kkstudio.daemon.rg", "rg"),
        System.getProperty("kkstudio.daemon.fd", "fd"),
        new LocalFileArtifactSink(artifactDirectory));
  }

  private static Path canonicalDirectory(Path value, String name) {
    try {
      Path path = Objects.requireNonNull(value, name).toRealPath();
      if (!Files.isDirectory(path)) {
        throw new IllegalArgumentException(name + " must be an existing directory");
      }
      return path;
    } catch (IOException error) {
      throw new IllegalArgumentException(name + " must be an existing directory", error);
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
