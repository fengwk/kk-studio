package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpStableIds;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpConnectionSpec;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClientFactory;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 动态 MCP 工具目录：每次调用现读 DB 中全部 mcp_server / mcp_tool 行并映射为可执行 {@link ToolContribution}。
 *
 * <p>映射合同：AgentToolId 与 ContributionId 由 mcp_tool 稳定 UUID 派生；ToolDescriptor.version 是 server
 * version 的稳定十进制字符串；rendererKey 恒为 {@code tool}；requirements 为 none（environmentRequired=false）；
 * sideEffect 恒为 NON_IDEMPOTENT；timeout 来自 server 配置。Tool execute 使用 per-call MCP client 调用
 * source_name（见 {@link McpExecutableTool}）。目录本身绝不缓存连接或工具行。
 */
public final class McpToolCatalog implements RuntimeToolCatalog {

  /** MCP 工具贡献的稳定 rendererKey。 */
  public static final String RENDERER_KEY = "tool";

  private final McpServerRepository repository;
  private final McpToolClientFactory clientFactory;

  public McpToolCatalog(McpServerRepository repository, McpToolClientFactory clientFactory) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
  }

  @Override
  public List<ToolContribution> selectableTools() {
    return repository.listAllServers().stream()
        .flatMap(server -> serverTools(server).stream())
        .sorted(Comparator.comparing(contribution -> contribution.definition().id().value()))
        .toList();
  }

  @Override
  public Optional<ToolContribution> findTool(AgentToolId id) {
    Objects.requireNonNull(id, "id");
    return McpStableIds.parseAgentToolId(id.value())
        .flatMap(repository::getToolById)
        .flatMap(
            tool ->
                repository.getById(tool.getServerId()).map(server -> contribution(server, tool)));
  }

  private List<ToolContribution> serverTools(McpServer server) {
    return repository.listTools(server.getId()).stream()
        .map(tool -> contribution(server, tool))
        .toList();
  }

  private ToolContribution contribution(McpServer server, McpTool tool) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            tool.getModelName(),
            Long.toString(server.getVersion()),
            tool.getDescription(),
            RENDERER_KEY,
            decodeSchema(tool),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofMillis(server.getTimeoutMillis()));
    AgentToolDefinition definition =
        new AgentToolDefinition(
            McpStableIds.agentToolId(tool.getId()), descriptor, ToolVisibility.SELECTABLE);
    McpExecutableTool.SourceName source = new McpExecutableTool.SourceName(tool.getSourceName());
    McpExecutableTool executable =
        new McpExecutableTool(
            descriptor,
            source,
            new McpConnectionSpec(
                server.getUrl(), server.getBearerToken(), server.getTimeoutMillis()),
            clientFactory);
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
