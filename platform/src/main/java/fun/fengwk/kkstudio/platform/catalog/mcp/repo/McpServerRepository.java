package fun.fengwk.kkstudio.platform.catalog.mcp.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Platform MCP server 与已发现工具的仓库。 */
public interface McpServerRepository {

  Page<McpServer> page(PageQuery pageQuery);

  /** 返回全部 server 行（按 name 排序）。 */
  List<McpServer> listAllServers();

  Optional<McpServer> getById(UUID id);

  /** 返回有效的 Server 并持有其行锁，直到外层事务结束。 */
  Optional<McpServer> getByIdForUpdate(UUID id);

  Optional<McpServer> getByName(String name);

  boolean create(McpServer server);

  /** 基于 (id, expectedVersion) 的原子 CAS 更新；重置为 UNVERIFIED 并将版本 +1。 */
  boolean updateById(McpServer server, long expectedVersion);

  /** 基于 (id, expectedVersion) 的原子 CAS 更新发现状态与发现版本；不变更 version。 */
  boolean updateDiscoveryResult(
      UUID id, long expectedVersion, McpDiscoveryStatus discoveryStatus, Long discoveredVersion);

  /** 基于 (id, expectedVersion) 的硬删除 CAS；工具行随 ON DELETE CASCADE 物理删除。 */
  boolean deleteById(UUID id, long expectedVersion);

  Optional<McpTool> getToolById(UUID toolId);

  /** 列出该 server 下所有工具（含 tombstone）。 */
  List<McpTool> listTools(UUID serverId);

  /** 列出该 server 下可用工具（available = true）。 */
  List<McpTool> listAvailableTools(UUID serverId);

  /** 列出全库可用工具（available = true）。 */
  List<McpTool> listAllAvailableTools();

  /** 统计 server 下可用工具数。 */
  int countAvailableTools(UUID serverId);

  void insertTool(McpTool tool);

  /** 保留稳定 UUID/model_name，更新 description/schema/available/schemaRevision。 */
  void updateTool(McpTool tool);

  /** 按 (serverId, sourceName) 精确删除一个工具行。 */
  void deleteTool(UUID serverId, String sourceName);

  /** model_name 是否已被占用；refresh 时用 {@code excludingToolId} 排除本行。 */
  boolean isModelNameTaken(String modelName, UUID excludingToolId);

  /** 返回任一 agent_definition.config.toolIds 引用的 MCP AgentToolId 集合。 */
  List<String> selectReferencedAgentToolIds();
}
