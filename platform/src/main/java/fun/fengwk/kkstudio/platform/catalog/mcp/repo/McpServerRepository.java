package fun.fengwk.kkstudio.platform.catalog.mcp.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Platform MCP server 与已发现工具的仓库。 */
public interface McpServerRepository {

  Page<McpServer> page(PageQuery pageQuery);

  /** 返回全部 server 行（按 name 排序）；供动态目录现读。 */
  List<McpServer> listAllServers();

  Optional<McpServer> getById(UUID id);

  /** 返回有效的 Server 并持有其行锁，直到外层事务结束。 */
  Optional<McpServer> getByIdForUpdate(UUID id);

  Optional<McpServer> getByName(String name);

  boolean create(McpServer server);

  /** 基于 (id, expectedVersion) 的原子 CAS 更新。行被更新且版本恰好 +1 时返回 true。 */
  boolean updateById(McpServer server, long expectedVersion);

  /** 基于 (id, expectedVersion) 的硬删除 CAS；工具行随 ON DELETE CASCADE 物理删除。 */
  boolean deleteById(UUID id, long expectedVersion);

  Optional<McpTool> getToolById(UUID toolId);

  List<McpTool> listTools(UUID serverId);

  void insertTool(McpTool tool);

  /** 保留稳定 UUID/model_name，仅刷新 description 与 input schema。 */
  void updateTool(McpTool tool);

  /** 按 (serverId, sourceName) 精确删除一个工具行。 */
  void deleteTool(UUID serverId, String sourceName);

  /** model_name 是否已被占用；refresh 时用 {@code excludingToolId} 排除本行。 */
  boolean isModelNameTaken(String modelName, UUID excludingToolId);

  /**
   * 返回任一 agent_definition.config.toolIds 引用的 MCP AgentToolId 集合（canonical {@code mcp.<32hex>}
   * 文本）。必须在持有目标 server 行锁的事务内调用。
   */
  List<String> selectReferencedAgentToolIds();
}
