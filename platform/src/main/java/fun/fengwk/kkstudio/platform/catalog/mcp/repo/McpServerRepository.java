package fun.fengwk.kkstudio.platform.catalog.mcp.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.util.List;
import java.util.Optional;

/** Platform MCP server 与当前发现结果的仓库。 */
public interface McpServerRepository {

  Page<McpServer> page(PageQuery pageQuery);

  /** 返回全部 server 行（按 name 排序）。 */
  List<McpServer> listAllServers();

  Optional<McpServer> getByName(String name);

  /** 返回有效的 Server 并持有其行锁，直到外层事务结束。 */
  Optional<McpServer> getForUpdate(String name);

  boolean create(McpServer server);

  /** 基于 (name, expectedVersion) 的原子全量 CAS 更新；重置为 UNVERIFIED 并将版本 +1。 */
  boolean update(McpServer server, long expectedVersion);

  /** 基于 (name, expectedVersion) 的原子 CAS 状态更新；不变更 version。 */
  boolean updateDiscoveryStatus(String name, long expectedVersion, McpDiscoveryStatus status);

  /** 基于 (name, expectedVersion) 的硬删除 CAS；工具行随 ON DELETE CASCADE 物理删除。 */
  boolean delete(String name, long expectedVersion);

  /** 按模型可见 name 返回工具行。 */
  Optional<McpTool> getTool(String name);

  /** 列出该 server 下全部工具行（按 name 排序）。 */
  List<McpTool> listTools(String serverName);

  /** 列出全库工具行（按 name 排序）。 */
  List<McpTool> listAllTools();

  /** 统计 server 下工具数。 */
  int countTools(String serverName);

  /** 物理删除该 server 的全部工具行。 */
  void deleteTools(String serverName);

  void insertTool(McpTool tool);

  /** 返回任一 agent_definition.config.tools 引用的 MCP 模型可见工具名集合。 */
  List<String> selectReferencedToolNames();
}
