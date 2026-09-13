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
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.RemoteConnectionConfig;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Remote MCP 工具可执行实现。
 *
 * <p>发送前必须重新围栏比对当前 Server 与 Tool 状态；使用单一 McpDeadline 覆盖初始化与调用； 错误信息绝不泄露 URL、敏感 headers 或系统内部异常栈。
 */
@Slf4j
public final class RemoteMcpExecutableTool implements Tool {

  private static final String CONFIG_CHANGED_MESSAGE = "MCP tool configuration has changed";
  private static final String TIMEOUT_MESSAGE = "MCP tool call timed out";
  private static final String FAILED_MESSAGE = "MCP tool call failed";

  private final ToolDescriptor descriptor;
  private final UUID serverId;
  private final UUID toolId;
  private final String sourceName;
  private final long frozenConfigVersion;
  private final long frozenSchemaRevision;
  private final McpServerRepository repository;
  private final ExecutorService executor;
  private final BiFunction<RemoteMcpConfig, McpDeadline, McpClient> clientProvider;
  private final Function<String, String> envProvider;

  public RemoteMcpExecutableTool(
      ToolDescriptor descriptor,
      UUID serverId,
      UUID toolId,
      String sourceName,
      long frozenConfigVersion,
      long frozenSchemaRevision,
      McpServerRepository repository,
      ExecutorService executor) {
    this(
        descriptor,
        serverId,
        toolId,
        sourceName,
        frozenConfigVersion,
        frozenSchemaRevision,
        repository,
        executor,
        McpClientFactory::createRemote,
        System::getenv);
  }

  public RemoteMcpExecutableTool(
      ToolDescriptor descriptor,
      UUID serverId,
      UUID toolId,
      String sourceName,
      long frozenConfigVersion,
      long frozenSchemaRevision,
      McpServerRepository repository,
      ExecutorService executor,
      BiFunction<RemoteMcpConfig, McpDeadline, McpClient> clientProvider,
      Function<String, String> envProvider) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.serverId = Objects.requireNonNull(serverId, "serverId");
    this.toolId = Objects.requireNonNull(toolId, "toolId");
    if (sourceName == null || sourceName.isBlank()) {
      throw new IllegalArgumentException("sourceName must not be blank");
    }
    this.sourceName = sourceName;
    this.frozenConfigVersion = frozenConfigVersion;
    this.frozenSchemaRevision = frozenSchemaRevision;
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

    Optional<McpServer> serverOpt = repository.getById(serverId);
    Optional<McpTool> toolOpt = repository.getToolById(toolId);

    if (serverOpt.isEmpty() || toolOpt.isEmpty()) {
      listener.onComplete(
          new ToolResult(
              request.call().id(),
              List.of(new TextResultContent(CONFIG_CHANGED_MESSAGE)),
              true,
              "{}"));
      return CompletedToolExecutionHandle.INSTANCE;
    }

    McpServer server = serverOpt.get();
    McpTool tool = toolOpt.get();

    if (server.getConnectionType() != McpConnectionType.REMOTE
        || !server.isEnabled()
        || server.getDiscoveryStatus() != McpDiscoveryStatus.AVAILABLE
        || server.getVersion() != frozenConfigVersion
        || !Objects.equals(server.getDiscoveredVersion(), server.getVersion())
        || !tool.isAvailable()
        || tool.getSchemaRevision() != frozenSchemaRevision) {
      listener.onComplete(
          new ToolResult(
              request.call().id(),
              List.of(new TextResultContent(CONFIG_CHANGED_MESSAGE)),
              true,
              "{}"));
      return CompletedToolExecutionHandle.INSTANCE;
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
                RemoteConnectionConfig remoteConfig =
                    (RemoteConnectionConfig)
                        McpConfigParser.parseConnectionConfig(
                            server.getConnectionType(), server.getConnectionConfig());
                Map<String, String> resolvedHeaders =
                    McpConfigParser.resolveRemoteHeaders(remoteConfig.headers(), envProvider);

                RemoteMcpConfig config = new RemoteMcpConfig(remoteConfig.url(), resolvedHeaders);
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
}
