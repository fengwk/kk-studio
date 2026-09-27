package fun.fengwk.kkstudio.harness.daemon.coding;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 预先配置的一台本机语言服务器。
 *
 * <p>条目只描述宿主上已经存在的程序：命令、扩展名与项目根标记是全部配置面，字段缺失或取值越界都在构造期失败关闭， Daemon 不会自动安装或下载语言服务器。
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

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]+");

  public LspServerConfig {
    id = requireId(id);
    command = requireCommand(command);
    extensions = requireExtensions(extensions);
    rootMarkers = requireNonBlankList(rootMarkers, "rootMarkers", true);
    firstMatchMarkers = requireNonBlankList(firstMatchMarkers, "firstMatchMarkers", true);
  }

  /** 该扩展名（含前导点）是否由本服务器负责。 */
  boolean handles(String extension) {
    return extensions.contains(extension.toLowerCase(Locale.ROOT));
  }

  private static String requireId(String value) {
    String trimmed = value == null ? null : value.trim();
    if (trimmed == null || trimmed.isEmpty() || !ID.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(
          "lsp server id must match " + ID.pattern() + " but was: " + value);
    }
    return trimmed;
  }

  private static List<String> requireCommand(List<String> values) {
    Objects.requireNonNull(values, "command");
    if (values.isEmpty()) {
      throw new IllegalArgumentException("command must not be empty");
    }
    return values.stream().map(value -> requireMarker(value, "command", false)).toList();
  }

  private static List<String> requireNonBlankList(
      List<String> values, String name, boolean relative) {
    Objects.requireNonNull(values, name);
    if (values.isEmpty()) {
      return List.of();
    }
    return values.stream().map(value -> requireMarker(value, name, relative)).distinct().toList();
  }

  private static String requireMarker(String value, String name, boolean relative) {
    String trimmed = value == null ? null : value.trim();
    if (trimmed == null || trimmed.isEmpty()) {
      throw new IllegalArgumentException(name + " must not contain blank entries");
    }
    if (relative && (trimmed.startsWith("/") || trimmed.startsWith("\\"))) {
      throw new IllegalArgumentException(name + " entries must be project-relative: " + trimmed);
    }
    return trimmed;
  }

  private static List<String> requireExtensions(List<String> values) {
    Objects.requireNonNull(values, "extensions");
    if (values.isEmpty()) {
      throw new IllegalArgumentException("extensions must not be empty");
    }
    return values.stream()
        .map(
            value -> {
              String trimmed = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
              if (trimmed == null
                  || trimmed.length() < 2
                  || !trimmed.startsWith(".")
                  || trimmed.indexOf('/') >= 0
                  || trimmed.indexOf('\\') >= 0) {
                throw new IllegalArgumentException(
                    "extensions must be file suffixes with a leading dot but was: " + value);
              }
              return trimmed;
            })
        .distinct()
        .toList();
  }
}
