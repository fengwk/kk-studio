package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 测试用的 {@link CodingToolsConfig} 构造基座。
 *
 * <p>测试关心的是能力行为而不是资源布局细节，因此这里统一按生产语义在给定根目录下建立 {@code resources/{text,staging}}，避免每个测试各自重复路径拼接。
 */
public final class TestCodingConfig {

  private TestCodingConfig() {}

  /** 默认阈值、默认 bash/javap、启用 echo bridge 的配置。 */
  public static CodingToolsConfig withBridge(Path root) {
    return build(root, 2000, 50 * 1024, "echo", "javap", "bash");
  }

  /** 默认阈值、禁用 LSP bridge 的配置。 */
  public static CodingToolsConfig withoutBridge(Path root) {
    return build(root, 2000, 50 * 1024, null, "javap", "bash");
  }

  /** 指定内联阈值的配置。 */
  public static CodingToolsConfig withLimits(Path root, int previewMaxLines, int previewMaxBytes) {
    return build(root, previewMaxLines, previewMaxBytes, null, "javap", "bash");
  }

  /** 指定 bridge 命令与内联阈值的配置。 */
  public static CodingToolsConfig withBridgeCommand(
      Path root, int previewMaxLines, int previewMaxBytes, String bridgeCommand) {
    return build(root, previewMaxLines, previewMaxBytes, bridgeCommand, "javap", "bash");
  }

  /** 指定 bash 可执行文件的配置，用于验证启动失败路径。 */
  public static CodingToolsConfig withBash(
      Path root, int previewMaxLines, int previewMaxBytes, String bashExecutable) {
    return build(root, previewMaxLines, previewMaxBytes, null, "javap", bashExecutable);
  }

  /** 文本输出落在 {@code <root>/resources/{text,staging}}，与生产数据目录布局一致。 */
  public static TextOutputStore textOutputStore(Path root) {
    Path resources = Objects.requireNonNull(root, "root").resolve("resources");
    return TextOutputStore.open(resources.resolve("text"), resources.resolve("staging"));
  }

  private static CodingToolsConfig build(
      Path root,
      int previewMaxLines,
      int previewMaxBytes,
      String bridgeCommand,
      String javapExecutable,
      String bashExecutable) {
    return new CodingToolsConfig(
        root,
        previewMaxLines,
        previewMaxBytes,
        bashExecutable,
        textOutputStore(root),
        bridgeCommand,
        javapExecutable);
  }
}
