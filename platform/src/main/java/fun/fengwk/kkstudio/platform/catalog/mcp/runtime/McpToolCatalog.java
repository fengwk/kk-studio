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
 * 动态 MCP 工具目录：每次调用现读 DB 中符合条件且 enabled 的 AVAILABLE server 行并映射为可执行 {@link ToolContribution}。
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
        .flatMap(server -> repository.listTools(server.getName()).stream())
        .map(tool -> contribution(tool))
        .sorted(Comparator.comparing(contribution -> contribution.definition().descriptor().name()))
        .toList();
  }

  @Override
  public Optional<ToolContribution> findTool(String toolName) {
    Objects.requireNonNull(toolName, "toolName");
    return repository
        .getTool(toolName)
        .flatMap(
            tool ->
                repository
                    .getByName(tool.getServerName())
                    .filter(this::isServerSelectable)
                    .map(server -> contribution(tool)));
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

  private ToolContribution contribution(McpTool tool) {
    McpServer server = repository.getByName(tool.getServerName()).orElseThrow();
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
