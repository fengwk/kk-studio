package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Daemon coding capabilities 共享的不可变本地执行配置。
 *
 * <p>工具目录不是静态配置：每次调用的显式 workdir 来自该调用自己的 arguments。本配置只持有本地执行程序、输出存储与进程参数。
 *
 * <p>没有本地二进制 resource 导出根：图片等二进制内容以 {@code BinaryResultContent} 直接进入终态，由 Daemon 侧上传端口直传全局对象
 * 存储；本地只保留大文本全文（{@link TextOutputStore}）。
 *
 * <p>所有取值都来自 CLI（{@link fun.fengwk.kkstudio.harness.daemon.DaemonConfig}），本类型不再读取任何 {@code
 * kkstudio.daemon.*} 系统属性：一条配置只有一个权威来源。
 */
public record CodingToolsConfig(
    int previewMaxLines,
    int previewMaxBytes,
    String bashExecutable,
    TextOutputStore textOutputStore,
    String lspBridgeCommand,
    String javapExecutable) {

  public static final int DEFAULT_PREVIEW_MAX_LINES = 2000;
  public static final int DEFAULT_PREVIEW_MAX_BYTES = 50 * 1024;
  public static final String DEFAULT_BASH_EXECUTABLE = "bash";
  public static final String DEFAULT_JAVAP_EXECUTABLE = "javap";

  public CodingToolsConfig {
    if (previewMaxLines < 1 || previewMaxBytes < 1) {
      throw new IllegalArgumentException("preview output limits must be positive");
    }
    bashExecutable = requireNonBlank(bashExecutable, "bashExecutable");
    textOutputStore = Objects.requireNonNull(textOutputStore, "textOutputStore");
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
      int previewMaxLines,
      int previewMaxBytes,
      String bashExecutable,
      TextOutputStore textOutputStore) {
    this(
        previewMaxLines,
        previewMaxBytes,
        bashExecutable,
        textOutputStore,
        null,
        DEFAULT_JAVAP_EXECUTABLE);
  }

  /**
   * 用 CLI 的显式取值与数据目录资源根构建配置。
   *
   * <p>输出布局完全由数据目录决定：本地大文本落在 {@code <resources>/text} 与 {@code <resources>/staging}。
   *
   * @param resources 数据目录下的资源根（{@code <data-dir>/resources}）
   * @param bashExecutable 显式 bash 可执行文件，空白时回退默认值
   * @param lspBridgeCommand 显式 LSP bridge 命令；空白表示禁用
   * @param javapExecutable 显式 javap 可执行文件，空白时回退默认值
   */
  public static CodingToolsConfig fromCli(
      Path resources, String bashExecutable, String lspBridgeCommand, String javapExecutable) {
    Path root = Objects.requireNonNull(resources, "resources").toAbsolutePath().normalize();
    return new CodingToolsConfig(
        DEFAULT_PREVIEW_MAX_LINES,
        DEFAULT_PREVIEW_MAX_BYTES,
        bashExecutable == null || bashExecutable.isBlank()
            ? DEFAULT_BASH_EXECUTABLE
            : bashExecutable,
        TextOutputStore.open(root.resolve("text"), root.resolve("staging")),
        lspBridgeCommand,
        javapExecutable == null || javapExecutable.isBlank()
            ? DEFAULT_JAVAP_EXECUTABLE
            : javapExecutable);
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
