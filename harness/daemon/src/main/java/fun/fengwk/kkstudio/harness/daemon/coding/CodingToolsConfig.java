package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Immutable local execution configuration shared by Daemon coding tools. */
public record CodingToolsConfig(
    Path environmentRoot,
    Path defaultWorkdir,
    int previewMaxLines,
    int previewMaxBytes,
    String bashExecutable,
    String rgExecutable,
    String fdExecutable,
    ArtifactSink artifactSink,
    String lspBridgeCommand,
    String javapExecutable) {

  public static final int DEFAULT_PREVIEW_MAX_LINES = 2000;
  public static final int DEFAULT_PREVIEW_MAX_BYTES = 50 * 1024;
  public static final String DEFAULT_JAVAP_EXECUTABLE = "javap";

  public CodingToolsConfig {
    environmentRoot = canonicalDirectory(environmentRoot, "environmentRoot");
    defaultWorkdir =
        Objects.requireNonNull(defaultWorkdir, "defaultWorkdir").toAbsolutePath().normalize();
    if (!defaultWorkdir.startsWith(environmentRoot)) {
      throw new IllegalArgumentException("defaultWorkdir must be inside environmentRoot");
    }
    defaultWorkdir = canonicalDirectory(defaultWorkdir, "defaultWorkdir");
    if (!defaultWorkdir.startsWith(environmentRoot)) {
      throw new IllegalArgumentException("defaultWorkdir resolves outside environmentRoot");
    }
    if (previewMaxLines < 1 || previewMaxBytes < 1) {
      throw new IllegalArgumentException("preview output limits must be positive");
    }
    bashExecutable = requireNonBlank(bashExecutable, "bashExecutable");
    rgExecutable = requireNonBlank(rgExecutable, "rgExecutable");
    fdExecutable = requireNonBlank(fdExecutable, "fdExecutable");
    artifactSink = Objects.requireNonNull(artifactSink, "artifactSink");
    lspBridgeCommand = blankToNull(lspBridgeCommand);
    javapExecutable =
        requireNonBlank(
            javapExecutable == null || javapExecutable.isBlank()
                ? DEFAULT_JAVAP_EXECUTABLE
                : javapExecutable,
            "javapExecutable");
  }

  /** Convenience constructor that leaves the optional LSP bridge disabled. */
  public CodingToolsConfig(
      Path environmentRoot,
      Path defaultWorkdir,
      int previewMaxLines,
      int previewMaxBytes,
      String bashExecutable,
      String rgExecutable,
      String fdExecutable,
      ArtifactSink artifactSink) {
    this(
        environmentRoot,
        defaultWorkdir,
        previewMaxLines,
        previewMaxBytes,
        bashExecutable,
        rgExecutable,
        fdExecutable,
        artifactSink,
        null,
        DEFAULT_JAVAP_EXECUTABLE);
  }

  /** Builds the standalone configuration from stable Daemon system properties. */
  public static CodingToolsConfig fromSystemProperties() {
    Path root =
        Path.of(
            System.getProperty("kkstudio.daemon.environment-root", System.getProperty("user.dir")));
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
        new LocalFileArtifactSink(artifactDirectory),
        System.getProperty("kkstudio.daemon.lsp-bridge"),
        System.getProperty("kkstudio.daemon.javap", DEFAULT_JAVAP_EXECUTABLE));
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

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
