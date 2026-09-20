package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
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
 * 动态 MCP 工具目录：每次调用现读 DB 的 MCP 持久化行并映射为可执行 {@link ToolContribution}。
 *
 * <p>目录区分两个入口：{@link #selectableTools()} 是 UI/配置选择面，只选拔 {@code enabled=true} 且 {@code AVAILABLE} 的
 * server 行下的工具；{@link #findTool(String)} 是规划/查找面，只要求 {@code mcp_tool} 行与其所属 {@code mcp_server}
 * 行存在，不按 enabled 或发现状态过滤。因此 server 的可用性不改变已持久化定义的可解析性：被禁用或发现失败的 server 只让自身工具的调用在 {@code
 * RemoteMcpExecutableTool} 处 fail closed，绝不把调用期可用性提前变成 planning 期的 tool-not-found。
 *
 * <p>工具身份就是模型可见工具名（{@code mcp_tool.name}）。该名含 {@code _} 分隔段，而 ContributionId.localName 要求 canonical
 * 小写 dotted/dashed 形式，因此贡献身份按 {@code _ -> -} 转换；模型可见名只有 {@code [a-z0-9_]} 字符， 该转换在目录内一一对应，不引入任何隐藏
 * UUID 或修订号。
 */
public final class McpToolCatalog implements RuntimeToolCatalog {

  /** MCP 工具贡献的唯一 contributor 身份。 */
  public static final ContributorId CONTRIBUTOR_ID = new ContributorId("platform.mcp");

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
        .flatMap(
            server ->
                repository.listTools(server.getName()).stream()
                    .map(tool -> contribution(tool, server)))
        .sorted(Comparator.comparing(contribution -> contribution.definition().descriptor().name()))
        .toList();
  }

  /**
   * 按模型可见工具名解析持久化定义：只要求工具行存在且其 server 行存在，不按 enabled 或发现状态过滤。
   *
   * <p>server 的可用性是调用期事实：禁用或非 AVAILABLE 的 server 下工具仍必须在 planning 期可解析并绑定， 由 {@link
   * RemoteMcpExecutableTool} 在真正发送前 fail closed，绝不把该失败提前成 planning 期的 tool-not-found。
   */
  @Override
  public Optional<ToolContribution> findTool(String toolName) {
    Objects.requireNonNull(toolName, "toolName");
    return repository
        .getTool(toolName)
        .flatMap(
            tool ->
                repository
                    .getByName(tool.getServerName())
                    .map(server -> contribution(tool, server)));
  }

  private boolean isServerSelectable(McpServer server) {
    return server != null
        && server.isEnabled()
        && server.getDiscoveryStatus() == McpDiscoveryStatus.AVAILABLE;
  }

  /** 把模型可见工具名转换为 contribution localName：{@code _} 改为 {@code -} 以符合 canonical 语法。 */
  public static String localName(String toolName) {
    Objects.requireNonNull(toolName, "toolName");
    return toolName.replace('_', '-');
  }

  private ToolContribution contribution(McpTool tool, McpServer server) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            tool.getName(),
            tool.getDescription(),
            RENDERER_KEY,
            decodeSchema(tool),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofMillis(server.getTimeoutMillis()));
    AgentToolDefinition definition = new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE);
    Tool executable =
        new RemoteMcpExecutableTool(
            descriptor,
            tool.getServerName(),
            tool.getName(),
            tool.getSourceName(),
            repository,
            executor);

    return new ToolContribution(
        new ContributionId(CONTRIBUTOR_ID, localName(tool.getName())),
        definition,
        executable,
        executable.requirements(),
        0);
  }

  private static InputSchema decodeSchema(McpTool tool) {
    return new SchemaJsonCodec().decode(tool.getInputSchemaJson());
  }
}
