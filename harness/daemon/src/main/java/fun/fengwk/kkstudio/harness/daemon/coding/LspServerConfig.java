package fun.fengwk.kkstudio.harness.daemon.coding;

import java.util.List;
import java.util.Locale;

/**
 * 预先配置的一台本机语言服务器。
 *
 * <p>共享 codec 已校验条目的不可变运行时快照；不重复配置校验，也不自动安装或下载语言服务器。
 *
 * @param id 服务器标识，同时是 read header 中 {@code lsp: supported (<id>)} 的语言标签
 * @param command 承载 stdio LSP 的完整命令行；首元素必须是 PATH 上的可执行程序或绝对路径
 * @param extensions 触发该服务器的文件扩展名，必须带前导点；大小写不敏感
 * @param rootMarkers 项目根标记：在 git 边界内命中这些标记的最浅目录优先
 * @param firstMatchMarkers 项目根标记：命中这些标记的离目标文件最近的目录优先
 */
public record LspServerConfig(
    String id,
    List<String> command,
    List<String> extensions,
    List<String> rootMarkers,
    List<String> firstMatchMarkers) {

  public LspServerConfig {
    command = List.copyOf(command);
    extensions = extensions.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    rootMarkers = List.copyOf(rootMarkers);
    firstMatchMarkers = List.copyOf(firstMatchMarkers);
  }

  /** 该扩展名（含前导点）是否由本服务器负责。 */
  boolean handles(String extension) {
    return extensions.contains(extension.toLowerCase(Locale.ROOT));
  }
}
