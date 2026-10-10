package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Daemon coding capabilities 共享的不可变本地执行配置。
 *
 * <p>工具目录不是静态配置：{@code process.exec} 的 workdir 来自该调用自己的
 * arguments；文件工具只接受绝对路径，没有任何默认目录。本配置只持有本地执行程序、LSP 服务器集合、输出存储与进程参数。
 *
 * <p>没有本地二进制 resource 导出根：图片等二进制内容以 {@code BinaryResultContent} 直接进入终态，由 Daemon 侧上传端口直传全局对象
 * 存储；本地只保留大文本全文（{@link TextOutputStore}）。
 *
 * <p>所有取值都来自配置文件派生的 {@link fun.fengwk.kkstudio.harness.daemon.DaemonConfig}，本类型不再读取任何 {@code
 * kkstudio.daemon.*} 系统属性：一条配置只有一个权威来源。
 */
public record CodingToolsConfig(
    int previewMaxLines,
    int previewMaxBytes,
    String bashExecutable,
    TextOutputStore textOutputStore,
    LspDiscovery lsp) {

  public static final int DEFAULT_PREVIEW_MAX_LINES = 2000;
  public static final int DEFAULT_PREVIEW_MAX_BYTES = 50 * 1024;
  public static final String DEFAULT_BASH_EXECUTABLE = "bash";

  public CodingToolsConfig {
    if (previewMaxLines < 1 || previewMaxBytes < 1) {
      throw new IllegalArgumentException("preview output limits must be positive");
    }
    bashExecutable = requireNonBlank(bashExecutable, "bashExecutable");
    textOutputStore = Objects.requireNonNull(textOutputStore, "textOutputStore");
    lsp = Objects.requireNonNull(lsp, "lsp");
  }

  /** 便捷构造器：没有任何 LSP 服务器。 */
  public CodingToolsConfig(
      int previewMaxLines,
      int previewMaxBytes,
      String bashExecutable,
      TextOutputStore textOutputStore) {
    this(previewMaxLines, previewMaxBytes, bashExecutable, textOutputStore, LspDiscovery.empty());
  }

  /**
   * 用运行时的显式取值与受控临时产物根构建配置。
   *
   * <p>输出布局完全由受控临时产物根决定：工具外化的本地大文本落在 {@code <tmp>/workspaces} 下的登记临时 workspace 内。
   *
   * @param tmp 数据目录下的受控临时产物根（{@code <data-dir>/tmp}）
   * @param bashExecutable 显式 bash 可执行文件，空白时回退默认值
   * @param lsp 预先配置的 LSP 服务器；未配置时为空集合
   */
  public static CodingToolsConfig fromRuntime(Path tmp, String bashExecutable, LspDiscovery lsp) {
    Path root = Objects.requireNonNull(tmp, "tmp").toAbsolutePath().normalize();
    return new CodingToolsConfig(
        DEFAULT_PREVIEW_MAX_LINES,
        DEFAULT_PREVIEW_MAX_BYTES,
        bashExecutable == null || bashExecutable.isBlank()
            ? DEFAULT_BASH_EXECUTABLE
            : bashExecutable,
        TextOutputStore.open(root),
        lsp);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
