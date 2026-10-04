package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.share.ai.environment.DaemonLspConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预先配置的 LSP 服务器集合，以及从目标文件出发的服务器选择、可执行程序解析与项目根发现。
 *
 * <p>项目根只由配置里的标记决定，调用方不提供额外 cwd：从文件所在目录向上扫描，命中 {@code rootMarkers} 的最浅目录优先，其次是命中 {@code
 * firstMatchMarkers} 的最近目录，都没有则回退文件父目录。{@code .git}（目录或 worktree 的 gitfile）只作为扫描边界，
 * 本身不被当作项目根，也不会跨过边界复用上层仓库的标记。
 *
 * <p>{@link #support(Path)} 是 read header 的只读发现入口：只做配置匹配与可执行程序探测，绝不启动服务器。
 */
public final class LspDiscovery {

  private final List<LspServerConfig> servers;
  private final Map<String, Optional<String>> executables = new ConcurrentHashMap<>();

  private LspDiscovery(List<LspServerConfig> servers) {
    this.servers = List.copyOf(servers);
  }

  /** 没有配置任何服务器：read header 报 {@code unsupported}，LSP 工具返回明确的未配置错误。 */
  public static LspDiscovery empty() {
    return new LspDiscovery(List.of());
  }

  /** 用已校验的条目构建发现器；同一扩展名由先声明的服务器负责。 */
  public static LspDiscovery of(List<LspServerConfig> servers) {
    Objects.requireNonNull(servers, "servers");
    Map<String, LspServerConfig> unique = new LinkedHashMap<>();
    for (LspServerConfig server : servers) {
      if (unique.putIfAbsent(server.id(), server) != null) {
        throw new IllegalArgumentException("duplicate lsp server id: " + server.id());
      }
    }
    return new LspDiscovery(new ArrayList<>(unique.values()));
  }

  /** 采用共享 codec 已校验的结构化配置；这里只做不可变运行时快照，不重复校验。 */
  public static LspDiscovery fromConfiguration(DaemonLspConfiguration configuration) {
    if (configuration == null) {
      return empty();
    }
    List<LspServerConfig> servers = new ArrayList<>();
    configuration
        .getServers()
        .forEach(
            (id, server) ->
                servers.add(
                    new LspServerConfig(
                        id,
                        server.getCommand(),
                        server.getExtensions(),
                        server.getRootMarkers(),
                        server.getFirstMatchMarkers())));
    return new LspDiscovery(servers);
  }

  /** 已配置服务器的声明顺序。 */
  public List<LspServerConfig> servers() {
    return servers;
  }

  /** 负责该文件的服务器；扩展名匹配大小写不敏感，同一扩展名由先声明者优先。 */
  public Optional<LspServerConfig> server(Path file) {
    String extension = extension(file);
    if (extension == null) {
      return Optional.empty();
    }
    return servers.stream().filter(server -> server.handles(extension)).findFirst();
  }

  /**
   * 该文件对应的服务器，未配置时抛出可操作的错误。
   *
   * @throws IllegalStateException 没有服务器负责该文件类型
   */
  public LspServerConfig requireServer(Path file) {
    return server(file)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No LSP server configured for "
                        + file
                        + ". Add the server to lsp.servers in the daemon configuration."));
  }

  /**
   * read header 使用的只读判定：只匹配配置并探测可执行程序，绝不启动任何语言服务器。
   *
   * <p>结果区分“未配置服务器”“已配置但可执行程序不存在”与“可用”三态，调用方据此渲染只读状态。
   */
  public LspSupport support(Path file) {
    Optional<LspServerConfig> matched = server(file);
    if (matched.isEmpty()) {
      return LspSupport.unsupported();
    }
    LspServerConfig config = matched.get();
    return LspSupport.supported(config.id(), executable(config).isPresent());
  }

  /** 已解析的服务器命令首元素；命令不在 PATH 上且不是可执行文件时为空。结果按命令缓存。 */
  public Optional<String> executable(LspServerConfig server) {
    Objects.requireNonNull(server, "server");
    return executables.computeIfAbsent(
        server.command().getFirst(),
        ignored -> ExecutableResolver.resolve(server.command().getFirst()));
  }

  /**
   * 该服务器对应的可执行程序，未安装时抛出可操作的错误。
   *
   * @throws IllegalStateException 命令既不是 PATH 上的可执行程序也不是绝对可执行文件
   */
  public String requireExecutable(LspServerConfig server) {
    return executable(server)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "LSP server '"
                        + server.id()
                        + "' is not installed. Command '"
                        + server.command().getFirst()
                        + "' must be available on PATH or be an absolute executable path."));
  }

  /**
   * 目标文件所属的项目根，用作 {@code rootUri}/workspaceFolders 与服务器进程目录。
   *
   * <p>项目根不改变文件工具自己的路径规则：它只影响语言服务器的视图。
   */
  public Path workspaceRoot(LspServerConfig server, Path file) {
    Objects.requireNonNull(server, "server");
    Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    Path directory = target.getParent();
    if (directory == null) {
      return target;
    }
    Path gitRoot = gitRoot(directory);
    // pi-base：扫描包含 gitRoot 自己（其标记可以是项目根），但不向 gitRoot 的父目录继续，
    // 这样嵌套 worktree 不会复用主仓的构建标记。
    Path limit = gitRoot == null ? null : gitRoot.getParent();
    Path buildRoot = null;
    Path firstMatchRoot = null;
    for (Path current = directory; current != null; current = current.getParent()) {
      if (limit != null && current.equals(limit)) {
        break;
      }
      if (!server.rootMarkers().isEmpty() && hasMarker(current, server.rootMarkers())) {
        buildRoot = current;
      }
      if (firstMatchRoot == null && hasMarker(current, server.firstMatchMarkers())) {
        firstMatchRoot = current;
      }
    }
    if (buildRoot != null) {
      return buildRoot;
    }
    return firstMatchRoot != null ? firstMatchRoot : directory;
  }

  private static boolean hasMarker(Path directory, List<String> markers) {
    for (String marker : markers) {
      if (Files.exists(directory.resolve(marker))) {
        return true;
      }
    }
    return false;
  }

  /** 最近的 git 边界目录：{@code .git} 是普通目录，也可以是指向主仓的 worktree gitfile。 */
  private static Path gitRoot(Path directory) {
    for (Path current = directory; current != null; current = current.getParent()) {
      if (Files.exists(current.resolve(".git"))) {
        return current;
      }
    }
    return null;
  }

  private static String extension(Path file) {
    Path name = file.getFileName();
    if (name == null) {
      return null;
    }
    String value = name.toString();
    int dot = value.lastIndexOf('.');
    if (dot <= 0 || dot == value.length() - 1) {
      return null;
    }
    return value.substring(dot).toLowerCase(Locale.ROOT);
  }
}
