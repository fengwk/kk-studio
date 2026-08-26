package fun.fengwk.kkstudio.harness.daemon.mcp;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerStatus;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpToolDescriptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Daemon 本地 MCP server registry。
 *
 * <p>每个配置的 server 独立初始化：一个 server 失败只记录为 FAILED，不影响其它 server、coding capabilities 与 skills 启动。 摘要与
 * READY/FAILED 状态在 {@link #start()} 后冻结，直到 daemon 重启。{@link #close()} 对每个成功创建的 client 恰好关闭一次。
 */
public final class McpServerRegistry implements AutoCloseable {

  private final McpConfig config;
  private final McpServerClientFactory factory;
  private final Duration defaultTimeout;
  private final List<ServerState> states = new ArrayList<>();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();

  public McpServerRegistry(
      McpConfig config, McpServerClientFactory factory, Duration defaultTimeout) {
    this.config = Objects.requireNonNull(config, "config");
    this.factory = Objects.requireNonNull(factory, "factory");
    this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
    if (defaultTimeout.isZero() || defaultTimeout.isNegative()) {
      throw new IllegalArgumentException("defaultTimeout must be positive");
    }
  }

  /** 空 registry：无配置 server，factory 不会被调用。 */
  public static McpServerRegistry empty() {
    return new McpServerRegistry(
        McpConfig.empty(),
        (config, timeout) -> {
          throw new IllegalStateException("empty registry must not create clients");
        },
        Duration.ofSeconds(30));
  }

  /** 独立初始化全部配置的 server；只允许调用一次，失败永不抛出。 */
  public synchronized void start() {
    if (!started.compareAndSet(false, true) || closed.get()) {
      return;
    }
    for (McpServerConfig server : config.servers()) {
      McpServerClient client = null;
      try {
        client = factory.create(server, defaultTimeout);
        List<McpToolSpec> toolSpecs = freeze(client.listTools());
        List<DaemonMcpToolDescriptor> tools = summarize(toolSpecs);
        states.add(
            new ServerState(
                server,
                new DaemonMcpServerDescriptor(
                    server.name(), DaemonMcpServerStatus.READY, null, tools),
                client,
                toolSpecs));
      } catch (RuntimeException error) {
        // client 已创建但工具摘要失败时，必须恰好关闭一次，避免泄漏半初始化 client。
        if (client != null) {
          try {
            client.close();
          } catch (RuntimeException ignored) {
            // 关闭失败不得掩盖初始化失败；client 已不再被引用。
          }
        }
        states.add(
            new ServerState(
                server,
                new DaemonMcpServerDescriptor(
                    server.name(),
                    DaemonMcpServerStatus.FAILED,
                    sanitize(server, error),
                    List.of()),
                null,
                List.of()));
      }
    }
  }

  /** 返回冻结的 MCP server 摘要（保持配置顺序）。 */
  public synchronized List<DaemonMcpServerDescriptor> snapshot() {
    List<DaemonMcpServerDescriptor> result = new ArrayList<>(states.size());
    for (ServerState state : states) {
      result.add(state.summary);
    }
    return List.copyOf(result);
  }

  /** 返回 READY server 的 client；未知或 FAILED server 返回 empty。 */
  public synchronized Optional<McpServerClient> find(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    for (ServerState state : states) {
      if (state.config.name().equals(name)) {
        return state.client == null ? Optional.empty() : Optional.of(state.client);
      }
    }
    return Optional.empty();
  }

  /** 返回 READY server 在启动时冻结的完整工具规格；未知或 FAILED server 返回 empty。 */
  synchronized Optional<List<McpToolSpec>> toolSpecs(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    for (ServerState state : states) {
      if (state.config.name().equals(name)) {
        return state.client == null ? Optional.empty() : Optional.of(state.toolSpecs);
      }
    }
    return Optional.empty();
  }

  /** 精确校验 READY server 的冻结工具列表是否包含 {@code toolName}；未知/FAILED server 或未知工具返回 false。 */
  public synchronized boolean hasTool(String serverName, String toolName) {
    if (serverName == null || serverName.isBlank() || toolName == null || toolName.isBlank()) {
      return false;
    }
    for (ServerState state : states) {
      if (state.config.name().equals(serverName)) {
        if (state.client == null) {
          return false;
        }
        for (McpToolSpec tool : state.toolSpecs) {
          if (tool.name().equals(toolName)) {
            return true;
          }
        }
        return false;
      }
    }
    return false;
  }

  /** 关闭全部成功创建的 client，恰好一次；单个 close 失败被吸收，绝不影响后续 client 与 daemon shutdown 收敛。 */
  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    for (ServerState state : states) {
      McpServerClient client = state.client;
      if (client != null) {
        try {
          client.close();
        } catch (RuntimeException ignored) {
          // shutdown 必须收敛：吸收单个 client 的关闭失败，继续关闭剩余 client。
        }
      }
    }
  }

  private static List<McpToolSpec> freeze(List<McpToolSpec> tools) {
    return List.copyOf(Objects.requireNonNull(tools, "client.listTools()"));
  }

  private static List<DaemonMcpToolDescriptor> summarize(List<McpToolSpec> tools) {
    List<DaemonMcpToolDescriptor> result = new ArrayList<>(tools.size());
    for (McpToolSpec tool : tools) {
      result.add(new DaemonMcpToolDescriptor(tool.name(), tool.description()));
    }
    return List.copyOf(result);
  }

  /** 错误摘要有界化：单行、截断到 wire 上限，并剔除配置中可能被异常消息回显的敏感细节（headers/environment 值、命令、URL）。 */
  private static String sanitize(McpServerConfig server, Throwable error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      message = error.getClass().getSimpleName();
    }
    message = redact(server, message);
    String singleLine = message.replaceAll("\\R+", " ").strip();
    if (singleLine.length() > DaemonMcpServerDescriptor.MAX_ERROR_CHARS) {
      singleLine = singleLine.substring(0, DaemonMcpServerDescriptor.MAX_ERROR_CHARS);
    }
    return singleLine;
  }

  /** 把配置中的敏感明文替换为占位符，防止异常消息回显 headers/environment 值、命令或 URL。 */
  private static String redact(McpServerConfig server, String message) {
    List<String> sensitive = new ArrayList<>();
    if (server.environment() != null) {
      sensitive.addAll(server.environment().values());
    }
    if (server.headers() != null) {
      sensitive.addAll(server.headers().values());
    }
    if (server.command() != null) {
      sensitive.addAll(server.command());
    }
    if (server.url() != null) {
      sensitive.add(server.url());
    }
    String redacted = message;
    for (String value : sensitive) {
      if (value != null && !value.isBlank()) {
        redacted = redacted.replace(value, "***");
      }
    }
    return redacted;
  }

  private static final class ServerState {

    private final McpServerConfig config;
    private final DaemonMcpServerDescriptor summary;
    private final McpServerClient client;
    private final List<McpToolSpec> toolSpecs;

    private ServerState(
        McpServerConfig config,
        DaemonMcpServerDescriptor summary,
        McpServerClient client,
        List<McpToolSpec> toolSpecs) {
      this.config = config;
      this.summary = summary;
      this.client = client;
      this.toolSpecs = toolSpecs;
    }
  }
}
