package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.ai.mcp.McpServerConfigDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDiscoveryResponseDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

/** Platform MCP server 应用服务：CRUD/CAS、显式发现与引用保护流程。 */
public interface McpServerService {

  Page<McpServerDTO> pageServers(PageQuery pageQuery);

  /** 获取 Server 安全公开视图。 */
  McpServerDTO getServer(String id);

  /** 读取 Server 规范化完整配置 JSON（包含默认值，不解析 ${VAR}）。 */
  McpServerConfigDTO getServerConfig(String id);

  /** 创建 Server 配置：仅校验并持久化为 UNVERIFIED，不发起任何网络或本地进程 I/O。 */
  McpServerDTO createServer(McpServerCreateDTO createDTO);

  /** 基于 (id, expectedVersion) 的 CAS 全量更新：仅更新配置并重置状态为 UNVERIFIED，不发起发现。 */
  McpServerDTO updateServer(String id, McpServerUpdateDTO updateDTO);

  /** 显式触发发现：Remote 执行同步握手与发现，Local 提交异步 MCP_SERVER_DISCOVER 管理操作。 */
  McpServerDiscoveryResponseDTO discoverServer(String id, String expectedVersion);

  /** 基于 (id, expectedVersion) 的硬删除 CAS：被 agent 引用时拒绝删除。 */
  void deleteServer(String id, String expectedVersion);
}
