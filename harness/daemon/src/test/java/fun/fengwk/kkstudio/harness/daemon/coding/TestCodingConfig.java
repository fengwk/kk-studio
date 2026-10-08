package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 测试用的 {@link CodingToolsConfig} 构造基座。
 *
 * <p>测试关心的是能力行为而不是资源布局细节，因此这里统一按生产语义在给定根目录下建立 {@code tmp/{text,staging}}，避免每个测试各自重复路径拼接。
 *
 * <p>LSP 相关的构造只提供"配置视角"：命令取本机真实存在的 {@code bash}，因为 read header 的只读判定只探测可执行程序、绝不启动它；真正需要协议交互的测试 自己启动
 * {@code FakeLspServer}。
 */
public final class TestCodingConfig {

  /** 测试用 LSP 服务器 id：read header 报告的语言标签就是它。 */
  public static final String LSP_SERVER_ID = "test-ls";

  private TestCodingConfig() {}

  /** 默认阈值、默认 bash，并配置一个覆盖测试用文件类型的 LSP 服务器。 */
  public static CodingToolsConfig withLsp(Path root) {
    return build(root, 2000, 50 * 1024, testLsp(), "bash");
  }

  /** 默认阈值、没有配置任何 LSP 服务器。 */
  public static CodingToolsConfig withoutLsp(Path root) {
    return build(root, 2000, 50 * 1024, LspDiscovery.empty(), "bash");
  }

  /** 指定内联阈值的配置。 */
  public static CodingToolsConfig withLimits(Path root, int previewMaxLines, int previewMaxBytes) {
    return build(root, previewMaxLines, previewMaxBytes, LspDiscovery.empty(), "bash");
  }

  /** 指定 bash 可执行文件的配置，用于验证启动失败路径。 */
  public static CodingToolsConfig withBash(
      Path root, int previewMaxLines, int previewMaxBytes, String bashExecutable) {
    return build(root, previewMaxLines, previewMaxBytes, LspDiscovery.empty(), bashExecutable);
  }

  /** 指向真实假服务器（{@link FakeLspServer}）的配置，用于端到端协议测试。 */
  public static CodingToolsConfig withFakeLsp(Path root, String mode, Path transcript) {
    return build(
        root,
        2000,
        50 * 1024,
        FakeLspServers.discovery(FakeLspServers.javaServer("fake", mode, root, transcript)),
        "bash");
  }

  /** 覆盖测试常用文件类型的 LSP 发现器；命令是真实存在的 {@code bash}，不会被启动。 */
  public static LspDiscovery testLsp() {
    return LspDiscovery.of(
        List.of(
            new LspServerConfig(
                LSP_SERVER_ID,
                List.of("bash"),
                List.of(".txt", ".java", ".ts"),
                List.of(),
                List.of())));
  }

  /** 文本输出落在 {@code <root>/tmp/{text,staging}}，与生产数据目录布局一致。 */
  public static TextOutputStore textOutputStore(Path root) {
    Path tmp = Objects.requireNonNull(root, "root").resolve("tmp");
    return TextOutputStore.open(tmp.resolve("text"), tmp.resolve("staging"));
  }

  private static CodingToolsConfig build(
      Path root,
      int previewMaxLines,
      int previewMaxBytes,
      LspDiscovery lsp,
      String bashExecutable) {
    return new CodingToolsConfig(
        previewMaxLines, previewMaxBytes, bashExecutable, textOutputStore(root), lsp);
  }
}
