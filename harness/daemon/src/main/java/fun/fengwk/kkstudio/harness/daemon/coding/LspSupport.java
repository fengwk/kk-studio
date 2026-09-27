package fun.fengwk.kkstudio.harness.daemon.coding;

import java.util.Optional;

/**
 * 文件对应的 LSP 支持状态：read header 的只读判定结果，判定过程不启动任何语言服务器。
 *
 * <p>三态与 pi-base 的 {@code supportsLsp} 对齐：未配置服务器、已配置服务器但可执行程序不存在、以及可用。
 */
public record LspSupport(Optional<String> language, boolean available) {

  /** 没有任何服务器负责该文件类型。 */
  public static LspSupport unsupported() {
    return new LspSupport(Optional.empty(), false);
  }

  /** 有服务器负责该文件类型；{@code available} 表示它的可执行程序真实存在。 */
  public static LspSupport supported(String language, boolean available) {
    return new LspSupport(Optional.of(language), available);
  }

  public LspSupport {
    language = language == null ? Optional.empty() : language;
  }

  /** 文件类型是否被配置的服务器覆盖。 */
  public boolean supported() {
    return language.isPresent();
  }
}
