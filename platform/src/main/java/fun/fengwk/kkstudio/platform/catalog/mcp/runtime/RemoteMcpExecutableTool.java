package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
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
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpConfigParser;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Streamable HTTP MCP 工具可执行实现。
 *
 * <p>发送前必须按模型可见工具名重新读取当前 DB 行并比对 Server 可选拔状态；使用单一 McpDeadline 覆盖初始化与调用； 错误信息绝不泄露 URL、敏感 headers
 * 或系统内部异常栈。
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
  private final ExecutorService executor;
  private final BiFunction<RemoteMcpConfig, McpDeadline, McpClient> clientProvider;
  private final Function<String, String> envProvider;

  public RemoteMcpExecutableTool(
      ToolDescriptor descriptor,
      String serverName,
      String toolName,
      String sourceName,
      McpServerRepository repository,
      ExecutorService executor) {
    this(
        descriptor,
        serverName,
        toolName,
        sourceName,
        repository,
        executor,
        McpClientFactory::createRemote,
        System::getenv);
  }

  public RemoteMcpExecutableTool(
      ToolDescriptor descriptor,
      String serverName,
      String toolName,
      String sourceName,
      McpServerRepository repository,
      ExecutorService executor,
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
    this.executor = Objects.requireNonNull(executor, "executor");
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

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");

    Optional<McpServer> serverOpt = repository.getByName(serverName);
    Optional<McpTool> toolOpt = repository.getTool(toolName);
    if (serverOpt.isEmpty() || toolOpt.isEmpty()) {
      return staleConfiguration(request, listener);
    }

    McpServer server = serverOpt.get();
    McpTool tool = toolOpt.get();
    if (!server.isEnabled()
        || server.getDiscoveryStatus() != McpDiscoveryStatus.AVAILABLE
        || !serverName.equals(tool.getServerName())) {
      return staleConfiguration(request, listener);
    }

    Duration serverTimeout = Duration.ofMillis(server.getTimeoutMillis());
    Duration requestTimeout = request.timeout();
    Duration effectiveBudget =
        (requestTimeout.isZero()
                || requestTimeout.isNegative()
                || requestTimeout.compareTo(serverTimeout) > 0)
            ? serverTimeout
            : requestTimeout;

    McpDeadline deadline = McpDeadline.of(effectiveBudget);
    McpCancellationToken token = new McpCancellationToken();
    AtomicBoolean completed = new AtomicBoolean(false);

    Future<?> worker =
        executor.submit(
            () -> {
              try {
                Map<String, String> resolvedHeaders =
                    McpConfigParser.resolveHeaders(server.getHeaders(), envProvider);
                RemoteMcpConfig config = new RemoteMcpConfig(server.getUrl(), resolvedHeaders);
                try (McpClient client = clientProvider.apply(config, deadline)) {
                  if (token.isCancelled()) {
                    return;
                  }
                  McpToolCallResult outcome =
                      client.callTool(sourceName, request.call().argumentsJson(), deadline, token);
                  if (completed.compareAndSet(false, true)) {
                    listener.onComplete(
                        new ToolResult(
                            request.call().id(),
                            outcome.contents(),
                            outcome.error(),
                            outcome.detailsJson()));
                  }
                }
              } catch (McpCancelledException cancelled) {
                // 取消无需完成回调
              } catch (McpTimeoutException timeout) {
                if (completed.compareAndSet(false, true)) {
                  listener.onComplete(
                      new ToolResult(
                          request.call().id(),
                          List.of(new TextResultContent(TIMEOUT_MESSAGE)),
                          true,
                          "{}"));
                }
              } catch (RuntimeException error) {
                if (completed.compareAndSet(false, true)) {
                  listener.onComplete(
                      new ToolResult(
                          request.call().id(),
                          List.of(new TextResultContent(FAILED_MESSAGE)),
                          true,
                          "{}"));
                }
              }
            });

    return new ToolExecutionHandle() {
      @Override
      public void cancel() {
        token.cancel();
        worker.cancel(true);
      }

      @Override
      public boolean isCancelled() {
        return token.isCancelled();
      }
    };
  }

  private static ToolExecutionHandle staleConfiguration(
      ToolExecutionRequest request, ToolExecutionListener listener) {
    listener.onComplete(
        new ToolResult(
            request.call().id(),
            List.of(new TextResultContent(CONFIG_CHANGED_MESSAGE)),
            true,
            "{}"));
    return CompletedToolExecutionHandle.INSTANCE;
  }
}
