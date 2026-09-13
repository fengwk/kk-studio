package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpStableIds;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * 动态 MCP 工具目录：每次调用现读 DB 中符合条件的 mcp_server / mcp_tool 行并映射为可执行 {@link ToolContribution}。
 *
 * <p>选拔条件：仅当 Server enabled=true、status=AVAILABLE、discoveredVersion==version 且 Tool available=true
 * 时方可进入目录。
 */
public final class McpToolCatalog implements RuntimeToolCatalog {

  public static final String RENDERER_KEY = "tool";

  private final McpServerRepository repository;
  private final ExecutorService executor;

  public McpToolCatalog(McpServerRepository repository, ExecutorService executor) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  @Override
  public List<ToolContribution> selectableTools() {
    return repository.listAllServers().stream()
        .filter(this::isServerSelectable)
        .flatMap(server -> serverTools(server).stream())
        .sorted(Comparator.comparing(contribution -> contribution.definition().id().value()))
        .toList();
  }

  @Override
  public Optional<ToolContribution> findTool(AgentToolId id) {
    Objects.requireNonNull(id, "id");
    return McpStableIds.parseAgentToolId(id.value())
        .flatMap(repository::getToolById)
        .filter(McpTool::isAvailable)
        .flatMap(
            tool ->
                repository
                    .getById(tool.getServerId())
                    .filter(this::isServerSelectable)
                    .map(server -> contribution(server, tool)));
  }

  private boolean isServerSelectable(McpServer server) {
    return server != null
        && server.isEnabled()
        && server.getDiscoveryStatus() == McpDiscoveryStatus.AVAILABLE
        && Objects.equals(server.getDiscoveredVersion(), server.getVersion());
  }

  private List<ToolContribution> serverTools(McpServer server) {
    return repository.listAvailableTools(server.getId()).stream()
        .map(tool -> contribution(server, tool))
        .toList();
  }

  private ToolContribution contribution(McpServer server, McpTool tool) {
    String descriptorVersion = server.getVersion() + "." + tool.getSchemaRevision();
    ToolDescriptor descriptor =
        new ToolDescriptor(
            tool.getModelName(),
            descriptorVersion,
            tool.getDescription(),
            RENDERER_KEY,
            decodeSchema(tool),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofMillis(server.getTimeoutMillis()));
    AgentToolDefinition definition =
        new AgentToolDefinition(
            McpStableIds.agentToolId(tool.getId()), descriptor, ToolVisibility.SELECTABLE);

    Tool executable;
    if (server.getConnectionType() == McpConnectionType.REMOTE) {
      executable =
          new RemoteMcpExecutableTool(
              descriptor,
              server.getId(),
              tool.getId(),
              tool.getSourceName(),
              server.getVersion(),
              tool.getSchemaRevision(),
              repository,
              executor);
    } else {
      executable =
          new LocalMcpExecutableTool(
              descriptor,
              server.getId(),
              tool.getId(),
              tool.getSourceName(),
              server.getEnvironmentId(),
              server.getVersion(),
              tool.getSchemaRevision(),
              repository);
    }

    return new ToolContribution(
        new ContributionId(McpStableIds.CONTRIBUTOR_ID, McpStableIds.localName(tool.getId())),
        definition,
        executable,
        executable.requirements(),
        0);
  }

  private static InputSchema decodeSchema(McpTool tool) {
    return new SchemaJsonCodec().decode(tool.getInputSchemaJson());
  }
}
