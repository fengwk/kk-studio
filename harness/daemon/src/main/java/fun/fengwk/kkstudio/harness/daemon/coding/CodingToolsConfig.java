package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Daemon coding tools 共享的不可变本地执行配置。 */
public record CodingToolsConfig(
    Path environmentRoot,
    Path defaultWorkdir,
    int previewMaxLines,
    int previewMaxBytes,
    String bashExecutable,
    ResourceStore resourceStore,
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
    resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
    lspBridgeCommand = blankToNull(lspBridgeCommand);
    javapExecutable =
        requireNonBlank(
            javapExecutable == null || javapExecutable.isBlank()
                ? DEFAULT_JAVAP_EXECUTABLE
                : javapExecutable,
            "javapExecutable");
  }

  /** 便捷构造器：保持可选 LSP bridge 处于禁用状态。 */
  public CodingToolsConfig(
      Path environmentRoot,
      Path defaultWorkdir,
      int previewMaxLines,
      int previewMaxBytes,
      String bashExecutable,
      ResourceStore resourceStore) {
    this(
        environmentRoot,
        defaultWorkdir,
        previewMaxLines,
        previewMaxBytes,
        bashExecutable,
        resourceStore,
        null,
        DEFAULT_JAVAP_EXECUTABLE);
  }

  /** 使用 CLI 冻结的唯一 workdir，并从其余稳定 Daemon 系统属性构建独立运行配置。 */
  public static CodingToolsConfig fromSystemProperties(Path workdir) {
    Path root = canonicalDirectory(workdir, "workdir");
    Path resourceDirectory =
        Path.of(
            System.getProperty(
                "kkstudio.daemon.resource-directory",
                root.resolve(".kkstudio").resolve("resources").toString()));
    return new CodingToolsConfig(
        root,
        root,
        DEFAULT_PREVIEW_MAX_LINES,
        DEFAULT_PREVIEW_MAX_BYTES,
        System.getProperty("kkstudio.daemon.bash", "bash"),
        new LocalFileResourceStore(
            resourceDirectory,
            parsePositiveLong(
                System.getProperty("kkstudio.daemon.max-resource-bytes"),
                LocalFileResourceStore.DEFAULT_MAX_RESOURCE_BYTES,
                "kkstudio.daemon.max-resource-bytes")),
        System.getProperty("kkstudio.daemon.lsp-bridge"),
        System.getProperty("kkstudio.daemon.javap", DEFAULT_JAVAP_EXECUTABLE));
  }

  private static long parsePositiveLong(String value, long defaultValue, String property) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      long parsed = Long.parseLong(value.trim());
      if (parsed <= 0) {
        throw new IllegalArgumentException(property + " must be positive");
      }
      return parsed;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(property + " must be a positive long", error);
    }
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
