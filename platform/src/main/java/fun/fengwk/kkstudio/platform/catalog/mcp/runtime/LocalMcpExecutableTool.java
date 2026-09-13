package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpConfigParser;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Local MCP 工具可执行实现。
 *
 * <p>发送前围栏比对 Server 与 Tool 状态；将请求构造成 {@code mcp.local.call} capability 规范参数并通过 {@link
 * BoundEnvironment} 执行。
 */
@Slf4j
public final class LocalMcpExecutableTool implements Tool {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  public static final String CAPABILITY_TOOL_NAME = "mcp_local_call";
  private static final String CONFIG_CHANGED_MESSAGE = "MCP tool configuration has changed";
  private static final String ENV_MISMATCH_MESSAGE =
      "Environment binding missing or mismatch for local MCP tool";

  private final ToolDescriptor descriptor;
  private final UUID serverId;
  private final UUID toolId;
  private final String sourceName;
  private final UUID targetEnvironmentId;
  private final long frozenConfigVersion;
  private final long frozenSchemaRevision;
  private final McpServerRepository repository;

  public LocalMcpExecutableTool(
      ToolDescriptor descriptor,
      UUID serverId,
      UUID toolId,
      String sourceName,
      UUID targetEnvironmentId,
      long frozenConfigVersion,
      long frozenSchemaRevision,
      McpServerRepository repository) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.serverId = Objects.requireNonNull(serverId, "serverId");
    this.toolId = Objects.requireNonNull(toolId, "toolId");
    if (sourceName == null || sourceName.isBlank()) {
      throw new IllegalArgumentException("sourceName must not be blank");
    }
    this.sourceName = sourceName;
    this.targetEnvironmentId = Objects.requireNonNull(targetEnvironmentId, "targetEnvironmentId");
    this.frozenConfigVersion = frozenConfigVersion;
    this.frozenSchemaRevision = frozenSchemaRevision;
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.environment(EnvironmentId.of(targetEnvironmentId));
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

    if (server.getConnectionType() != McpConnectionType.LOCAL
        || !Objects.equals(server.getEnvironmentId(), targetEnvironmentId)
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

    Optional<BoundEnvironment> boundEnvOpt =
        request.context() == null ? Optional.empty() : request.context().environment();
    if (boundEnvOpt.isEmpty()
        || !boundEnvOpt.get().environmentId().equals(EnvironmentId.of(targetEnvironmentId))) {
      listener.onComplete(
          new ToolResult(
              request.call().id(),
              List.of(new TextResultContent(ENV_MISMATCH_MESSAGE)),
              true,
              "{}"));
      return CompletedToolExecutionHandle.INSTANCE;
    }

    ObjectNode wrapperNode = OBJECT_MAPPER.createObjectNode();
    wrapperNode.put("serverId", serverId.toString());
    wrapperNode.put("configVersion", frozenConfigVersion);
    try {
      wrapperNode.set("config", OBJECT_MAPPER.readTree(McpConfigParser.toFullConfigJson(server)));
      wrapperNode.put("toolName", sourceName);
      wrapperNode.set("arguments", OBJECT_MAPPER.readTree(request.call().argumentsJson()));
    } catch (JsonProcessingException error) {
      listener.onComplete(
          new ToolResult(
              request.call().id(),
              List.of(new TextResultContent("Failed to serialize local MCP capability call")),
              true,
              "{}"));
      return CompletedToolExecutionHandle.INSTANCE;
    }

    EnvironmentCapabilityDescriptor capabilityDesc =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.MCP_LOCAL_CALL);
    ToolDescriptor capabilityToolDescriptor =
        new ToolDescriptor(
            CAPABILITY_TOOL_NAME,
            capabilityDesc.version(),
            "MCP local call capability",
            "capability",
            capabilityDesc.inputSchema(),
            ToolSideEffect.NON_IDEMPOTENT,
            capabilityDesc.timeout());
    ToolCall wrappedCall =
        new ToolCall(request.call().id(), CAPABILITY_TOOL_NAME, wrapperNode.toString());
    ToolExecutionRequest wrappedRequest =
        new ToolExecutionRequest(
            capabilityToolDescriptor, wrappedCall, request.timeout(), request.context());
    return boundEnvOpt.get().execute(capabilityDesc, wrappedRequest, listener);
  }
}
