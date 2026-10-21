package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.mcp.McpCancellationToken;
import fun.fengwk.kkstudio.harness.mcp.McpCancelledException;
import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpClientFactory;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
import fun.fengwk.kkstudio.harness.mcp.McpTimeoutException;
import fun.fengwk.kkstudio.harness.mcp.McpToolCallResult;
import fun.fengwk.kkstudio.harness.mcp.RemoteMcpConfig;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpConfigParser;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Streamable HTTP MCP 工具可执行实现。
 *
 * <p>发送前必须按模型可见工具名重新读取当前 DB 行并比对 Server 可选拔状态；server 行或工具行缺失、server 被禁用或非 {@code
 * AVAILABLE}、工具归属漂移，都在这里终结为本次调用的脱敏错误结果，且不产生任何网络 I/O。可用性只在此判定， 因此它既不改变已持久化定义的 planning，也不影响其他工具；使用单一
 * McpDeadline 覆盖初始化与调用； 错误信息绝不泄露 URL、敏感 headers 或系统内部异常栈。
 */
@Slf4j
public final class RemoteMcpExecutableTool implements Tool {

  private static final String CONFIG_CHANGED_MESSAGE = "MCP tool configuration has changed";
  private static final String TIMEOUT_MESSAGE = "MCP tool call timed out";
  private static final String FAILED_MESSAGE = "MCP tool call failed";

  private final ToolDescriptor descriptor;
  private final String serverName;
  private final String toolName;
  private final String sourceName;
  private final McpServerRepository repository;
  private final BiFunction<RemoteMcpConfig, McpDeadline, McpClient> clientProvider;
  private final Function<String, String> envProvider;

  public RemoteMcpExecutableTool(
      ToolDescriptor descriptor,
      String serverName,
      String toolName,
      String sourceName,
      McpServerRepository repository) {
    this(
        descriptor,
        serverName,
        toolName,
        sourceName,
        repository,
        McpClientFactory::createRemote,
        System::getenv);
  }

  public RemoteMcpExecutableTool(
      ToolDescriptor descriptor,
      String serverName,
      String toolName,
      String sourceName,
      McpServerRepository repository,
      BiFunction<RemoteMcpConfig, McpDeadline, McpClient> clientProvider,
      Function<String, String> envProvider) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.serverName = Objects.requireNonNull(serverName, "serverName");
    this.toolName = Objects.requireNonNull(toolName, "toolName");
    if (sourceName == null || sourceName.isBlank()) {
      throw new IllegalArgumentException("sourceName must not be blank");
    }
    this.sourceName = sourceName;
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clientProvider = Objects.requireNonNull(clientProvider, "clientProvider");
    this.envProvider = Objects.requireNonNull(envProvider, "envProvider");
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.none();
  }

  /**
   * 阻塞调用远端 MCP 工具：client 在本线程内创建并于退出时关闭，因此调用线程返回即本地资源静止。线程被中断时取消令牌、关闭 client 并抛出 {@link
   * InterruptedException}。
   */
  @Override
  public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException {
    Objects.requireNonNull(request, "request");

    Optional<McpServer> serverOpt = repository.getByName(serverName);
    Optional<McpTool> toolOpt = repository.getTool(toolName);
    if (serverOpt.isEmpty() || toolOpt.isEmpty()) {
      return staleConfiguration(request);
    }

    McpServer server = serverOpt.get();
    McpTool tool = toolOpt.get();
    if (!server.enabled()
        || server.discoveryStatus() != McpDiscoveryStatus.AVAILABLE
        || !serverName.equals(tool.serverName())) {
      return staleConfiguration(request);
    }

    // MCP 工具没有 arguments 级超时契约：有效超时即 definition 默认值（Server timeout 配置），
    // 已由 Tool SPI 在执行前解析一次；这里不再与 DB 当前值做二次 min clamp。
    McpDeadline deadline = McpDeadline.of(request.timeout());
    McpCancellationToken token = new McpCancellationToken();
    try {
      Map<String, String> resolvedHeaders =
          McpConfigParser.resolveHeaders(server.headers(), envProvider);
      RemoteMcpConfig config = new RemoteMcpConfig(server.url(), resolvedHeaders);
      try (McpClient client = clientProvider.apply(config, deadline)) {
        McpToolCallResult outcome =
            client.callTool(sourceName, request.call().argumentsJson(), deadline, token);
        return ToolOutcome.withoutEffects(
            new ToolResult(
                request.call().id(), outcome.contents(), outcome.error(), outcome.detailsJson()));
      }
    } catch (McpTimeoutException timeout) {
      return error(request, TIMEOUT_MESSAGE);
    } catch (RuntimeException failure) {
      // client 已由 try-with-resources 关闭；中断是取消的唯一来源。
      if (Thread.interrupted()) {
        token.cancel();
        throw new InterruptedException();
      }
      if (failure instanceof McpCancelledException) {
        throw new InterruptedException();
      }
      return error(request, FAILED_MESSAGE);
    }
  }

  private static ToolOutcome staleConfiguration(ToolExecutionRequest request) {
    return error(request, CONFIG_CHANGED_MESSAGE);
  }

  private static ToolOutcome error(ToolExecutionRequest request, String message) {
    return ToolOutcome.withoutEffects(
        new ToolResult(request.call().id(), List.of(new TextResultContent(message)), true, "{}"));
  }
}
